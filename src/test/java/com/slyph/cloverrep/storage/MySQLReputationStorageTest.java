package com.slyph.cloverrep.storage;

import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.config.ConfigManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "cloverrep.test.mysql.url", matches = "jdbc:mysql:.+")
class MySQLReputationStorageTest {
    @TempDir Path directory;
    private MySQLReputationStorage storage;
    private CloverRepPlugin plugin;
    private String prefix;
    private final UUID voter = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        URI uri = URI.create(System.getProperty("cloverrep.test.mysql.url").substring(5));
        ConfigManager config = mock(ConfigManager.class);
        prefix = "test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_";
        when(config.getMySQLHost()).thenReturn(uri.getHost());
        when(config.getMySQLPort()).thenReturn(uri.getPort() == -1 ? 3306 : uri.getPort());
        when(config.getMySQLDatabase()).thenReturn(uri.getPath().substring(1));
        when(config.getMySQLUser()).thenReturn(System.getProperty("cloverrep.test.mysql.user"));
        when(config.getMySQLPassword()).thenReturn(System.getProperty("cloverrep.test.mysql.password"));
        when(config.getMySQLTablePrefix()).thenReturn(prefix);
        when(config.getMySQLSslMode()).thenReturn("PREFERRED");
        when(config.getMySQLAllowPublicKeyRetrieval()).thenReturn(true);
        when(config.getMySQLConnectTimeoutMillis()).thenReturn(5000);
        when(config.getMySQLSocketTimeoutMillis()).thenReturn(5000);
        when(config.getMySQLPoolMaximumSize()).thenReturn(4);
        when(config.getMySQLPoolMinimumIdle()).thenReturn(1);
        when(config.getMySQLPoolConnectionTimeoutMillis()).thenReturn(5000L);
        when(config.getMySQLPoolIdleTimeoutMillis()).thenReturn(300000L);
        when(config.getMySQLPoolMaxLifetimeMillis()).thenReturn(1800000L);
        plugin = mock(CloverRepPlugin.class);
        when(plugin.getConfigManager()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        storage = new MySQLReputationStorage(plugin);
        assertTrue(storage.isAvailable(), "Test database must be available; failures must not silently skip integration tests.");
        storage.upsertPlayerIdentity(voter, "Voter");
        storage.upsertPlayerIdentity(target, "Target");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (storage != null) storage.close();
        try (Connection connection = DriverManager.getConnection(System.getProperty("cloverrep.test.mysql.url"),
                System.getProperty("cloverrep.test.mysql.user"), System.getProperty("cloverrep.test.mysql.password"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + "voters");
            statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + "players");
        }
    }

