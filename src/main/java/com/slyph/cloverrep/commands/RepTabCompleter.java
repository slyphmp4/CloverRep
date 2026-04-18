package com.slyph.cloverrep.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class RepTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            String input = args[0];
            if (sender.hasPermission("cloverrep.rep.plus") && "+".startsWith(input)) {
                suggestions.add("+");
            }
            if (sender.hasPermission("cloverrep.rep.minus") && "-".startsWith(input)) {
                suggestions.add("-");
            }
            return suggestions;
        }

        if (args.length == 2) {
            String action = args[0];
            if (action.equals("+") && !sender.hasPermission("cloverrep.rep.plus")) {
                return suggestions;
            }
            if (action.equals("-") && !sender.hasPermission("cloverrep.rep.minus")) {
                return suggestions;
            }
            if (!action.equals("+") && !action.equals("-")) {
                return suggestions;
            }

            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player player && player.getUniqueId().equals(online.getUniqueId())) {
                    continue;
                }
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(online.getName());
                }
            }

            suggestions.sort(Comparator.naturalOrder());
            return suggestions;
        }

        return suggestions;
    }
}
