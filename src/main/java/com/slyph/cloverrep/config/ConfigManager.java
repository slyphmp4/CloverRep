package com.slyph.cloverrep.config;

import com.slyph.cloverrep.CloverRepPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.Locale;

public final class ConfigManager {
    private final CloverRepPlugin plugin;
    private final File messagesFile;
    private FileConfiguration config;
    private FileConfiguration messagesConfig;

    public ConfigManager(CloverRepPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();

        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        this.config = plugin.getConfig();
        this.messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public String getStorageType() {
        return config.getString("storage.type", "local").toLowerCase(Locale.ROOT);
    }

    public long getAutoSaveTicks() {
        long ticks = config.getLong("storage.auto-save-ticks", 600L);
        return Math.max(20L, ticks);
    }

    public String getMySQLHost() {
        return config.getString("mysql.host", "localhost");
    }

    public int getMySQLPort() {
        return config.getInt("mysql.port", 3306);
    }

    public String getMySQLDatabase() {
        return config.getString("mysql.database", "minecraft");
    }

    public String getMySQLUser() {
        return config.getString("mysql.user", "root");
    }

    public String getMySQLPassword() {
        return config.getString("mysql.password", "password");
    }

    public String getMySQLTablePrefix() {
        return config.getString("mysql.tablePrefix", "clover_");
    }

    public int getMySQLPoolMaximumSize() {
        int value = config.getInt("mysql.pool.maximum-size", 10);
        return Math.max(1, value);
    }

    public int getMySQLPoolMinimumIdle() {
        int value = config.getInt("mysql.pool.minimum-idle", 2);
        return Math.max(0, value);
    }

    public long getMySQLPoolConnectionTimeoutMillis() {
        long value = config.getLong("mysql.pool.connection-timeout-millis", 5000L);
        return Math.max(1000L, value);
    }

    public long getMySQLPoolIdleTimeoutMillis() {
        long value = config.getLong("mysql.pool.idle-timeout-millis", 300000L);
        return Math.max(10000L, value);
    }

    public long getMySQLPoolMaxLifetimeMillis() {
        long value = config.getLong("mysql.pool.max-lifetime-millis", 1800000L);
        return Math.max(30000L, value);
    }

    public String getPositiveReputationColor() {
        return config.getString("colors.reputation-positive", "&46FF62");
    }

    public String getNegativeReputationColor() {
        return config.getString("colors.reputation-negative", "&FF4646");
    }

    public String getNeutralReputationColor() {
        return config.getString("colors.reputation-neutral", "&898989");
    }

    public String getDarkNickColor() {
        return config.getString("colors.dark-nick", "DARK_GRAY");
    }

    public int getDarkNickThreshold() {
        return config.getInt("reputation.dark-nick-threshold", -20);
    }

    public String getPlayerOnlyMessage() {
        return listMessage("messages.player-only", List.of("&7", "&cThis command is available only for players.", "&7"));
    }

    public String getNoPermissionMessage() {
        return listMessage("messages.no-permission", List.of("&7", "&cYou do not have permission for this action.", "&7"));
    }

    public String getSelfVoteErrorMessage() {
        return listMessage("messages.self-vote-error", List.of("&7", "&cYou cannot change your own reputation.", "&7"));
    }

    public String getAlreadyVotedMessage() {
        return listMessage("messages.already-voted", List.of("&7", "&cYou have already changed this player's reputation.", "&7"));
    }

    public String getSuccessMessage() {
        return listMessage("messages.success", List.of("&7", "&aPlayer %target% reputation was updated successfully.", "&7"));
    }

    public String getErrorMessage() {
        return listMessage("messages.error", List.of("&7", "&cPlayer not found.", "&7"));
    }

    public String getUsageMessage() {
        return listMessage("messages.usage", List.of("&7", "&eUsage: /rep <+|-> <player>", "&7"));
    }

    public String getReloadUsageMessage() {
        return listMessage("messages.reload-usage", List.of("&7", "&eUsage: /cloverrep <reload|status|checkdb|backup>", "&7"));
    }

    public String getReloadSuccessMessage() {
        return listMessage("messages.reload-success", List.of("&7", "&aPlugin configuration has been reloaded.", "&7"));
    }

    public String getReloadStorageNoticeMessage() {
        return listMessage("messages.reload-storage-notice", List.of("&7", "&eChanges to storage.type require server restart.", "&7"));
    }

    public String getStatusMessage() {
        return listMessage("messages.status", List.of(
                "&7",
                "&bBackend: &f%backend%",
                "&bAvailable: &f%available%",
                "&bPending save: &f%dirty%",
                "&bCache reputations: &f%cache_reputations%",
                "&bCache votes: &f%cache_votes%",
                "&7"
        ));
    }

    public String getCheckDbUnsupportedMessage() {
        return listMessage("messages.checkdb-unsupported", List.of("&7", "&eThis command is available only when storage.type=mysql.", "&7"));
    }

    public String getCheckDbSuccessMessage() {
        return listMessage("messages.checkdb-success", List.of(
                "&7",
                "&aDatabase is available.",
                "&aPing: &f%latency%ms",
                "&aPlayers: &f%players%",
                "&aVotes: &f%votes%",
                "&7"
        ));
    }

    public String getCheckDbErrorMessage() {
        return listMessage("messages.checkdb-error", List.of("&7", "&cDatabase check failed: %error%", "&7"));
    }

    public String getBackupSuccessMessage() {
        return listMessage("messages.backup-success", List.of("&7", "&aBackup created: &f%file%", "&7"));
    }

    public String getBackupErrorMessage() {
        return listMessage("messages.backup-error", List.of("&7", "&cBackup failed: %error%", "&7"));
    }

    public String getStorageErrorMessage() {
        return listMessage("messages.storage-error", List.of("&7", "&cStorage is temporarily unavailable. Try again later.", "&7"));
    }

    public String getReputationFormat() {
        return messagesConfig.getString("messages.reputation-suffix", " [&f%reputation%&r]");
    }

    public String getErrorSound() {
        return config.getString("sounds.error", "ENTITY_VILLAGER_NO");
    }

    public boolean isBelowNameEnabled() {
        return config.getBoolean("display.below-name.enabled", true);
    }

    public boolean isBelowNameForceSlot() {
        return config.getBoolean("display.below-name.force-slot", false);
    }

    public String getBelowNameObjectiveId() {
        return config.getString("display.below-name.objective-id", "cloverrep");
    }

    public String getBelowNameTitle() {
        return config.getString("display.below-name.title", "&eRep");
    }

    private String listMessage(String path, List<String> fallback) {
        List<String> lines = messagesConfig.getStringList(path);
        if (lines == null || lines.isEmpty()) {
            lines = fallback;
        }
        return String.join("\n", lines);
    }
}
