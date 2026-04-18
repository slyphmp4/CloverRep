package com.slyph.cloverrep.storage;

import com.slyph.cloverrep.CloverRepPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class YamlReputationStorage implements ReputationDataSource {
    private static final int DATA_VERSION = 3;
    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CloverRepPlugin plugin;
    private final File file;
    private final FileConfiguration config;

    public YamlReputationStorage(CloverRepPlugin plugin) {
        this.plugin = plugin;

        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        this.file = new File(dataFolder, "reputation.yml");
        if (!file.exists()) {
            try {
                plugin.saveResource("reputation.yml", false);
            } catch (IllegalArgumentException exception) {
                try {
                    file.createNewFile();
                } catch (IOException ioException) {
                    plugin.getLogger().severe("Failed to create reputation.yml");
                }
            }
        }

        this.config = YamlConfiguration.loadConfiguration(file);
        migrateIfNeeded();
    }

    @Override
    public synchronized int getReputation(UUID playerId) {
        if (playerId == null) {
            return 0;
        }
        return config.getInt(playerReputationPath(playerId), 0);
    }

    @Override
    public synchronized void setReputation(UUID playerId, int reputation) {
        if (playerId == null) {
            return;
        }
        config.set(playerReputationPath(playerId), reputation);
    }

    @Override
    public synchronized boolean hasVoted(UUID voterId, UUID targetId) {
        if (voterId == null || targetId == null) {
            return false;
        }
        return getVotersInternal(voterId).contains(targetId);
    }

    @Override
    public synchronized void addVoter(UUID voterId, UUID targetId) {
        if (voterId == null || targetId == null) {
            return;
        }

        Set<UUID> voters = getVotersInternal(voterId);
        voters.add(targetId);
        config.set(votesPath(voterId), toStringList(voters));
    }

    @Override
    public synchronized Set<UUID> getVoters(UUID voterId) {
        if (voterId == null) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(getVotersInternal(voterId));
    }

    @Override
    public synchronized void upsertPlayerIdentity(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) {
            return;
        }

        String sanitizedName = trimPlayerName(playerName);
        config.set(playerNamePath(playerId), sanitizedName);
        config.set(nameIndexPath(sanitizedName), playerId.toString());
    }

    @Override
    public synchronized UUID findUuidByName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        String normalized = normalizeName(playerName);
        String uuidText = config.getString("names." + normalized, "");
        UUID direct = parseUuid(uuidText);
        if (direct != null) {
            return direct;
        }

        ConfigurationSection playersSection = config.getConfigurationSection("players");
        if (playersSection == null) {
            return null;
        }

        for (String key : playersSection.getKeys(false)) {
            UUID uuid = parseUuid(key);
            if (uuid == null) {
                continue;
            }

            String storedName = config.getString("players." + key + ".name", "");
            if (storedName != null && normalizeName(storedName).equals(normalized)) {
                config.set(nameIndexPath(storedName), uuid.toString());
                return uuid;
            }
        }

        return null;
    }

    @Override
    public synchronized String findNameByUuid(UUID playerId) {
        if (playerId == null) {
            return "";
        }
        return config.getString(playerNamePath(playerId), "");
    }

    @Override
    public synchronized Integer applyVote(UUID voterId, UUID targetId, int amount) {
        if (voterId == null || targetId == null) {
            return null;
        }

        Set<UUID> voters = getVotersInternal(voterId);
        if (voters.contains(targetId)) {
            return null;
        }

        voters.add(targetId);
        config.set(votesPath(voterId), toStringList(voters));

        int updated = getReputation(targetId) + amount;
        config.set(playerReputationPath(targetId), updated);
        return updated;
    }

    @Override
    public synchronized Integer adjustReputation(UUID playerId, int amount) {
        if (playerId == null) {
            return null;
        }

        int updated = getReputation(playerId) + amount;
        config.set(playerReputationPath(playerId), updated);
        return updated;
    }

    @Override
    public synchronized String getBackendName() {
        return "local";
    }

    @Override
    public synchronized boolean saveData() {
        try {
            safeSave();
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Failed to save reputation.yml: " + exception.getMessage());
            return false;
        }
    }

    @Override
    public synchronized StorageBackupResult createBackup(File backupDirectory) {
        if (backupDirectory == null) {
            return StorageBackupResult.failure("Backup directory is not defined.");
        }

        if (!backupDirectory.exists() && !backupDirectory.mkdirs()) {
            return StorageBackupResult.failure("Could not create backup directory.");
        }

        String timestamp = LocalDateTime.now().format(BACKUP_FORMAT);
        File backupFile = new File(backupDirectory, "reputation-local-" + timestamp + ".yml");

        try {
            Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return StorageBackupResult.success(backupFile.getName());
        } catch (IOException exception) {
            return StorageBackupResult.failure(exception.getMessage());
        }
    }

    private synchronized void migrateIfNeeded() {
        int version = config.getInt("meta.data-version", 0);
        if (version >= DATA_VERSION && isNormalizedStructure()) {
            return;
        }

        Map<UUID, Integer> reputations = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, Set<UUID>> votes = new LinkedHashMap<>();

        readPlayersSection(reputations, names, votes);
        readLegacyReputationsSection(reputations, names);
        readVotesSection(votes, names);

        config.set("players", null);
        config.set("names", null);
        config.set("reputations", null);
        config.set("votes", null);

        for (Map.Entry<UUID, Integer> entry : reputations.entrySet()) {
            UUID playerId = entry.getKey();
            config.set(playerReputationPath(playerId), entry.getValue());
            String playerName = names.get(playerId);
            if (playerName != null && !playerName.isBlank()) {
                String sanitizedName = trimPlayerName(playerName);
                config.set(playerNamePath(playerId), sanitizedName);
                config.set(nameIndexPath(sanitizedName), playerId.toString());
            }
        }

        for (Map.Entry<UUID, Set<UUID>> entry : votes.entrySet()) {
            config.set(votesPath(entry.getKey()), toStringList(entry.getValue()));
        }

        config.set("meta.data-version", DATA_VERSION);
        saveData();
    }

    private void readPlayersSection(Map<UUID, Integer> reputations, Map<UUID, String> names, Map<UUID, Set<UUID>> votes) {
        ConfigurationSection playersSection = config.getConfigurationSection("players");
        if (playersSection == null) {
            return;
        }

        for (String key : playersSection.getKeys(false)) {
            UUID playerId = parseUuid(key);
            if (playerId != null) {
                int reputation = config.getInt("players." + key + ".reputation", 0);
                reputations.put(playerId, reputation);

                String playerName = config.getString("players." + key + ".name", "");
                if (playerName != null && !playerName.isBlank()) {
                    names.put(playerId, trimPlayerName(playerName));
                }

                List<String> legacyVotes = config.getStringList("players." + key + ".voters");
                if (!legacyVotes.isEmpty()) {
                    Set<UUID> targets = votes.computeIfAbsent(playerId, ignored -> new LinkedHashSet<>());
                    for (String target : legacyVotes) {
                        UUID targetId = parseUuid(target);
                        if (targetId != null) {
                            targets.add(targetId);
                        } else {
                            UUID resolvedTarget = resolveUuidByName(target);
                            if (resolvedTarget != null) {
                                targets.add(resolvedTarget);
                                names.putIfAbsent(resolvedTarget, trimPlayerName(target));
                            }
                        }
                    }
                }
                continue;
            }

            UUID resolved = resolveUuidByName(key);
            if (resolved == null) {
                continue;
            }

            int reputation = config.getInt("players." + key + ".reputation", 0);
            reputations.put(resolved, reputation);
            names.putIfAbsent(resolved, trimPlayerName(key));

            List<String> legacyVotes = config.getStringList("players." + key + ".voters");
            if (!legacyVotes.isEmpty()) {
                Set<UUID> targets = votes.computeIfAbsent(resolved, ignored -> new LinkedHashSet<>());
                for (String target : legacyVotes) {
                    UUID targetId = parseUuid(target);
                    if (targetId != null) {
                        targets.add(targetId);
                    } else {
                        UUID resolvedTarget = resolveUuidByName(target);
                        if (resolvedTarget != null) {
                            targets.add(resolvedTarget);
                            names.putIfAbsent(resolvedTarget, trimPlayerName(target));
                        }
                    }
                }
            }
        }
    }

    private void readLegacyReputationsSection(Map<UUID, Integer> reputations, Map<UUID, String> names) {
        ConfigurationSection reputationsSection = config.getConfigurationSection("reputations");
        if (reputationsSection == null) {
            return;
        }

        for (String key : reputationsSection.getKeys(false)) {
            UUID playerId = parseUuid(key);
            if (playerId == null) {
                playerId = resolveUuidByName(key);
                if (playerId != null) {
                    names.putIfAbsent(playerId, trimPlayerName(key));
                }
            }

            if (playerId == null) {
                continue;
            }

            reputations.putIfAbsent(playerId, reputationsSection.getInt(key, 0));
        }
    }

    private void readVotesSection(Map<UUID, Set<UUID>> votes, Map<UUID, String> names) {
        ConfigurationSection votesSection = config.getConfigurationSection("votes");
        if (votesSection == null) {
            return;
        }

        for (String key : votesSection.getKeys(false)) {
            UUID voterId = parseUuid(key);
            if (voterId == null) {
                voterId = resolveUuidByName(key);
                if (voterId != null) {
                    names.putIfAbsent(voterId, trimPlayerName(key));
                }
            }

            if (voterId == null) {
                continue;
            }

            Set<UUID> targets = votes.computeIfAbsent(voterId, ignored -> new LinkedHashSet<>());
            List<String> rawTargets = votesSection.getStringList(key);
            for (String rawTarget : rawTargets) {
                UUID targetId = parseUuid(rawTarget);
                if (targetId == null) {
                    targetId = resolveUuidByName(rawTarget);
                    if (targetId != null) {
                        names.putIfAbsent(targetId, trimPlayerName(rawTarget));
                    }
                }

                if (targetId != null) {
                    targets.add(targetId);
                }
            }
        }
    }

    private Set<UUID> getVotersInternal(UUID voterId) {
        List<String> votersList = config.getStringList(votesPath(voterId));
        Set<UUID> result = new LinkedHashSet<>();
        for (String voter : votersList) {
            UUID targetId = parseUuid(voter);
            if (targetId != null) {
                result.add(targetId);
            }
        }
        return result;
    }

    private List<String> toStringList(Set<UUID> values) {
        List<String> result = new ArrayList<>();
        for (UUID value : values) {
            result.add(value.toString());
        }
        return result;
    }

    private void safeSave() throws IOException {
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        File backupFile = new File(file.getParentFile(), file.getName() + ".bak");

        config.save(tempFile);

        Path target = file.toPath();
        Path temp = tempFile.toPath();

        if (Files.exists(target)) {
            Files.copy(target, backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean isNormalizedStructure() {
        if (!config.isConfigurationSection("players")) {
            return false;
        }
        if (!config.isConfigurationSection("names")) {
            return false;
        }
        if (!config.isConfigurationSection("votes")) {
            return false;
        }

        ConfigurationSection playersSection = config.getConfigurationSection("players");
        if (playersSection != null) {
            for (String key : playersSection.getKeys(false)) {
                if (parseUuid(key) == null) {
                    return false;
                }
            }
        }

        ConfigurationSection votesSection = config.getConfigurationSection("votes");
        if (votesSection != null) {
            for (String key : votesSection.getKeys(false)) {
                if (parseUuid(key) == null) {
                    return false;
                }
                List<String> targets = votesSection.getStringList(key);
                for (String target : targets) {
                    if (parseUuid(target) == null) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private UUID resolveUuidByName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        Player online = Bukkit.getPlayerExact(playerName);
        if (online == null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().equalsIgnoreCase(playerName)) {
                    online = player;
                    break;
                }
            }
        }

        if (online != null) {
            return online.getUniqueId();
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        return offlinePlayer.getUniqueId();
    }

    private String playerReputationPath(UUID playerId) {
        return "players." + playerId + ".reputation";
    }

    private String playerNamePath(UUID playerId) {
        return "players." + playerId + ".name";
    }

    private String nameIndexPath(String playerName) {
        return "names." + normalizeName(playerName);
    }

    private String votesPath(UUID voterId) {
        return "votes." + voterId;
    }

    private String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimPlayerName(String playerName) {
        String trimmed = playerName == null ? "" : playerName.trim();
        if (trimmed.length() > 16) {
            return trimmed.substring(0, 16);
        }
        return trimmed;
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
