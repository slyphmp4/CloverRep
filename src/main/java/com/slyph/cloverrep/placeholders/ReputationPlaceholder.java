package com.slyph.cloverrep.placeholders;

import com.slyph.cloverrep.CloverRepPlugin;
import com.slyph.cloverrep.config.ConfigManager;
import com.slyph.cloverrep.utils.ColorUtil;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.UUID;

public final class ReputationPlaceholder extends PlaceholderExpansion {
    private static final String PREFIX_REPUTATION_PLAYER = "reputation_player_";
    private static final String PREFIX_REPUTATION_RAW_PLAYER = "reputation_raw_player_";
    private static final String PREFIX_REPUTATION_SIGNED_PLAYER = "reputation_signed_player_";
    private static final String PREFIX_REPUTATION_UUID = "reputation_uuid_";
    private static final String PREFIX_REPUTATION_RAW_UUID = "reputation_raw_uuid_";
    private static final String PREFIX_REPUTATION_SIGNED_UUID = "reputation_signed_uuid_";

    private static final String PREFIX_NICK_COLOR_PLAYER = "nick_color_player_";
    private static final String PREFIX_NICK_COLOR_UUID = "nick_color_uuid_";
    private static final String PREFIX_DARK_NICK_PLAYER = "dark_nick_player_";
    private static final String PREFIX_DARK_NICK_UUID = "dark_nick_uuid_";
    private static final String PREFIX_NICK_PLAYER = "nick_player_";
    private static final String PREFIX_NICK_UUID = "nick_uuid_";

    private final CloverRepPlugin plugin;

    public ReputationPlaceholder(CloverRepPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "cloverrep";
    }

    @Override
    public @NotNull String getAuthor() {
        return "slyph";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("reputation")) {
            int reputation = resolveReputation(player);
            return formatColored(reputation);
        }

        if (params.equalsIgnoreCase("reputation_raw")) {
            int reputation = resolveReputation(player);
            return String.valueOf(reputation);
        }

        if (params.equalsIgnoreCase("reputation_signed")) {
            int reputation = resolveReputation(player);
            return formatSigned(reputation);
        }

        if (params.equalsIgnoreCase("nick_color")) {
            int reputation = resolveReputation(player);
            return resolveNickColorCode(reputation);
        }

        if (params.equalsIgnoreCase("dark_nick")) {
            int reputation = resolveReputation(player);
            return String.valueOf(isDarkNick(reputation));
        }

        if (params.equalsIgnoreCase("nick")) {
            String name = player == null || player.getName() == null ? "" : player.getName();
            int reputation = resolveReputation(player);
            return resolveNickColorCode(reputation) + name;
        }

        if (startsWithIgnoreCase(params, PREFIX_REPUTATION_PLAYER)) {
            String name = params.substring(PREFIX_REPUTATION_PLAYER.length());
            int reputation = resolveReputationByName(name);
            return formatColored(reputation);
        }

        if (startsWithIgnoreCase(params, PREFIX_REPUTATION_RAW_PLAYER)) {
            String name = params.substring(PREFIX_REPUTATION_RAW_PLAYER.length());
            int reputation = resolveReputationByName(name);
            return String.valueOf(reputation);
        }

        if (startsWithIgnoreCase(params, PREFIX_REPUTATION_SIGNED_PLAYER)) {
            String name = params.substring(PREFIX_REPUTATION_SIGNED_PLAYER.length());
            int reputation = resolveReputationByName(name);
            return formatSigned(reputation);
        }

        if (startsWithIgnoreCase(params, PREFIX_REPUTATION_UUID)) {
            String uuidText = params.substring(PREFIX_REPUTATION_UUID.length());
            int reputation = resolveReputationByUuid(uuidText);
            return formatColored(reputation);
        }

        if (startsWithIgnoreCase(params, PREFIX_REPUTATION_RAW_UUID)) {
            String uuidText = params.substring(PREFIX_REPUTATION_RAW_UUID.length());
            int reputation = resolveReputationByUuid(uuidText);
            return String.valueOf(reputation);
        }

        if (startsWithIgnoreCase(params, PREFIX_REPUTATION_SIGNED_UUID)) {
            String uuidText = params.substring(PREFIX_REPUTATION_SIGNED_UUID.length());
            int reputation = resolveReputationByUuid(uuidText);
            return formatSigned(reputation);
        }

