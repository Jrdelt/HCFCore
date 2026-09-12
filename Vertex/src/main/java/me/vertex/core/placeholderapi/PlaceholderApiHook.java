package me.vertex.core.placeholderapi;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin wrapper around PlaceholderAPI (softdepend), following the same
 * shape as {@link me.vertex.core.worldguard.WorldGuardHook} and
 * {@link me.vertex.core.economy.EconomyHook}: static, stateless, and
 * checks the target plugin is actually enabled before touching any of
 * its classes, so Vertex runs fine without it installed.
 */
public final class PlaceholderApiHook {

    private static final Pattern INTERNAL_VERTEX = Pattern.compile("<vertex:([A-Za-z0-9_]+)(?::([A-Za-z0-9_]{1,32}))?>");

    private PlaceholderApiHook() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /**
     * Expands any {@code %placeholder%} tokens in `text` (from
     * PlaceholderAPI and whatever expansions are installed alongside it --
     * LuckPerms', or any other) for `player`. Returns `text` unchanged if
     * PlaceholderAPI isn't installed, so callers don't need their own
     * availability check.
     */
    public static String apply(Player player, String text) {
        if (!isAvailable() || text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = INTERNAL_VERTEX.matcher(text);
        StringBuffer translated = new StringBuffer();
        while (matcher.find()) {
            String identifier = matcher.group(1) + (matcher.group(2) == null ? "" : "_" + matcher.group(2));
            matcher.appendReplacement(translated, Matcher.quoteReplacement("%vertex_" + identifier + "%"));
        }
        matcher.appendTail(translated);
        return PlaceholderAPI.setPlaceholders(player, translated.toString());
    }
}
