package com.slyph.cloverrep.utils;

import org.bukkit.command.CommandSender;

public final class MessageUtil {
    private MessageUtil() {
    }

    public static void send(CommandSender sender, String message) {
        if (message == null) {
            return;
        }
        String[] lines = message.split("\\n");
        for (String line : lines) {
            sender.sendMessage(ColorUtil.toComponent(line));
        }
    }
}
