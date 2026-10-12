package com.slyph.cloverrep.utils;

import com.slyph.cloverrep.CloverRepPlugin;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Optional;

public final class SoundUtil {
    private static final Cache<String, Optional<Sound>> SOUND_CACHE = Caffeine.newBuilder().maximumSize(128).build();
    private SoundUtil() {
    }

    public static void playErrorSound(Player player, String soundName) {
        if (soundName == null || soundName.isBlank()) {
            return;
        }

        Sound sound = SOUND_CACHE.get(soundName, value -> {
            Sound resolved = resolveSound(value);
            if (resolved == null) {
                CloverRepPlugin.getInstance().getLogger().warning("Invalid sound in config: " + value);
            }
            return Optional.ofNullable(resolved);
        }).orElse(null);
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 1.0F, 1.0F);
            return;
        }

    }

    private static Sound resolveSound(String soundName) {
        String normalized = soundName.trim();
        NamespacedKey key = NamespacedKey.fromString(normalized.toLowerCase(Locale.ROOT));
        if (key != null) {
            Sound sound = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT).get(key);
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
