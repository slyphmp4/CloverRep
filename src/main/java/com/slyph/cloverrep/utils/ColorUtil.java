package com.slyph.cloverrep.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ColorUtil {
    private static final Pattern HEX_PATTERN = Pattern.compile("&(?:#)?([A-Fa-f0-9]{6})");
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final Map<String, Character> NAMED_CODES = Map.ofEntries(
            Map.entry("BLACK", '0'),
            Map.entry("DARK_BLUE", '1'),
            Map.entry("DARK_GREEN", '2'),
            Map.entry("DARK_AQUA", '3'),
            Map.entry("DARK_RED", '4'),
            Map.entry("DARK_PURPLE", '5'),
            Map.entry("GOLD", '6'),
            Map.entry("GRAY", '7'),
            Map.entry("DARK_GRAY", '8'),
            Map.entry("BLUE", '9'),
            Map.entry("GREEN", 'a'),
            Map.entry("AQUA", 'b'),
            Map.entry("RED", 'c'),
            Map.entry("LIGHT_PURPLE", 'd'),
            Map.entry("YELLOW", 'e'),
            Map.entry("WHITE", 'f'),
            Map.entry("MAGIC", 'k'),
            Map.entry("BOLD", 'l'),
            Map.entry("STRIKETHROUGH", 'm'),
            Map.entry("UNDERLINE", 'n'),
            Map.entry("ITALIC", 'o'),
            Map.entry("RESET", 'r')
    );

    private ColorUtil() {
    }

    public static String applyColor(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("\u00A7x");
            for (int index = 0; index < hex.length(); index++) {
                replacement.append('\u00A7').append(hex.charAt(index));
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement.toString()));
        }

        matcher.appendTail(buffer);
        return translateLegacyCodes(buffer.toString());
    }

    public static Component toComponent(String text) {
        return LEGACY_SERIALIZER.deserialize(applyColor(text));
    }

    public static String resolveColorCode(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        String trimmed = raw.trim();
        if (trimmed.indexOf('\u00A7') >= 0) {
            return trimmed;
        }

        Character namedCode = NAMED_CODES.get(trimmed.toUpperCase(Locale.ROOT));
        if (namedCode != null) {
            return "\u00A7" + namedCode;
        }

        String candidate = trimmed.startsWith("&") || trimmed.startsWith("\u00A7") ? trimmed : "&" + trimmed;
        String colored = applyColor(candidate);
        return colored.equals(candidate) ? fallback : colored;
    }

    private static String translateLegacyCodes(String text) {
        char[] characters = text.toCharArray();
        for (int index = 0; index < characters.length - 1; index++) {
            if (characters[index] == '&' && isLegacyCode(characters[index + 1])) {
                characters[index] = '\u00A7';
                characters[index + 1] = Character.toLowerCase(characters[index + 1]);
            }
        }
        return new String(characters);
    }

    private static boolean isLegacyCode(char value) {
        char normalized = Character.toLowerCase(value);
        return (normalized >= '0' && normalized <= '9')
                || (normalized >= 'a' && normalized <= 'f')
                || (normalized >= 'k' && normalized <= 'o')
                || normalized == 'r';
    }
}
