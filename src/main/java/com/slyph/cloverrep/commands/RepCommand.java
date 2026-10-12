package com.slyph.cloverrep.commands;

import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.managers.ReputationManager;
import com.slyph.cloverrep.utils.MessageUtil;
import com.slyph.cloverrep.utils.SoundUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RepCommand implements CommandExecutor {
    private final CloverRepPlugin plugin;

    public RepCommand(CloverRepPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getConfigManager().getPlayerOnlyMessage());
            return true;
        }

        if (args.length != 2) {
            MessageUtil.send(player, plugin.getConfigManager().getUsageMessage());
            return true;
        }

        String action = args[0];
        if (!action.equals("+") && !action.equals("-")) {
            MessageUtil.send(player, plugin.getConfigManager().getUsageMessage());
            return true;
        }

        String actionPermission = action.equals("+") ? "cloverrep.rep.plus" : "cloverrep.rep.minus";
        if (!player.hasPermission(actionPermission)) {
            MessageUtil.send(player, plugin.getConfigManager().getNoPermissionMessage());
            SoundUtil.playErrorSound(player, plugin.getConfigManager().getErrorSound());
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().equalsIgnoreCase(args[1])) {
                    target = online;
                    break;
                }
            }
        }
        if (target == null) {
            MessageUtil.send(player, plugin.getConfigManager().getErrorMessage());
            SoundUtil.playErrorSound(player, plugin.getConfigManager().getErrorSound());
            return true;
        }

        if (player.getUniqueId().equals(target.getUniqueId())) {
            MessageUtil.send(player, plugin.getConfigManager().getSelfVoteErrorMessage());
            SoundUtil.playErrorSound(player, plugin.getConfigManager().getErrorSound());
            return true;
        }

        boolean bypassVoteLimit = player.hasPermission("cloverrep.rep.bypass");

        int amount = action.equals("+") ? 1 : -1;
        Player resolvedTarget = target;
        plugin.getReputationManager().addReputation(player, target, amount, bypassVoteLimit).thenAccept(result ->
                plugin.runOnMainThread(() -> {
                    plugin.getReputationManager().updatePlayerDisplay(resolvedTarget);
                    if (!player.isOnline()) {
                        return;
                    }
                    if (result.status() == ReputationManager.VoteChangeStatus.ALREADY_VOTED) {
                        MessageUtil.send(player, plugin.getConfigManager().getAlreadyVotedMessage());
                        SoundUtil.playErrorSound(player, plugin.getConfigManager().getErrorSound());
                    } else if (result.status() == ReputationManager.VoteChangeStatus.FAILED) {
                        MessageUtil.send(player, plugin.getConfigManager().getStorageErrorMessage());
                        SoundUtil.playErrorSound(player, plugin.getConfigManager().getErrorSound());
                    } else {
                        String success = plugin.getConfigManager().getSuccessMessage()
                                .replace("%target%", resolvedTarget.getName())
                                .replace("%change%", action)
                                .replace("%reputation%", String.valueOf(result.reputation()));
                        MessageUtil.send(player, success);
                    }
                }));
        return true;
    }
}
