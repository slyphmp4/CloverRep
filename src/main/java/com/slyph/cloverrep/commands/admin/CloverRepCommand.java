package com.slyph.cloverrep.commands.admin;

import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.storage.StorageBackupResult;
import com.slyph.cloverrep.storage.StorageCheckResult;
import com.slyph.cloverrep.utils.MessageUtil;
import com.slyph.cloverrep.utils.SoundUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class CloverRepCommand implements CommandExecutor {
    private final CloverRepPlugin plugin;

    public CloverRepCommand(CloverRepPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            MessageUtil.send(sender, plugin.getConfigManager().getReloadUsageMessage());
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);

        return switch (subCommand) {
            case "reload" -> handleReload(sender);
            case "status" -> handleStatus(sender);
            case "checkdb" -> handleCheckDb(sender);
            case "backup" -> handleBackup(sender);
            default -> {
                MessageUtil.send(sender, plugin.getConfigManager().getReloadUsageMessage());
                yield true;
            }
        };
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("cloverrep.admin.reload")) {
            sendNoPermission(sender);
            return true;
        }

        boolean storageChanged = plugin.reloadPluginConfiguration();
        MessageUtil.send(sender, plugin.getConfigManager().getReloadSuccessMessage());
        if (storageChanged) {
            MessageUtil.send(sender, plugin.getConfigManager().getReloadStorageNoticeMessage());
        }
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!sender.hasPermission("cloverrep.admin.status")) {
            sendNoPermission(sender);
            return true;
        }

        String message = plugin.getConfigManager().getStatusMessage()
                .replace("%backend%", plugin.getReputationManager().getStorageBackendName())
                .replace("%available%", String.valueOf(plugin.getReputationManager().isStorageAvailable()))
                .replace("%dirty%", String.valueOf(plugin.getReputationManager().hasPendingChanges()))
                .replace("%cache_reputations%", String.valueOf(plugin.getReputationManager().getReputationCacheSize()))
                .replace("%cache_votes%", String.valueOf(plugin.getReputationManager().getVoteCacheSize()));
        MessageUtil.send(sender, message);
        return true;
    }

    private boolean handleCheckDb(CommandSender sender) {
        if (!sender.hasPermission("cloverrep.admin.checkdb")) {
            sendNoPermission(sender);
            return true;
        }

        if (!"mysql".equalsIgnoreCase(plugin.getReputationManager().getStorageBackendName())) {
            MessageUtil.send(sender, plugin.getConfigManager().getCheckDbUnsupportedMessage());
            return true;
        }

        plugin.getReputationManager().checkStorage().thenAccept(result ->
                plugin.runOnMainThread(() -> sendCheckDbResult(sender, result)));
        return true;
    }

    private void sendCheckDbResult(CommandSender sender, StorageCheckResult result) {
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }
        if (result.success()) {
            String message = plugin.getConfigManager().getCheckDbSuccessMessage()
                    .replace("%latency%", String.valueOf(result.latencyMillis()))
                    .replace("%players%", String.valueOf(result.playersCount()))
                    .replace("%votes%", String.valueOf(result.votesCount()));
            MessageUtil.send(sender, message);
            return;
        }

        String errorText = result.errorMessage() == null || result.errorMessage().isBlank() ? "unknown error" : result.errorMessage();
        String message = plugin.getConfigManager().getCheckDbErrorMessage().replace("%error%", errorText);
        MessageUtil.send(sender, message);
        if (sender instanceof Player player) {
            SoundUtil.playErrorSound(player, plugin.getConfigManager().getErrorSound());
        }
    }

    private boolean handleBackup(CommandSender sender) {
        if (!sender.hasPermission("cloverrep.admin.backup")) {
            sendNoPermission(sender);
            return true;
        }

        plugin.getReputationManager().createBackup().thenAccept(result ->
                plugin.runOnMainThread(() -> sendBackupResult(sender, result)));
        return true;
    }

    private void sendBackupResult(CommandSender sender, StorageBackupResult result) {
        if (sender instanceof Player player && !player.isOnline()) {
            return;
        }
        if (result.success()) {
            String message = plugin.getConfigManager().getBackupSuccessMessage().replace("%file%", result.fileName());
            MessageUtil.send(sender, message);
            return;
        }

        String errorText = result.errorMessage() == null || result.errorMessage().isBlank() ? "unknown error" : result.errorMessage();
        String message = plugin.getConfigManager().getBackupErrorMessage().replace("%error%", errorText);
        MessageUtil.send(sender, message);
        if (sender instanceof Player player) {
            SoundUtil.playErrorSound(player, plugin.getConfigManager().getErrorSound());
        }
    }

    private void sendNoPermission(CommandSender sender) {
        MessageUtil.send(sender, plugin.getConfigManager().getNoPermissionMessage());
        if (sender instanceof Player player) {
            SoundUtil.playErrorSound(player, plugin.getConfigManager().getErrorSound());
        }
    }
}
