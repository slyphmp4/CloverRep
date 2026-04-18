package com.slyph.cloverrep.utils;

import com.slyph.cloverrep.CloverRepPlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class SoundUtil {
    private SoundUtil() {
    }

    public static void playErrorSound(Player player, String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return;
        }

        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, 1.0F, 1.0F);
        } catch (IllegalArgumentException exception) {
            CloverRepPlugin.getInstance().getLogger().warning("Invalid sound in config: " + soundName);
        }
    }
}
