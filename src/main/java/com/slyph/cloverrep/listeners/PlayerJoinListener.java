package com.slyph.cloverrep.listeners;

import com.slyph.cloverrep.CloverRepPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {
    private final CloverRepPlugin plugin;

    public PlayerJoinListener(CloverRepPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getReputationManager().registerPlayerIdentity(event.getPlayer());
        plugin.getReputationManager().updatePlayerDisplay(event.getPlayer());
    }
}
