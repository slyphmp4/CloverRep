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
                + "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&tcpKeepAlive=true";
        this.dbUser = config.getMySQLUser();
        this.dbPassword = config.getMySQLPassword();

        this.poolMaximumSize = config.getMySQLPoolMaximumSize();
        this.poolMinimumIdle = config.getMySQLPoolMinimumIdle();
        this.poolConnectionTimeout = config.getMySQLPoolConnectionTimeoutMillis();
        this.poolIdleTimeout = config.getMySQLPoolIdleTimeoutMillis();
        this.poolMaxLifetime = config.getMySQLPoolMaxLifetimeMillis();

        this.selectReputationSql = "SELECT reputation FROM " + tablePlayers + " WHERE player_uuid = ?";
        this.upsertReputationSql = "INSERT INTO " + tablePlayers + " (player_uuid, last_name, reputation) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE reputation = VALUES(reputation), last_name = VALUES(last_name), updated_at = CURRENT_TIMESTAMP";
        this.incrementReputationSql = "INSERT INTO " + tablePlayers + " (player_uuid, last_name, reputation) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE reputation = reputation + VALUES(reputation), last_name = VALUES(last_name), updated_at = CURRENT_TIMESTAMP";
        this.hasVotedSql = "SELECT 1 FROM " + tableVoters + " WHERE voter_uuid = ? AND target_uuid = ? LIMIT 1";
        this.insertVoteSql = "INSERT IGNORE INTO " + tableVoters + " (voter_uuid, target_uuid) VALUES (?, ?)";
        this.selectVotersSql = "SELECT target_uuid FROM " + tableVoters + " WHERE voter_uuid = ?";
        this.upsertIdentitySql = "INSERT INTO " + tablePlayers + " (player_uuid, last_name, reputation) VALUES (?, ?, 0) "
                + "ON DUPLICATE KEY UPDATE last_name = VALUES(last_name), updated_at = CURRENT_TIMESTAMP";
        this.selectUuidByNameSql = "SELECT player_uuid FROM " + tablePlayers + " WHERE LOWER(last_name) = LOWER(?) "
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
                    return result.getInt("reputation");
                }
            }
            available = true;
            return 0;
        } catch (SQLException exception) {
            onSqlError("load reputation for " + playerId, exception);
            return 0;
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
            statement.executeUpdate();
            available = true;
        } catch (SQLException exception) {
            onSqlError("save reputation for " + playerId, exception);
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
            return false;
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
            onSqlError("save vote between " + voterId + " and " + targetId, exception);
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
            statement.executeUpdate();
            available = true;
        } catch (SQLException exception) {
            onSqlError("update identity for " + playerId, exception);
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
            available = true;
        } catch (SQLException exception) {
            onSqlError("find UUID by name " + playerName, exception);
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
        }

        return "";
    }

    @Override
    public Integer applyVote(UUID voterId, UUID targetId, int amount) {
        if (voterId == null || targetId == null) {
            return null;
        }

        String targetName = getSafeName(findNameByUuid(targetId), targetId);

        try (Connection connection = openConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);

                int insertedVote;
                try (PreparedStatement statement = connection.prepareStatement(insertVoteSql)) {
                    statement.setString(1, voterId.toString());
                    statement.setString(2, targetId.toString());
                    insertedVote = statement.executeUpdate();
                }

                if (insertedVote == 0) {
                    connection.rollback();
                    return null;
                }

                try (PreparedStatement statement = connection.prepareStatement(incrementReputationSql)) {
                    statement.setString(1, targetId.toString());
                    statement.setString(2, targetName);
                    statement.setInt(3, amount);
                    statement.executeUpdate();
                }

                int updated;
                try (PreparedStatement statement = connection.prepareStatement(selectReputationSql)) {
                    statement.setString(1, targetId.toString());
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return null;
                        }
                        updated = result.getInt("reputation");
                    }
                }

                connection.commit();
                available = true;
                return updated;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                onSqlError("apply vote between " + voterId + " and " + targetId, exception);
                return null;
            } finally {
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException exception) {
            onSqlError("open transaction for vote", exception);
            return null;
        }
    }

    @Override
    public Integer adjustReputation(UUID playerId, int amount) {
        if (playerId == null) {
            return null;
        }

        String targetName = getSafeName(findNameByUuid(playerId), playerId);

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(incrementReputationSql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, targetName);
            statement.setInt(3, amount);
            statement.executeUpdate();
            available = true;
        } catch (SQLException exception) {
            onSqlError("adjust reputation for " + playerId, exception);
            return null;
        }

        return getReputation(playerId);
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
        if (backupDirectory == null) {
            return StorageBackupResult.failure("Backup directory is not defined.");
        }

        if (!backupDirectory.exists() && !backupDirectory.mkdirs()) {
            return StorageBackupResult.failure("Could not create backup directory.");
        }

        String timestamp = LocalDateTime.now().format(BACKUP_FORMAT);
        File backupFile = new File(backupDirectory, "reputation-mysql-" + timestamp + ".yml");
        YamlConfiguration snapshot = new YamlConfiguration();

        snapshot.set("meta.storage", "mysql");
        snapshot.set("meta.generated-at", Instant.now().toString());
        snapshot.set("meta.players-table", tablePlayers);
        snapshot.set("meta.votes-table", tableVoters);

        try (Connection connection = openConnection();
             PreparedStatement playersStatement = connection.prepareStatement(selectAllPlayersSql);
             PreparedStatement votesStatement = connection.prepareStatement(selectAllVotesSql);
             ResultSet players = playersStatement.executeQuery();
             ResultSet votes = votesStatement.executeQuery()) {
            while (players.next()) {
                String playerUuid = players.getString("player_uuid");
                snapshot.set("players." + playerUuid + ".name", players.getString("last_name"));
                snapshot.set("players." + playerUuid + ".reputation", players.getInt("reputation"));
                snapshot.set("players." + playerUuid + ".updated-at", String.valueOf(players.getTimestamp("updated_at")));
            }

            int index = 0;
            while (votes.next()) {
                snapshot.set("votes." + index + ".voter", votes.getString("voter_uuid"));
                snapshot.set("votes." + index + ".target", votes.getString("target_uuid"));
                snapshot.set("votes." + index + ".created-at", String.valueOf(votes.getTimestamp("created_at")));
                index++;
            }

            snapshot.save(backupFile);
            available = true;
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
        config.setValidationTimeout(3000L);
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
                + "ON DUPLICATE KEY UPDATE reputation = reputation + VALUES(reputation), "
                + "last_name = VALUES(last_name), updated_at = CURRENT_TIMESTAMP";

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
            return columns.next();
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
