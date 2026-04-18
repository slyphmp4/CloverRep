package com.slyph.cloverrep.commands.admin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CloverRepTabCompleter implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            addIfAllowed(suggestions, input, "reload", sender.hasPermission("cloverrep.admin.reload"));
            addIfAllowed(suggestions, input, "status", sender.hasPermission("cloverrep.admin.status"));
            addIfAllowed(suggestions, input, "checkdb", sender.hasPermission("cloverrep.admin.checkdb"));
            addIfAllowed(suggestions, input, "backup", sender.hasPermission("cloverrep.admin.backup"));
        }

        return suggestions;
    }

    private void addIfAllowed(List<String> suggestions, String input, String option, boolean allowed) {
        if (allowed && option.startsWith(input)) {
            suggestions.add(option);
        }
    }
}
