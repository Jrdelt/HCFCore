package me.hcfcore.core.placeholderapi;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Thin wrapper around PlaceholderAPI (softdepend), following the same
 * shape as {@link me.hcfcore.core.worldguard.WorldGuardHook} and
 * {@link me.hcfcore.core.economy.EconomyHook}: static, stateless, and
 * checks the target plugin is actually enabled before touching any of
 * its classes, so HCFCore runs fine without it installed.
 */
public final class PlaceholderApiHook {

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
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
