package com.slyph.cloverrep.utils;

import com.slyph.cloverrep.CloverRepPlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;

public final class SoundUtil {
    private SoundUtil() {
    }

    public static void playErrorSound(Player player, String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return;
        }

        Sound sound = resolveSound(soundName);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 1.0F, 1.0F);
            return;
        }

        CloverRepPlugin.getInstance().getLogger().warning("Invalid sound in config: " + soundName);
    }

    private static Sound resolveSound(String soundName) {
        String normalized = soundName.trim();
        NamespacedKey key = NamespacedKey.fromString(normalized.toLowerCase(Locale.ROOT));
        if (key != null) {
            Sound sound = Registry.SOUND_EVENT.get(key);
            if (sound != null) {
                return sound;
            }
        }

        try {
            Field legacyField = Sound.class.getField(normalized.toUpperCase(Locale.ROOT));
            if (Modifier.isStatic(legacyField.getModifiers()) && Sound.class.isAssignableFrom(legacyField.getType())) {
                return (Sound) legacyField.get(null);
            }
        } catch (IllegalAccessException | NoSuchFieldException ignored) {
            // The configured value is neither a namespaced key nor a legacy Bukkit constant.
        }

        return null;
    }
}
