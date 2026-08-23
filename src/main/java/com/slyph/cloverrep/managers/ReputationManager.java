package com.slyph.cloverrep.managers;

import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.storage.MySQLReputationStorage;
import com.slyph.cloverrep.storage.ReputationDataSource;
import com.slyph.cloverrep.storage.StorageBackupResult;
import com.slyph.cloverrep.storage.StorageCheckResult;
import com.slyph.cloverrep.storage.YamlReputationStorage;
import com.slyph.cloverrep.utils.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ReputationManager {
    public enum VoteChangeStatus {
        APPLIED,
        ALREADY_VOTED,
        FAILED
    }

    private final CloverRepPlugin plugin;
    private final ReputationDataSource storage;
    private final Scoreboard scoreboard;
    private final Map<UUID, Integer> reputationCache;
    private final Map<UUID, Set<UUID>> voteCache;
    private final Map<String, UUID> nameToUuidCache;
    private final Map<UUID, String> uuidToNameCache;
    private final Object cacheLock;
    private Objective belowNameObjective;
    private boolean dirty;

    public ReputationManager(CloverRepPlugin plugin) {
        this.plugin = plugin;
        this.storage = createStorage(plugin);
        this.scoreboard = Bukkit.getScoreboardManager() == null ? null : Bukkit.getScoreboardManager().getMainScoreboard();
        this.reputationCache = new HashMap<>();
        this.voteCache = new HashMap<>();
        this.nameToUuidCache = new HashMap<>();
        this.uuidToNameCache = new HashMap<>();
        this.cacheLock = new Object();
        initializeBelowNameObjective();
    }

    public int getReputation(Player player) {
        if (player == null) {
            return 0;
        }
        registerPlayerIdentity(player);
        return getReputation(player.getUniqueId());
    }

    public int getReputation(UUID playerId) {
        if (playerId == null) {
            return 0;
        }

        synchronized (cacheLock) {
            Integer cached = reputationCache.get(playerId);
            if (cached != null) {
                return cached;
            }
        }

        int loaded = storage.getReputation(playerId);

        synchronized (cacheLock) {
            reputationCache.put(playerId, loaded);
        }

        return loaded;
    }

    public int getReputation(String playerName) {
        UUID playerId = findUuidByName(playerName);
        if (playerId == null) {
            return 0;
        }
        return getReputation(playerId);
    }

    public UUID findUuidByName(String playerName) {
        return resolveUuidByName(playerName);
    }

    public String findNameByUuid(UUID playerId) {
        return resolveNameByUuid(playerId);
    }

    public void registerPlayerIdentity(OfflinePlayer player) {
        if (player == null || player.getUniqueId() == null || player.getName() == null || player.getName().isBlank()) {
            return;
        }
        if (cacheIdentity(player.getUniqueId(), player.getName())) {
            storage.upsertPlayerIdentity(player.getUniqueId(), player.getName());
            synchronized (cacheLock) {
                dirty = true;
            }
        }
    }

    public boolean canVote(Player voter, Player target) {
        if (voter == null || target == null) {
            return false;
        }

        registerPlayerIdentity(voter);
        registerPlayerIdentity(target);

        UUID voterId = voter.getUniqueId();
        UUID targetId = target.getUniqueId();
        Set<UUID> votedTargets = getOrLoadVotes(voterId);
        return !votedTargets.contains(targetId);
    }

    public VoteChangeStatus addReputation(Player voter, Player target, int amount, boolean bypassVoteLimit) {
        if (voter == null || target == null) {
            return VoteChangeStatus.FAILED;
        }

        registerPlayerIdentity(voter);
        registerPlayerIdentity(target);

        UUID voterId = voter.getUniqueId();
        UUID targetId = target.getUniqueId();

        if (bypassVoteLimit) {
            Integer adjusted = storage.adjustReputation(targetId, amount);
            if (adjusted == null) {
                return VoteChangeStatus.FAILED;
            }

            synchronized (cacheLock) {
                reputationCache.put(targetId, adjusted);
                dirty = true;
            }

            updatePlayerDisplay(target);
            return VoteChangeStatus.APPLIED;
        }

        Integer updated = storage.applyVote(voterId, targetId, amount);
        if (updated == null) {
            if (storage.hasVoted(voterId, targetId)) {
                synchronized (cacheLock) {
                    voteCache.computeIfAbsent(voterId, ignored -> new HashSet<>()).add(targetId);
                }
                return VoteChangeStatus.ALREADY_VOTED;
            }
            return VoteChangeStatus.FAILED;
        }

        synchronized (cacheLock) {
            voteCache.computeIfAbsent(voterId, ignored -> new HashSet<>()).add(targetId);
            reputationCache.put(targetId, updated);
            dirty = true;
        }

        updatePlayerDisplay(target);
        return VoteChangeStatus.APPLIED;
    }

    public void updateAllOnlinePlayers() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            registerPlayerIdentity(online);
            updatePlayerDisplay(online);
        }
    }

    public void updatePlayerDisplay(Player player) {
        if (player == null || belowNameObjective == null) {
            return;
        }
        int reputation = getReputation(player.getUniqueId());
        belowNameObjective.getScore(player.getName()).setScore(reputation);
    }

    public void reloadDisplaySettings() {
        initializeBelowNameObjective();
        updateAllOnlinePlayers();
    }

    public void saveAllData() {
        boolean needsSave;

        synchronized (cacheLock) {
            needsSave = dirty;
        }

        if (!needsSave) {
            return;
        }

        boolean saved = storage.saveData();

        synchronized (cacheLock) {
            if (saved) {
                dirty = false;
            }
        }
    }

    public StorageBackupResult createBackup() {
        saveAllData();
        File backupDirectory = new File(plugin.getDataFolder(), "backups");
        return storage.createBackup(backupDirectory);
    }

    public StorageCheckResult checkStorage() {
        return storage.checkStorage();
    }

    public String getStorageBackendName() {
        return storage.getBackendName();
    }

    public boolean isStorageAvailable() {
        return storage.isAvailable();
    }

    public int getReputationCacheSize() {
        synchronized (cacheLock) {
            return reputationCache.size();
        }
    }

    public int getVoteCacheSize() {
        synchronized (cacheLock) {
            return voteCache.size();
        }
    }

    public boolean hasPendingChanges() {
        synchronized (cacheLock) {
            return dirty;
        }
    }

    public void shutdown() {
        saveAllData();
        storage.close();
    }

    private ReputationDataSource createStorage(CloverRepPlugin plugin) {
        if ("mysql".equals(plugin.getConfigManager().getStorageType())) {
            MySQLReputationStorage mysqlStorage = new MySQLReputationStorage(plugin);
            if (mysqlStorage.isAvailable()) {
                plugin.getLogger().info("Using MySQL storage.");
                return mysqlStorage;
            }
        }

        plugin.getLogger().info("Using local YAML storage.");
        return new YamlReputationStorage(plugin);
    }

    private Set<UUID> getOrLoadVotes(UUID voterId) {
        synchronized (cacheLock) {
            Set<UUID> cached = voteCache.get(voterId);
            if (cached != null) {
                return cached;
            }
        }

        Set<UUID> loaded = new HashSet<>(storage.getVoters(voterId));

        synchronized (cacheLock) {
            Set<UUID> existing = voteCache.get(voterId);
            if (existing != null) {
                return existing;
            }
            voteCache.put(voterId, loaded);
            return loaded;
        }
    }

    private UUID resolveUuidByName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        String key = normalizeName(playerName);
        synchronized (cacheLock) {
            UUID cached = nameToUuidCache.get(key);
            if (cached != null) {
                return cached;
            }
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
            cacheIdentity(online.getUniqueId(), online.getName());
            storage.upsertPlayerIdentity(online.getUniqueId(), online.getName());
            return online.getUniqueId();
        }

        UUID stored = storage.findUuidByName(playerName);
        if (stored != null) {
            String storedName = storage.findNameByUuid(stored);
            if (storedName == null || storedName.isBlank()) {
                storedName = playerName;
            }
            cacheIdentity(stored, storedName);
            return stored;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        UUID resolved = offlinePlayer.getUniqueId();
        if (resolved != null) {
            String resolvedName = offlinePlayer.getName() == null || offlinePlayer.getName().isBlank() ? playerName : offlinePlayer.getName();
            cacheIdentity(resolved, resolvedName);
            storage.upsertPlayerIdentity(resolved, resolvedName);
            return resolved;
        }

        return null;
    }

    private String resolveNameByUuid(UUID playerId) {
        if (playerId == null) {
            return "";
        }

        synchronized (cacheLock) {
            String cached = uuidToNameCache.get(playerId);
            if (cached != null) {
                return cached;
            }
        }

        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            cacheIdentity(playerId, online.getName());
            storage.upsertPlayerIdentity(playerId, online.getName());
            return online.getName();
        }

        String stored = storage.findNameByUuid(playerId);
        if (stored != null && !stored.isBlank()) {
            cacheIdentity(playerId, stored);
            return stored;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
        String name = offlinePlayer.getName();
        if (name != null && !name.isBlank()) {
            cacheIdentity(playerId, name);
            storage.upsertPlayerIdentity(playerId, name);
            return name;
        }

        return "";
    }

    private boolean cacheIdentity(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) {
            return false;
        }

        String normalized = normalizeName(playerName);
        synchronized (cacheLock) {
            boolean changed = !playerId.equals(nameToUuidCache.get(normalized))
                    || !playerName.equals(uuidToNameCache.get(playerId));
            nameToUuidCache.put(normalized, playerId);
            uuidToNameCache.put(playerId, playerName);
            return changed;
        }
    }

    private void initializeBelowNameObjective() {
        if (scoreboard == null) {
            belowNameObjective = null;
            return;
        }

        if (belowNameObjective != null) {
            Objective currentBelow = scoreboard.getObjective(DisplaySlot.BELOW_NAME);
            if (currentBelow != null && currentBelow.getName().equals(belowNameObjective.getName())) {
                scoreboard.clearSlot(DisplaySlot.BELOW_NAME);
            }
        }

        belowNameObjective = null;

        if (!plugin.getConfigManager().isBelowNameEnabled()) {
            return;
        }

        String objectiveId = sanitizeObjectiveId(plugin.getConfigManager().getBelowNameObjectiveId());
        Objective objective = scoreboard.getObjective(objectiveId);
        if (objective == null) {
            objective = scoreboard.registerNewObjective(objectiveId, Criteria.DUMMY, ColorUtil.toComponent(plugin.getConfigManager().getBelowNameTitle()));
        } else {
            objective.displayName(ColorUtil.toComponent(plugin.getConfigManager().getBelowNameTitle()));
        }

        Objective currentBelow = scoreboard.getObjective(DisplaySlot.BELOW_NAME);
        if (currentBelow == null || currentBelow.getName().equals(objective.getName()) || plugin.getConfigManager().isBelowNameForceSlot()) {
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
            belowNameObjective = objective;
            return;
        }

        plugin.getLogger().warning("Below-name slot is already used by another objective. Set display.below-name.force-slot=true to override.");
    }

    private String sanitizeObjectiveId(String input) {
        String source = input == null ? "cloverrep" : input;
        String sanitized = source.replaceAll("[^A-Za-z0-9_]", "").toLowerCase(Locale.ROOT);
        if (sanitized.isEmpty()) {
            sanitized = "cloverrep";
        }
        if (sanitized.length() > 16) {
            sanitized = sanitized.substring(0, 16);
        }
        return sanitized;
    }

    private String normalizeName(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
