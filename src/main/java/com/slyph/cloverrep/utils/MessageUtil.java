package com.slyph.cloverrep.utils;

import org.bukkit.command.CommandSender;

public final class MessageUtil {
    private MessageUtil() {
    }

    public static void send(CommandSender sender, String message) {
        String colored = ColorUtil.applyColor(message);
        String[] lines = colored.split("\\n");
        for (String line : lines) {
            sender.sendMessage(line);
        }
    }
}