        if (startsWithIgnoreCase(params, PREFIX_NICK_COLOR_PLAYER)) {
            String name = params.substring(PREFIX_NICK_COLOR_PLAYER.length());
            int reputation = resolveReputationByName(name);
            return resolveNickColorCode(reputation);
        }

        if (startsWithIgnoreCase(params, PREFIX_NICK_COLOR_UUID)) {
            String uuidText = params.substring(PREFIX_NICK_COLOR_UUID.length());
            int reputation = resolveReputationByUuid(uuidText);
            return resolveNickColorCode(reputation);
        }

        if (startsWithIgnoreCase(params, PREFIX_DARK_NICK_PLAYER)) {
            String name = params.substring(PREFIX_DARK_NICK_PLAYER.length());
            int reputation = resolveReputationByName(name);
            return String.valueOf(isDarkNick(reputation));
        }

        if (startsWithIgnoreCase(params, PREFIX_DARK_NICK_UUID)) {
            String uuidText = params.substring(PREFIX_DARK_NICK_UUID.length());
            int reputation = resolveReputationByUuid(uuidText);
            return String.valueOf(isDarkNick(reputation));
        }

        if (startsWithIgnoreCase(params, PREFIX_NICK_PLAYER)) {
            String name = params.substring(PREFIX_NICK_PLAYER.length());
            int reputation = resolveReputationByName(name);
            return resolveNickColorCode(reputation) + name;
        }

        if (startsWithIgnoreCase(params, PREFIX_NICK_UUID)) {
            String uuidText = params.substring(PREFIX_NICK_UUID.length());
            String name = resolveNameByUuid(uuidText);
            if (name.isEmpty()) {
                return "";
            }
            int reputation = resolveReputationByName(name);
            return resolveNickColorCode(reputation) + name;
        }

        return null;
    }

    @Override
    public String onPlaceholderRequest(Player player, @NotNull String params) {
        return onRequest(player, params);
    }

    private int resolveReputation(OfflinePlayer player) {
        if (player == null || player.getUniqueId() == null) {
            return 0;
        }

        plugin.getReputationManager().registerPlayerIdentity(player);
        return plugin.getReputationManager().getReputation(player.getUniqueId());
    }

    private int resolveReputationByName(String name) {
        UUID uuid = plugin.getReputationManager().findUuidByName(name);
        if (uuid == null) {
            return 0;
        }
        return plugin.getReputationManager().getReputation(uuid);
    }

    private int resolveReputationByUuid(String uuidText) {
        UUID uuid = parseUuid(uuidText);
        if (uuid == null) {
            return 0;
        }
        return plugin.getReputationManager().getReputation(uuid);
    }

    private String resolveNameByUuid(String uuidText) {
        UUID uuid = parseUuid(uuidText);
        if (uuid == null) {
            return "";
        }
        return plugin.getReputationManager().findNameByUuid(uuid);
    }

    private UUID parseUuid(String uuidText) {
        if (uuidText == null || uuidText.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(uuidText);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String formatColored(int reputation) {
        ConfigManager config = plugin.getConfigManager();
        String color;

        if (reputation > 0) {
            color = config.getPositiveReputationColor();
        } else if (reputation < 0) {
            color = config.getNegativeReputationColor();
        } else {
            color = config.getNeutralReputationColor();
        }

        return ColorUtil.applyColor(color + reputation);
    }

    private String formatSigned(int reputation) {
        if (reputation > 0) {
            return "+" + reputation;
        }

        return String.valueOf(reputation);
    }

    private boolean isDarkNick(int reputation) {
        return reputation <= plugin.getConfigManager().getDarkNickThreshold();
    }

    private String resolveNickColorCode(int reputation) {
        if (!isDarkNick(reputation)) {
            return ChatColor.RESET.toString();
        }

        String raw = plugin.getConfigManager().getDarkNickColor();
        if (raw == null || raw.isBlank()) {
            return ChatColor.DARK_GRAY.toString();
        }

        String trimmed = raw.trim();

        try {
            return ChatColor.valueOf(trimmed.toUpperCase(Locale.ROOT)).toString();
        } catch (IllegalArgumentException ignored) {
            String candidate = trimmed.startsWith("&") || trimmed.startsWith("\u00A7") ? trimmed : "&" + trimmed;
            String colored = ColorUtil.applyColor(candidate);
            if (colored.equals(candidate)) {
                return ChatColor.DARK_GRAY.toString();
            }
            return colored;
        }
    }

    private boolean startsWithIgnoreCase(String source, String prefix) {
        return source.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