    @Test
    void simultaneousDuplicateVotesChangeTheScoreExactlyOnce() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> votes = new ArrayList<>();
            for (int index = 0; index < 16; index++) votes.add(() -> storage.applyVote(voter, target, 1));
            int applied = 0;
            for (var vote : workers.invokeAll(votes)) if (vote.get() != null) applied++;
            assertEquals(1, applied);
        }
        assertEquals(1, storage.getReputation(target));
        assertEquals(1, storage.checkStorage().votesCount());
    }

    @Test
    void differentVotersDoNotLoseConcurrentIncrements() throws Exception {
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> votes = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                UUID voterId = UUID.randomUUID();
                votes.add(() -> storage.applyVote(voterId, target, 1));
            }
            for (var vote : workers.invokeAll(votes)) assertNotNull(vote.get());
        }
        assertEquals(24, storage.getReputation(target));
        assertEquals(24, storage.checkStorage().votesCount());
        assertEquals("Target", storage.findNameByUuid(target));
    }

    @Test
    void bypassUpdatesAreTransactionalAndKeepTheIdentity() {
        assertEquals(-1, storage.adjustReputation(target, -1));
        assertEquals(-2, storage.adjustReputation(target, -1));
        assertEquals("Target", storage.findNameByUuid(target));
        assertEquals(target, storage.findUuidByName("tArGeT"));
        assertEquals(0, storage.checkStorage().votesCount());
    }

    @Test
    void existingCaseSensitiveTablesKeepCaseInsensitiveNameLookups() throws Exception {
        try (Connection connection = DriverManager.getConnection(System.getProperty("cloverrep.test.mysql.url"),
                System.getProperty("cloverrep.test.mysql.user"), System.getProperty("cloverrep.test.mysql.password"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + prefix + "players MODIFY last_name VARCHAR(16) COLLATE utf8mb4_bin NOT NULL");
        }
        assertEquals(target, storage.findUuidByName("tArGeT"));
    }

    @Test
    void overflowRollsBackBothVoteAndReputation() {
        storage.setReputation(target, Integer.MAX_VALUE);
        assertThrows(StorageException.class, () -> storage.applyVote(voter, target, 1));
        assertEquals(Integer.MAX_VALUE, storage.getReputation(target));
        assertFalse(storage.hasVoted(voter, target));
    }

    @Test
    void closedPoolReportsFailureInsteadOfAZeroScore() {
        storage.close();
        assertThrows(StorageException.class, () -> storage.getReputation(target));
    }

    @Test
    void legacyTablesWithKnownUuidsMigrateWithoutLosingScoresOrVotes() throws Exception {
        storage.close();
        try (Connection connection = DriverManager.getConnection(System.getProperty("cloverrep.test.mysql.url"),
                System.getProperty("cloverrep.test.mysql.user"), System.getProperty("cloverrep.test.mysql.password"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE " + prefix + "voters");
            statement.executeUpdate("DROP TABLE " + prefix + "players");
            statement.executeUpdate("CREATE TABLE " + prefix + "players (player_name VARCHAR(16) PRIMARY KEY, "
                    + "player_uuid VARCHAR(36), last_name VARCHAR(16), reputation INT NOT NULL)");
            statement.executeUpdate("CREATE TABLE " + prefix + "voters (voter_name VARCHAR(16), target_name VARCHAR(16), "
                    + "voter_uuid VARCHAR(36), target_uuid VARCHAR(36), PRIMARY KEY(voter_name, target_name))");
            try (var player = connection.prepareStatement("INSERT INTO " + prefix + "players VALUES (?, ?, ?, ?)");
                 var vote = connection.prepareStatement("INSERT INTO " + prefix + "voters VALUES (?, ?, ?, ?)")) {
                player.setString(1, "OldTarget");
                player.setString(2, target.toString());
                player.setString(3, "OldTarget");
                player.setInt(4, 12);
                player.executeUpdate();
                vote.setString(1, "Voter");
                vote.setString(2, "OldTarget");
                vote.setString(3, voter.toString());
                vote.setString(4, target.toString());
                vote.executeUpdate();
            }
        }
        storage = new MySQLReputationStorage(plugin);
        assertTrue(storage.isAvailable());
        assertEquals(12, storage.getReputation(target));
        assertEquals("OldTarget", storage.findNameByUuid(target));
        assertTrue(storage.hasVoted(voter, target));
        assertNull(storage.applyVote(voter, target, 1));
    }

    @Test
    void backupHasMatchingVotesAndScoreAndDistinctNames() {
        assertEquals(1, storage.applyVote(voter, target, 1));
        StorageBackupResult first = storage.createBackup(directory.toFile());
        StorageBackupResult second = storage.createBackup(directory.toFile());
        assertTrue(first.success(), first.errorMessage());
        assertTrue(second.success(), second.errorMessage());
        assertNotEquals(first.fileName(), second.fileName());
        YamlConfiguration snapshot = YamlConfiguration.loadConfiguration(directory.resolve(first.fileName()).toFile());
        assertEquals(1, snapshot.getInt("players." + target + ".reputation"));
        assertEquals(1, snapshot.getConfigurationSection("votes").getKeys(false).size());
    }
}
