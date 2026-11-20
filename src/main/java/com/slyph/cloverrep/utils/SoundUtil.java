package com.slyph.cloverrep.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.slyph.cloverrep.CloverRepPlugin;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Optional;

/**
 * Uses the typed Paper sound registry when available, but remains usable on
 * Bukkit-compatible bridges which do not implement every Paper registry API.
 */
public final class SoundUtil {
    private static final Cache<String, Optional<Sound>> SOUND_CACHE =
            Caffeine.newBuilder().maximumSize(128).build();

    private SoundUtil() {
    }

    public static void playErrorSound(Player player, String soundName) {
        if (player == null || soundName == null || soundName.isBlank()) {
            return;
        }

        String requested = soundName.trim();
        try {
            Optional<Sound> resolved = SOUND_CACHE.get(requested,
                    value -> Optional.ofNullable(resolveSound(value)));
            if (resolved.isPresent()) {
                player.playSound(player.getLocation(), resolved.get(), 1.0F, 1.0F);
                return;
            }
        } catch (LinkageError | RuntimeException ignored) {
            // A Fabric/Bukkit bridge may expose Paper classes without providing
            // the full registry implementation. Use the Bukkit sound-key overload.
        }

        String key = toSoundKey(requested);
        if (key != null) {
            player.playSound(player.getLocation(), key, 1.0F, 1.0F);
        } else {
            CloverRepPlugin plugin = CloverRepPlugin.getInstance();
            if (plugin != null) {
                plugin.getLogger().warning("Invalid sound in config: " + requested);
            }
        }
    }

    static String toSoundKey(String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }

        String normalized = requested.trim().toLowerCase(Locale.ROOT);
        if (!normalized.contains(":") && !normalized.contains(".")) {
            // Older configuration files used Bukkit constant names. Preserve
            // the embedded underscore in the two special vanilla namespaces.
            if (normalized.startsWith("block_note_block_")) {
                normalized = "block.note_block." + normalized.substring("block_note_block_".length()).replace('_', '.');
            } else if (normalized.startsWith("music_disc_")) {
                normalized = "music_disc." + normalized.substring("music_disc_".length()).replace('_', '.');
            } else {
                normalized = normalized.replace('_', '.');
            }
        }

        String qualified = normalized.contains(":") ? normalized : "minecraft:" + normalized;
        return NamespacedKey.fromString(qualified) == null ? null : qualified;
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
            // The configured value is not a legacy static Bukkit sound constant.
        }

        return null;
    }
}
