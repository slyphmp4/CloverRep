package com.slyph.cloverrep.storage;

import com.slyph.cloverrep.CloverRepPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class YamlReputationStorageTest {
    @TempDir Path directory;
    private CloverRepPlugin plugin;
    private YamlReputationStorage storage;
    private final UUID voter = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(CloverRepPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        Files.writeString(directory.resolve("reputation.yml"), "meta:\n  data-version: 3\nplayers: {}\nnames: {}\nvotes: {}\n");
        storage = new YamlReputationStorage(plugin);
    }

    @Test
    void voteAndDuplicateRestrictionSurviveRestart() {
        storage.upsertPlayerIdentity(target, "Target");
        assertEquals(1, storage.applyVote(voter, target, 1));
        assertNull(storage.applyVote(voter, target, -1));
        assertTrue(storage.saveData());
        YamlReputationStorage restarted = new YamlReputationStorage(plugin);
        assertEquals(1, restarted.getReputation(target));
        assertTrue(restarted.hasVoted(voter, target));
        assertEquals(target, restarted.findUuidByName("target"));
    }

    @Test
    void backupIncludesUnsavedChangesAndDoesNotOverwriteAnotherBackup() {
        assertEquals(-1, storage.applyVote(voter, target, -1));
        StorageBackupResult first = storage.createBackup(directory.resolve("backups").toFile());
        StorageBackupResult second = storage.createBackup(directory.resolve("backups").toFile());
        assertTrue(first.success());
        assertTrue(second.success());
        assertNotEquals(first.fileName(), second.fileName());
        YamlConfiguration snapshot = YamlConfiguration.loadConfiguration(directory.resolve("backups").resolve(first.fileName()).toFile());
        assertEquals(-1, snapshot.getInt("players." + target + ".reputation"));
        assertEquals(target.toString(), snapshot.getStringList("votes." + voter).getFirst());
    }

    @Test
    void overflowDoesNotRecordAVoteOrWrapTheScore() {
        storage.setReputation(target, Integer.MAX_VALUE);
        assertNull(storage.applyVote(voter, target, 1));
        assertFalse(storage.hasVoted(voter, target));
        assertEquals(Integer.MAX_VALUE, storage.getReputation(target));
        assertNull(storage.adjustReputation(target, 1));
    }

    @Test
    void renameRemovesStaleNameIndex() {
        storage.upsertPlayerIdentity(target, "OldName");
        storage.upsertPlayerIdentity(target, "NewName");
        assertNull(storage.findUuidByName("OldName"));
        assertEquals(target, storage.findUuidByName("newname"));
    }

    @Test
    void corruptYamlIsPreservedInsteadOfBeingReplacedWithEmptyData() throws Exception {
        String corrupt = "players: [not: valid\n";
        Files.writeString(directory.resolve("reputation.yml"), corrupt);
        assertThrows(StorageException.class, () -> new YamlReputationStorage(plugin));
        assertEquals(corrupt, Files.readString(directory.resolve("reputation.yml")));
    }

    @Test
    void emptyMigrationCreatesNormalizedSectionsAndPreservesOriginal() throws Exception {
        Files.writeString(directory.resolve("reputation.yml"), "meta:\n  data-version: 1\n");
        storage = new YamlReputationStorage(plugin);
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(directory.resolve("reputation.yml").toFile());
        assertTrue(migrated.isConfigurationSection("players"));
        assertTrue(migrated.isConfigurationSection("names"));
        assertTrue(migrated.isConfigurationSection("votes"));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.filter(path -> path.getFileName().toString().startsWith("reputation-pre-migration-")).count());
        }
    }

    @Test
    void selfVotesAndInvalidAmountsAreRejected() {
        assertNull(storage.applyVote(target, target, 1));
        assertNull(storage.applyVote(voter, target, 20));
        assertEquals(0, storage.getReputation(target));
    }

    @Test
    void legacyUuidDataKeepsScoresIdentitiesAndVoteRestrictions() throws Exception {
        Files.writeString(directory.resolve("reputation.yml"), "meta:\n  data-version: 1\nplayers:\n  "
                + voter + ":\n    name: Voter\n    reputation: 0\n    voters: [" + target + "]\n  "
                + target + ":\n    name: Target\n    reputation: 12\n");
        storage = new YamlReputationStorage(plugin);
        assertEquals(12, storage.getReputation(target));
        assertEquals(target, storage.findUuidByName("target"));
        assertTrue(storage.hasVoted(voter, target));
        assertNull(storage.applyVote(voter, target, -1));
    }
}
