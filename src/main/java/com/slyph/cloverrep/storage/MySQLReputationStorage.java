package com.slyph.cloverrep.storage;

import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class MySQLReputationStorage implements ReputationDataSource {
    private static final int CONNECT_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 350L;
    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CloverRepPlugin plugin;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int poolMaximumSize;
    private final int poolMinimumIdle;
    private final long poolConnectionTimeout;
    private final long poolIdleTimeout;
    private final long poolMaxLifetime;
    private final String tablePlayers;
    private final String tableVoters;

    private final String selectReputationSql;
    private final String upsertReputationSql;
    private final String incrementReputationSql;
    private final String hasVotedSql;
    private final String insertVoteSql;
    private final String selectVotersSql;
    private final String upsertIdentitySql;
    private final String selectUuidByNameSql;
    private final String selectUuidByNameCaseInsensitiveSql;
    private final String selectNameByUuidSql;
    private final String countPlayersSql;
    private final String countVotesSql;
    private final String selectAllPlayersSql;
    private final String selectAllVotesSql;

    private volatile HikariDataSource dataSource;
    private volatile boolean available;

    public MySQLReputationStorage(CloverRepPlugin plugin) {
        this.plugin = plugin;

        ConfigManager config = plugin.getConfigManager();
        String prefix = sanitizePrefix(config.getMySQLTablePrefix());

        this.tablePlayers = prefix + "players";
        this.tableVoters = prefix + "voters";

        this.jdbcUrl = "jdbc:mysql://" + config.getMySQLHost() + ":" + config.getMySQLPort() + "/" + config.getMySQLDatabase()
                + "?sslMode=" + config.getMySQLSslMode() + "&allowPublicKeyRetrieval=" + config.getMySQLAllowPublicKeyRetrieval()
                + "&characterEncoding=UTF-8&connectionTimeZone=UTC&tcpKeepAlive=true"
                + "&connectTimeout=" + config.getMySQLConnectTimeoutMillis() + "&socketTimeout=" + config.getMySQLSocketTimeoutMillis();
        this.dbUser = config.getMySQLUser();
        this.dbPassword = config.getMySQLPassword();

        this.poolMaximumSize = config.getMySQLPoolMaximumSize();
        this.poolMinimumIdle = config.getMySQLPoolMinimumIdle();
        this.poolConnectionTimeout = config.getMySQLPoolConnectionTimeoutMillis();
        this.poolIdleTimeout = config.getMySQLPoolIdleTimeoutMillis();
        this.poolMaxLifetime = config.getMySQLPoolMaxLifetimeMillis();

        this.selectReputationSql = "SELECT reputation FROM " + tablePlayers + " WHERE player_uuid = ?";
        this.upsertReputationSql = "INSERT INTO " + tablePlayers + " (player_uuid, last_name, reputation) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE reputation = ?, last_name = ?, updated_at = CURRENT_TIMESTAMP";
        this.incrementReputationSql = "INSERT INTO " + tablePlayers + " (player_uuid, last_name, reputation) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE reputation = reputation + ?, updated_at = CURRENT_TIMESTAMP";
        this.hasVotedSql = "SELECT 1 FROM " + tableVoters + " WHERE voter_uuid = ? AND target_uuid = ? LIMIT 1";
        this.insertVoteSql = "INSERT INTO " + tableVoters + " (voter_uuid, target_uuid) VALUES (?, ?)";
        this.selectVotersSql = "SELECT target_uuid FROM " + tableVoters + " WHERE voter_uuid = ?";
        this.upsertIdentitySql = "INSERT INTO " + tablePlayers + " (player_uuid, last_name, reputation) VALUES (?, ?, 0) "
                + "ON DUPLICATE KEY UPDATE last_name = ?, updated_at = CURRENT_TIMESTAMP";
        this.selectUuidByNameSql = "SELECT player_uuid FROM " + tablePlayers + " WHERE last_name = ? "
                + "ORDER BY updated_at DESC LIMIT 1";
        this.selectUuidByNameCaseInsensitiveSql = "SELECT player_uuid FROM " + tablePlayers + " WHERE LOWER(last_name) = LOWER(?) "
                + "ORDER BY updated_at DESC LIMIT 1";
        this.selectNameByUuidSql = "SELECT last_name FROM " + tablePlayers + " WHERE player_uuid = ? LIMIT 1";
        this.countPlayersSql = "SELECT COUNT(*) FROM " + tablePlayers;
        this.countVotesSql = "SELECT COUNT(*) FROM " + tableVoters;
        this.selectAllPlayersSql = "SELECT player_uuid, last_name, reputation, updated_at FROM " + tablePlayers;
        this.selectAllVotesSql = "SELECT voter_uuid, target_uuid, created_at FROM " + tableVoters;

        this.available = initializePool();

        if (!available) {
            plugin.getLogger().severe("Failed to connect to MySQL. Falling back to local storage.");
        } else {
            plugin.getLogger().info("MySQL tables: " + tablePlayers + ", " + tableVoters);
        }
    }

    @Override
    public int getReputation(UUID playerId) {
        if (playerId == null) {
            return 0;
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(selectReputationSql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    available = true;
                    return result.getInt("reputation");
                }
            }
            available = true;
            return 0;
        } catch (SQLException exception) {
            onSqlError("load reputation for " + playerId, exception);
            throw new StorageException("Could not load player reputation.", exception);
        }
    }

    @Override
    public void setReputation(UUID playerId, int reputation) {
        if (playerId == null) {
            return;
        }

        String playerName = getSafeName(findNameByUuid(playerId), playerId);

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(upsertReputationSql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, playerName);
            statement.setInt(3, reputation);
            statement.setInt(4, reputation);
            statement.setString(5, playerName);
            statement.executeUpdate();
            available = true;
        } catch (SQLException exception) {
            onSqlError("save reputation for " + playerId, exception);
            throw new StorageException("Could not save player reputation.", exception);
        }
    }

    @Override
    public boolean hasVoted(UUID voterId, UUID targetId) {
        if (voterId == null || targetId == null) {
            return false;
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(hasVotedSql)) {
            statement.setString(1, voterId.toString());
            statement.setString(2, targetId.toString());
            try (ResultSet result = statement.executeQuery()) {
                boolean voted = result.next();
                available = true;
                return voted;
            }
        } catch (SQLException exception) {
            onSqlError("check vote between " + voterId + " and " + targetId, exception);
            throw new StorageException("Could not check vote history.", exception);
        }
    }

    @Override
    public void addVoter(UUID voterId, UUID targetId) {
        if (voterId == null || targetId == null) {
            return;
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(insertVoteSql)) {
            statement.setString(1, voterId.toString());
            statement.setString(2, targetId.toString());
            statement.executeUpdate();
            available = true;
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1062) {
                available = true;
                return;
            }
            onSqlError("save vote between " + voterId + " and " + targetId, exception);
            throw new StorageException("Could not save vote history.", exception);
        }
    }

    @Override
    public Set<UUID> getVoters(UUID voterId) {
        Set<UUID> voters = new LinkedHashSet<>();
        if (voterId == null) {
            return voters;
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(selectVotersSql)) {
            statement.setString(1, voterId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID targetId = parseUuid(result.getString("target_uuid"));
                    if (targetId != null) {
                        voters.add(targetId);
                    }
                }
            }
            available = true;
        } catch (SQLException exception) {
            onSqlError("load voted targets for " + voterId, exception);
            throw new StorageException("Could not load vote history.", exception);
        }

        return voters;
    }

    @Override
    public void upsertPlayerIdentity(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) {
            return;
        }

        String safeName = getSafeName(playerName, playerId);

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(upsertIdentitySql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, safeName);
            statement.setString(3, safeName);
            statement.executeUpdate();
            available = true;
        } catch (SQLException exception) {
            onSqlError("update identity for " + playerId, exception);
            throw new StorageException("Could not persist player identity.", exception);
        }
    }

    @Override
    public UUID findUuidByName(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(selectUuidByNameSql)) {
            statement.setString(1, trimPlayerName(playerName));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    available = true;
                    return parseUuid(result.getString("player_uuid"));
                }
            }
            // Preserve lookups on existing tables with a case-sensitive name collation.
            try (PreparedStatement fallback = connection.prepareStatement(selectUuidByNameCaseInsensitiveSql)) {
                fallback.setString(1, trimPlayerName(playerName));
                try (ResultSet result = fallback.executeQuery()) {
                    if (result.next()) {
                        available = true;
                        return parseUuid(result.getString("player_uuid"));
                    }
                }
            }
            available = true;
        } catch (SQLException exception) {
            onSqlError("find UUID by name " + playerName, exception);
            throw new StorageException("Could not resolve player name.", exception);
        }

        return null;
    }

    @Override
    public String findNameByUuid(UUID playerId) {
        if (playerId == null) {
            return "";
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(selectNameByUuidSql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    available = true;
                    return result.getString("last_name");
                }
            }
            available = true;
        } catch (SQLException exception) {
            onSqlError("find name by UUID " + playerId, exception);
            throw new StorageException("Could not resolve player UUID.", exception);
        }

        return "";
    }

    @Override
    public Integer applyVote(UUID voterId, UUID targetId, int amount) {
        if (voterId == null || targetId == null || voterId.equals(targetId) || (amount != 1 && amount != -1)) {
            return null;
        }
        return changeReputation(voterId, targetId, amount);
    }

    @Override
    public Integer adjustReputation(UUID playerId, int amount) {
        return playerId == null ? null : changeReputation(null, playerId, amount);
    }

    private Integer changeReputation(UUID voterId, UUID targetId, int amount) {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (voterId != null) {
                    try (PreparedStatement vote = connection.prepareStatement(insertVoteSql)) {
                        vote.setString(1, voterId.toString());
                        vote.setString(2, targetId.toString());
                        try {
                            vote.executeUpdate();
                        } catch (SQLException exception) {
                            if (exception.getErrorCode() != 1062) {
                                throw exception;
                            }
                            connection.rollback();
                            available = true;
                            return null;
                        }
                    }
                }

                try (PreparedStatement update = connection.prepareStatement(incrementReputationSql)) {
                    update.setString(1, targetId.toString());
                    update.setString(2, getSafeName("", targetId));
                    update.setInt(3, amount);
                    update.setInt(4, amount);
                    update.executeUpdate();
                }

                int updated;
                try (PreparedStatement select = connection.prepareStatement(selectReputationSql)) {
                    select.setString(1, targetId.toString());
                    try (ResultSet result = select.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("The updated player record is missing.");
                        }
                        updated = result.getInt("reputation");
                    }
                }
                connection.commit();
                available = true;
                return updated;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            onSqlError("change reputation for " + targetId, exception);
            throw new StorageException("Could not commit reputation change.", exception);
        }
    }

    @Override
    public String getBackendName() {
        return "mysql";
    }

    @Override
    public boolean saveData() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        HikariDataSource source = dataSource;
        return available && source != null && !source.isClosed();
    }

    @Override
    public StorageCheckResult checkStorage() {
        long start = System.nanoTime();

        try (Connection connection = openConnection();
             Statement pingStatement = connection.createStatement()) {
            pingStatement.execute("SELECT 1");
            long playersCount = queryCount(connection, countPlayersSql);
            long votesCount = queryCount(connection, countVotesSql);
            long latency = (System.nanoTime() - start) / 1_000_000L;
            available = true;
            return StorageCheckResult.success(latency, playersCount, votesCount);
        } catch (SQLException exception) {
            onSqlError("check database health", exception);
            return StorageCheckResult.failure(exception.getMessage());
        }
    }

    @Override
    public StorageBackupResult createBackup(File backupDirectory) {
        if (backupDirectory == null || (!backupDirectory.isDirectory() && !backupDirectory.mkdirs())) {
            return StorageBackupResult.failure("Could not create backup directory.");
        }
        YamlConfiguration snapshot = new YamlConfiguration();
        snapshot.set("meta.storage", "mysql");
        snapshot.set("meta.generated-at", Instant.now().toString());
        snapshot.set("meta.players-table", tablePlayers);
        snapshot.set("meta.votes-table", tableVoters);

        try (Connection connection = openConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(selectAllPlayersSql);
                     ResultSet players = statement.executeQuery()) {
                    while (players.next()) {
                        String id = players.getString("player_uuid");
                        snapshot.set("players." + id + ".name", players.getString("last_name"));
                        snapshot.set("players." + id + ".reputation", players.getInt("reputation"));
                        snapshot.set("players." + id + ".updated-at", String.valueOf(players.getTimestamp("updated_at")));
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(selectAllVotesSql);
                     ResultSet votes = statement.executeQuery()) {
                    int index = 0;
                    while (votes.next()) {
                        snapshot.set("votes." + index + ".voter", votes.getString("voter_uuid"));
                        snapshot.set("votes." + index + ".target", votes.getString("target_uuid"));
                        snapshot.set("votes." + index + ".created-at", String.valueOf(votes.getTimestamp("created_at")));
                        index++;
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
            available = true;
            String timestamp = LocalDateTime.now().format(BACKUP_FORMAT);
            File backupFile = Files.createTempFile(backupDirectory.toPath(), "reputation-mysql-" + timestamp + "-", ".yml").toFile();
            snapshot.save(backupFile);
            return StorageBackupResult.success(backupFile.getName());
        } catch (SQLException exception) {
            onSqlError("create backup", exception);
            return StorageBackupResult.failure(exception.getMessage());
        } catch (IOException exception) {
            return StorageBackupResult.failure(exception.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        HikariDataSource source = dataSource;
        if (source != null) {
            source.close();
        }
        dataSource = null;
        available = false;
    }

    private boolean initializePool() {
        close();

        Exception lastException = null;

        for (int attempt = 1; attempt <= CONNECT_RETRIES; attempt++) {
            HikariDataSource candidate = null;
            try {
                candidate = new HikariDataSource(createHikariConfig());
                try (Connection connection = candidate.getConnection()) {
                    initializeSchema(connection);
                }
                dataSource = candidate;
                available = true;
                return true;
            } catch (Exception exception) {
                if (candidate != null) {
                    candidate.close();
                }
                lastException = exception;
                sleep(attempt * RETRY_DELAY_MS);
            }
        }

        available = false;
        if (lastException != null) {
            plugin.getLogger().severe("MySQL connection attempts failed: " + lastException.getMessage());
        }
        return false;
    }

    private HikariConfig createHikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(dbUser);
        config.setPassword(dbPassword);
        config.setPoolName("CloverRepPool");
        config.setMaximumPoolSize(poolMaximumSize);
        config.setMinimumIdle(Math.min(poolMinimumIdle, poolMaximumSize));
        config.setConnectionTimeout(poolConnectionTimeout);
        config.setIdleTimeout(poolIdleTimeout);
        config.setMaxLifetime(poolMaxLifetime);
        config.setValidationTimeout(Math.min(3000L, poolConnectionTimeout));
        config.setInitializationFailTimeout(-1L);
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        return config;
    }

    private Connection openConnection() throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) {
            throw new SQLException("DataSource is not available.");
        }
        return source.getConnection();
    }

    private void initializeSchema(Connection connection) throws SQLException {
        createTables(connection);
        migrateLegacyPlayersTable(connection);
        migrateLegacyVotersTable(connection);
    }

    private void createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + tablePlayers + " ("
                            + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                            + "last_name VARCHAR(16) NOT NULL,"
                            + "reputation INT NOT NULL DEFAULT 0,"
                            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                            + "INDEX idx_last_name (last_name)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );

            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + tableVoters + " ("
                            + "voter_uuid VARCHAR(36) NOT NULL,"
                            + "target_uuid VARCHAR(36) NOT NULL,"
                            + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                            + "PRIMARY KEY (voter_uuid, target_uuid),"
                            + "INDEX idx_target_uuid (target_uuid)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
        }
    }

    private void migrateLegacyPlayersTable(Connection connection) throws SQLException {
        if (!hasColumn(connection, tablePlayers, "player_name")) {
            return;
        }

        boolean hasPlayerUuid = hasColumn(connection, tablePlayers, "player_uuid");
        boolean hasLastName = hasColumn(connection, tablePlayers, "last_name");

        String tempTable = tablePlayers + "_tmp_uuid_" + System.currentTimeMillis();
        String legacyTable = tablePlayers + "_legacy_" + System.currentTimeMillis();

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE " + tempTable + " ("
                            + "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,"
                            + "last_name VARCHAR(16) NOT NULL,"
                            + "reputation INT NOT NULL DEFAULT 0,"
                            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                            + "INDEX idx_last_name (last_name)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
        }

        String selectSql = buildLegacyPlayersSelectSql(hasPlayerUuid, hasLastName);
        String insertSql = "INSERT INTO " + tempTable + " (player_uuid, last_name, reputation) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE reputation = reputation + ?, "
                + "last_name = ?, updated_at = CURRENT_TIMESTAMP";

        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet result = select.executeQuery();
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            while (result.next()) {
                UUID playerId = null;
                if (hasPlayerUuid) {
                    playerId = parseUuid(result.getString("player_uuid"));
                }

                String legacyName = result.getString("player_name");
                if (playerId == null) {
                    playerId = resolveUuidByName(legacyName);
                }
                if (playerId == null) {
                    continue;
                }

                String storedName = hasLastName ? result.getString("last_name") : legacyName;
                storedName = getSafeName(storedName, playerId);

                insert.setString(1, playerId.toString());
                insert.setString(2, storedName);
                insert.setInt(3, result.getInt("reputation"));
                insert.setInt(4, result.getInt("reputation"));
                insert.setString(5, storedName);
                insert.addBatch();
            }
            insert.executeBatch();
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("RENAME TABLE " + tablePlayers + " TO " + legacyTable + ", " + tempTable + " TO " + tablePlayers);
        }
    }

    private void migrateLegacyVotersTable(Connection connection) throws SQLException {
        if (!hasColumn(connection, tableVoters, "voter_name") && !hasColumn(connection, tableVoters, "target_name")) {
            return;
        }

        boolean hasVoterUuid = hasColumn(connection, tableVoters, "voter_uuid");
        boolean hasTargetUuid = hasColumn(connection, tableVoters, "target_uuid");
        boolean hasVoterName = hasColumn(connection, tableVoters, "voter_name");
        boolean hasTargetName = hasColumn(connection, tableVoters, "target_name");

        String tempTable = tableVoters + "_tmp_uuid_" + System.currentTimeMillis();
        String legacyTable = tableVoters + "_legacy_" + System.currentTimeMillis();

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE " + tempTable + " ("
                            + "voter_uuid VARCHAR(36) NOT NULL,"
                            + "target_uuid VARCHAR(36) NOT NULL,"
                            + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                            + "PRIMARY KEY (voter_uuid, target_uuid),"
                            + "INDEX idx_target_uuid (target_uuid)"
                            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
        }

        String selectSql = buildLegacyVotesSelectSql(hasVoterUuid, hasTargetUuid, hasVoterName, hasTargetName);
        String insertSql = "INSERT IGNORE INTO " + tempTable + " (voter_uuid, target_uuid) VALUES (?, ?)";

        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet result = select.executeQuery();
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            while (result.next()) {
                UUID voterId = hasVoterUuid ? parseUuid(result.getString("voter_uuid")) : null;
                UUID targetId = hasTargetUuid ? parseUuid(result.getString("target_uuid")) : null;

                if (voterId == null && hasVoterName) {
                    voterId = resolveUuidByName(result.getString("voter_name"));
                }
                if (targetId == null && hasTargetName) {
                    targetId = resolveUuidByName(result.getString("target_name"));
                }

                if (voterId == null || targetId == null) {
                    continue;
                }

                insert.setString(1, voterId.toString());
                insert.setString(2, targetId.toString());
                insert.addBatch();
            }
            insert.executeBatch();
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("RENAME TABLE " + tableVoters + " TO " + legacyTable + ", " + tempTable + " TO " + tableVoters);
        }
    }

    private String buildLegacyPlayersSelectSql(boolean hasPlayerUuid, boolean hasLastName) {
        StringBuilder builder = new StringBuilder("SELECT player_name, reputation");
        if (hasPlayerUuid) {
            builder.append(", player_uuid");
        }
        if (hasLastName) {
            builder.append(", last_name");
        }
        builder.append(" FROM ").append(tablePlayers);
        return builder.toString();
    }

    private String buildLegacyVotesSelectSql(boolean hasVoterUuid, boolean hasTargetUuid, boolean hasVoterName, boolean hasTargetName) {
        StringBuilder builder = new StringBuilder("SELECT ");
        boolean first = true;

        if (hasVoterUuid) {
            builder.append("voter_uuid");
            first = false;
        }
        if (hasTargetUuid) {
            if (!first) {
                builder.append(", ");
            }
            builder.append("target_uuid");
            first = false;
        }
        if (hasVoterName) {
            if (!first) {
                builder.append(", ");
            }
            builder.append("voter_name");
            first = false;
        }
        if (hasTargetName) {
            if (!first) {
                builder.append(", ");
            }
            builder.append("target_name");
        }

        builder.append(" FROM ").append(tableVoters);
        return builder.toString();
    }

    private long queryCount(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (result.next()) {
                return result.getLong(1);
            }
            return 0L;
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            while (columns.next()) {
                if (tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private void onSqlError(String operation, SQLException exception) {
        available = false;
        plugin.getLogger().severe("Failed to " + operation + ": " + exception.getMessage());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
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
        UUID uuid = offlinePlayer.getUniqueId();
        if (uuid != null) {
            return uuid;
        }

        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "clover_";
        }

        String sanitized = prefix.replaceAll("[^A-Za-z0-9_]", "");
        if (sanitized.isEmpty()) {
            return "clover_";
        }

        return sanitized.toLowerCase(Locale.ROOT);
    }

    private String trimPlayerName(String playerName) {
        String trimmed = playerName == null ? "" : playerName.trim();
        if (trimmed.length() > 16) {
            return trimmed.substring(0, 16);
        }
        return trimmed;
    }

    private String getSafeName(String rawName, UUID playerId) {
        String trimmed = trimPlayerName(rawName);
        if (!trimmed.isBlank()) {
            return trimmed;
        }
        String fallback = playerId == null ? "unknown" : playerId.toString().replace("-", "");
        if (fallback.length() > 16) {
            return fallback.substring(0, 16);
        }
        return fallback;
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
