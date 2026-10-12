package com.slyph.cloverrep;

import com.slyph.cloverrep.commands.RepCommand;
import com.slyph.cloverrep.commands.RepTabCompleter;
import com.slyph.cloverrep.commands.admin.CloverRepCommand;
import com.slyph.cloverrep.commands.admin.CloverRepTabCompleter;
import com.slyph.cloverrep.config.ConfigManager;
import com.slyph.cloverrep.listeners.PlayerJoinListener;
import com.slyph.cloverrep.managers.ReputationManager;
import com.slyph.cloverrep.placeholders.ReputationPlaceholder;
import org.bukkit.command.PluginCommand;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

public final class CloverRepPlugin extends JavaPlugin {
    private static CloverRepPlugin instance;
    private ConfigManager configManager;
    private ReputationManager reputationManager;
    private BukkitTask autoSaveTask;
    private ReputationPlaceholder placeholder;
    private volatile boolean shuttingDown;

    @Override
    public void onEnable() {
        shuttingDown = false;
        instance = this;
        configManager = new ConfigManager(this);
        reputationManager = new ReputationManager(this);

        PluginCommand repCommand = getCommand("rep");
        if (repCommand == null) {
            getLogger().severe("Command rep is not defined in plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand cloverRepCommand = getCommand("cloverrep");
        if (cloverRepCommand == null) {
            getLogger().severe("Command cloverrep is not defined in plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        repCommand.setExecutor(new RepCommand(this));
        repCommand.setTabCompleter(new RepTabCompleter());

        cloverRepCommand.setExecutor(new CloverRepCommand(this));
        cloverRepCommand.setTabCompleter(new CloverRepTabCompleter());

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        reputationManager.updateAllOnlinePlayers();

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholder = new ReputationPlaceholder(this);
            placeholder.register();
        }

        startAutoSaveTask();
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        stopAutoSaveTask();

        if (placeholder != null) {
            placeholder.unregister();
            placeholder = null;
        }

        if (reputationManager != null) {
            reputationManager.shutdown();
        }
        instance = null;
    }

    public void runOnMainThread(Runnable action) {
        if (shuttingDown || !isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        try {
            getServer().getScheduler().runTask(this, () -> {
                if (!shuttingDown && isEnabled()) {
                    action.run();
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // The server may disable the plugin between completion and scheduling.
        }
    }

    public static CloverRepPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public ReputationManager getReputationManager() {
        return reputationManager;
    }

    public boolean reloadPluginConfiguration() {
        String previousStorageType = configManager.getStorageType();
        configManager.reload();

        if (reputationManager != null) {
            reputationManager.reloadDisplaySettings();
        }

        restartAutoSaveTask();

        String currentStorageType = configManager.getStorageType();
        return !previousStorageType.equalsIgnoreCase(currentStorageType);
    }

    private void restartAutoSaveTask() {
        stopAutoSaveTask();
        startAutoSaveTask();
    }

    private void stopAutoSaveTask() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
    }

    private void startAutoSaveTask() {
        long interval = configManager.getAutoSaveTicks();
        autoSaveTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (reputationManager != null) {
                reputationManager.saveAllData();
            }
        }, interval, interval);
    }
}
