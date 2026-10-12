package com.slyph.cloverrep.managers;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.storage.MySQLReputationStorage;
import com.slyph.cloverrep.storage.ReputationDataSource;
import com.slyph.cloverrep.storage.StorageBackupResult;
import com.slyph.cloverrep.storage.StorageCheckResult;
import com.slyph.cloverrep.storage.StorageTaskQueue;
import com.slyph.cloverrep.storage.YamlReputationStorage;
import com.slyph.cloverrep.utils.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class ReputationManager {
    public enum VoteChangeStatus { APPLIED, ALREADY_VOTED, FAILED }
    public record VoteResult(VoteChangeStatus status, int reputation) { }
    private record VoteKey(UUID voterId, UUID targetId) { }

    private final CloverRepPlugin plugin;
    private final ReputationDataSource storage;
    private final Scoreboard scoreboard;
    private final StorageTaskQueue queue;
    private final Cache<UUID, Integer> reputationCache = newCache();
    private final Cache<UUID, Boolean> freshReputations = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(30)).build();
    private final Cache<VoteKey, Boolean> voteCache = newCache();
    private final Cache<String, UUID> nameToUuidCache = newCache();
    private final Cache<UUID, String> uuidToNameCache = newCache();
    private final Cache<UUID, String> persistedNames = newCache();
    private final Cache<String, Boolean> lookupCooldown = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofSeconds(30)).build();
    private final Map<UUID, CompletableFuture<Integer>> pendingReputations = new ConcurrentHashMap<>();
    private CompletableFuture<Boolean> pendingSave;
    private Objective belowNameObjective;
    // Only the serialized storage queue changes this flag.
    private volatile boolean dirty;

    public ReputationManager(CloverRepPlugin plugin) {
        this(plugin, createStorage(plugin), Bukkit.getScoreboardManager() == null
                ? null : Bukkit.getScoreboardManager().getMainScoreboard());
    }

    ReputationManager(CloverRepPlugin plugin, ReputationDataSource storage, Scoreboard scoreboard) {
        this.plugin = plugin;
        this.storage = storage;
        this.scoreboard = scoreboard;
        this.queue = new StorageTaskQueue(plugin.getLogger());
        initializeBelowNameObjective();
    }

    private static <K, V> Cache<K, V> newCache() {
        return Caffeine.newBuilder().maximumSize(10_000).expireAfterAccess(Duration.ofMinutes(15)).build();
    }

    /** Placeholder reads are cache-only; a cold lookup schedules one background load. */
    public int getReputation(UUID playerId) {
        if (playerId == null) return 0;
        Integer cached = reputationCache.getIfPresent(playerId);
        if (cached != null) {
            if (freshReputations.getIfPresent(playerId) == null) loadReputation(playerId);
            return cached;
        }
        loadReputation(playerId);
        return 0;
    }

    public int getReputation(Player player) {
        return player == null ? 0 : getReputation(player.getUniqueId());
    }

    public int getReputation(String playerName) {
        return getReputation(findUuidByName(playerName));
    }

    public UUID findUuidByName(String playerName) {
        if (playerName == null || !playerName.matches("[A-Za-z0-9_]{1,16}")) return null;
        String key = normalizeName(playerName);
        UUID cached = nameToUuidCache.getIfPresent(key);
        if (cached != null) return cached;
        if (lookupCooldown.asMap().putIfAbsent("name:" + key, true) == null) {
            observe(queue.submit(() -> {
                UUID stored = storage.findUuidByName(playerName);
                if (stored != null) {
                    cacheIdentity(stored, storage.findNameByUuid(stored));
                    cacheReputation(stored, storage.getReputation(stored));
                }
                return stored;
            }));
        }
        return null;
    }

    public String findNameByUuid(UUID playerId) {
        if (playerId == null) return "";
        String cached = uuidToNameCache.getIfPresent(playerId);
        if (cached != null) return cached;
        if (lookupCooldown.asMap().putIfAbsent("uuid:" + playerId, true) == null) {
            observe(queue.submit(() -> {
                String name = storage.findNameByUuid(playerId);
                cacheIdentity(playerId, name);
                return name;
            }));
        }
        return "";
    }

    public void registerPlayerIdentity(OfflinePlayer player) {
        if (player == null || player.getName() == null) return;
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        cacheIdentity(playerId, playerName);
        observe(queue.submit(() -> {
            persistIdentity(playerId, playerName);
            return null;
        }));
    }

    /** Called on join so online players' placeholders become warm immediately. */
    public CompletableFuture<Integer> loadPlayer(Player player) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        cacheIdentity(playerId, playerName);
        return observe(queue.submit(() -> {
            persistIdentity(playerId, playerName);
            int value = storage.getReputation(playerId);
            cacheReputation(playerId, value);
            return value;
        }));
    }

    private void loadReputation(UUID playerId) {
        String key = "rep:" + playerId;
        if (lookupCooldown.getIfPresent(key) != null) return;
        CompletableFuture<Integer> marker = new CompletableFuture<>();
        if (pendingReputations.putIfAbsent(playerId, marker) != null) return;
        observe(queue.submit(() -> {
            int loaded = storage.getReputation(playerId);
            cacheReputation(playerId, loaded);
            return loaded;
        })).whenComplete((value, error) -> {
            if (error == null) {
                marker.complete(value);
            } else {
                lookupCooldown.put(key, true);
                marker.completeExceptionally(error);
            }
            pendingReputations.remove(playerId, marker);
        });
    }

    public CompletableFuture<VoteResult> addReputation(Player voter, Player target, int amount, boolean bypassVoteLimit) {
        if (voter == null || target == null || voter.getUniqueId().equals(target.getUniqueId())
                || (amount != 1 && amount != -1)) {
            return CompletableFuture.completedFuture(new VoteResult(VoteChangeStatus.FAILED, 0));
        }
        UUID voterId = voter.getUniqueId();
        UUID targetId = target.getUniqueId();
        String voterName = voter.getName();
        String targetName = target.getName();
        return observe(queue.submit(() -> {
            persistIdentity(voterId, voterName);
            persistIdentity(targetId, targetName);
            VoteKey key = new VoteKey(voterId, targetId);
            Integer updated = bypassVoteLimit ? storage.adjustReputation(targetId, amount)
                    : storage.applyVote(voterId, targetId, amount);
            if (updated == null) {
                if (!bypassVoteLimit && storage.hasVoted(voterId, targetId)) {
                    voteCache.put(key, true);
                    return new VoteResult(VoteChangeStatus.ALREADY_VOTED, getCachedReputation(targetId));
                }
                return new VoteResult(VoteChangeStatus.FAILED, getCachedReputation(targetId));
            }
            cacheReputation(targetId, updated);
            if (!bypassVoteLimit) voteCache.put(key, true);
            dirty = true;
            return new VoteResult(VoteChangeStatus.APPLIED, updated);
        })).exceptionally(error -> new VoteResult(VoteChangeStatus.FAILED, getCachedReputation(targetId)));
    }

    private int getCachedReputation(UUID playerId) {
        Integer cached = reputationCache.getIfPresent(playerId);
        return cached == null ? 0 : cached;
    }

    private void cacheReputation(UUID playerId, int value) {
        reputationCache.put(playerId, value);
        freshReputations.put(playerId, true);
    }

    private void persistIdentity(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) return;
        if (!playerName.equals(persistedNames.getIfPresent(playerId))) {
            storage.upsertPlayerIdentity(playerId, playerName);
            persistedNames.put(playerId, playerName);
            dirty = true;
        }
        cacheIdentity(playerId, playerName);
    }

    private void cacheIdentity(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) return;
        String previousName = uuidToNameCache.getIfPresent(playerId);
        if (previousName != null && !previousName.equalsIgnoreCase(playerName)) {
            nameToUuidCache.asMap().remove(normalizeName(previousName), playerId);
        }
        uuidToNameCache.put(playerId, playerName);
        nameToUuidCache.put(normalizeName(playerName), playerId);
    }

    public void updateAllOnlinePlayers() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            loadPlayer(online).thenRun(() -> plugin.runOnMainThread(() -> updatePlayerDisplay(online)));
        }
    }

    /** Scoreboard access stays on the server thread. */
    public void updatePlayerDisplay(Player player) {
        if (player != null && player.isOnline() && belowNameObjective != null) {
            belowNameObjective.getScore(player.getName()).setScore(getReputation(player.getUniqueId()));
        }
    }

    public void reloadDisplaySettings() {
        initializeBelowNameObjective();
        updateAllOnlinePlayers();
    }

    public synchronized CompletableFuture<Boolean> saveAllData() {
        if (pendingSave == null || pendingSave.isDone()) {
            pendingSave = observe(queue.submit(this::saveNow));
        }
        return pendingSave;
    }

    private boolean saveNow() {
        if (!dirty) return true;
        if (!storage.saveData()) return false;
        dirty = false;
        return true;
    }

    public CompletableFuture<StorageBackupResult> createBackup() {
        File backupDirectory = new File(plugin.getDataFolder(), "backups");
        return observe(queue.submit(() -> {
            if (!saveNow()) return StorageBackupResult.failure("Could not flush pending data; backup was not created.");
            return storage.createBackup(backupDirectory);
        })).exceptionally(error -> StorageBackupResult.failure(error.getMessage()));
    }

    public CompletableFuture<StorageCheckResult> checkStorage() {
        return observe(queue.submit(storage::checkStorage))
                .exceptionally(error -> StorageCheckResult.failure(error.getMessage()));
    }

    public String getStorageBackendName() { return storage.getBackendName(); }
    public boolean isStorageAvailable() { return storage.isAvailable(); }
    public int getReputationCacheSize() { return (int) reputationCache.estimatedSize(); }
    public int getVoteCacheSize() { return (int) voteCache.estimatedSize(); }
    public boolean hasPendingChanges() { return dirty; }

    public void shutdown() {
        queue.close(() -> {
            try {
                if (!saveNow()) plugin.getLogger().severe("Pending reputation data could not be saved during shutdown.");
            } finally {
                storage.close();
            }
        });
        clearBelowNameSlot();
    }

    private <T> CompletableFuture<T> observe(CompletableFuture<T> future) {
        return future.whenComplete((value, error) -> {
            if (error != null) plugin.getLogger().warning("Storage operation failed: " + error.getMessage());
        });
    }

    private static ReputationDataSource createStorage(CloverRepPlugin plugin) {
        if ("mysql".equals(plugin.getConfigManager().getStorageType())) {
            MySQLReputationStorage mysql = new MySQLReputationStorage(plugin);
            if (mysql.isAvailable()) {
                plugin.getLogger().info("Using MySQL storage.");
                return mysql;
            }
            mysql.close();
        }
        plugin.getLogger().info("Using local YAML storage.");
        return new YamlReputationStorage(plugin);
    }

    private void clearBelowNameSlot() {
        if (scoreboard != null && belowNameObjective != null) {
            Objective current = scoreboard.getObjective(DisplaySlot.BELOW_NAME);
            if (current != null && current.getName().equals(belowNameObjective.getName())) {
                scoreboard.clearSlot(DisplaySlot.BELOW_NAME);
            }
        }
        belowNameObjective = null;
    }

    private void initializeBelowNameObjective() {
        clearBelowNameSlot();
        if (scoreboard == null || !plugin.getConfigManager().isBelowNameEnabled()) return;
        String objectiveId = sanitizeObjectiveId(plugin.getConfigManager().getBelowNameObjectiveId());
        Objective objective = scoreboard.getObjective(objectiveId);
        if (objective == null) {
            objective = scoreboard.registerNewObjective(objectiveId, Criteria.DUMMY,
                    ColorUtil.toComponent(plugin.getConfigManager().getBelowNameTitle()));
        } else if (!objective.getTrackedCriteria().equals(Criteria.DUMMY)) {
            plugin.getLogger().warning("Configured below-name objective uses another criterion; choose a different objective-id.");
            return;
        } else {
            objective.displayName(ColorUtil.toComponent(plugin.getConfigManager().getBelowNameTitle()));
        }
        Objective current = scoreboard.getObjective(DisplaySlot.BELOW_NAME);
        if (current == null || current.getName().equals(objective.getName()) || plugin.getConfigManager().isBelowNameForceSlot()) {
            objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
            belowNameObjective = objective;
        } else {
            plugin.getLogger().warning("Below-name slot is occupied. Set display.below-name.force-slot=true to override.");
        }
    }

    private String sanitizeObjectiveId(String input) {
        String sanitized = (input == null ? "cloverrep" : input).replaceAll("[^A-Za-z0-9_]", "").toLowerCase(Locale.ROOT);
        return sanitized.isEmpty() ? "cloverrep" : sanitized.substring(0, Math.min(16, sanitized.length()));
    }

    private String normalizeName(String value) { return value.toLowerCase(Locale.ROOT); }
}
