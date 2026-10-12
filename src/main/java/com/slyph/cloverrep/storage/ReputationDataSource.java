package com.slyph.cloverrep.storage;

import java.io.File;
import java.util.Set;
import java.util.UUID;

public interface ReputationDataSource {
    int getReputation(UUID playerId);

    void setReputation(UUID playerId, int reputation);

    boolean hasVoted(UUID voterId, UUID targetId);

    void addVoter(UUID voterId, UUID targetId);

    Set<UUID> getVoters(UUID voterId);

    void upsertPlayerIdentity(UUID playerId, String playerName);

    UUID findUuidByName(String playerName);

    String findNameByUuid(UUID playerId);

    // Implementations must apply a vote and its score change atomically.
    Integer applyVote(UUID voterId, UUID targetId, int amount);

    Integer adjustReputation(UUID playerId, int amount);

    String getBackendName();

    boolean saveData();

    default boolean isAvailable() {
        return true;
    }

    default StorageCheckResult checkStorage() {
        return StorageCheckResult.unsupported("Check operation is not supported for this storage.");
    }

    default StorageBackupResult createBackup(File backupDirectory) {
        return StorageBackupResult.failure("Backup operation is not supported for this storage.");
    }

    default void close() {
    }
}
