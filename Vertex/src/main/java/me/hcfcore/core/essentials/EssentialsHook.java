package me.hcfcore.core.essentials;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Looks up EssentialsX nicknames via reflection rather than a compile-time
 * dependency -- EssentialsX doesn't publish its API under a stable, public
 * Maven coordinate the way LuckPerms/PlaceholderAPI do, and reflection means
 * a method signature changing between Essentials versions degrades to "no
 * nickname" instead of breaking the build or chat for everyone.
 */
public final class EssentialsHook {

    private EssentialsHook() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("Essentials");
    }

    /**
     * The player's raw Essentials nickname with any leading "~" (Essentials'
     * own "this is a nickname, not a real username" marker) stripped, or
     * null if Essentials isn't installed, the player has none set, or the
     * lookup fails for any reason.
     */
    public static String getNickname(Player player) {
        if (!isAvailable()) {
            return null;
        }
        try {
            Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
            if (essentials == null) {
                return null;
            }
            Method getUser = essentials.getClass().getMethod("getUser", Player.class);
            Object user = getUser.invoke(essentials, player);
            if (user == null) {
                return null;
            }
            Method getNickname = user.getClass().getMethod("getNickname");
            Object nickname = getNickname.invoke(user);
            if (!(nickname instanceof String nick) || nick.isBlank()) {
                return null;
            }
            return nick.startsWith("~") ? nick.substring(1) : nick;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The name every display spot should actually render: the Essentials
     * nickname when one is set, falling back to the real username otherwise.
     */
    public static String resolveName(Player player) {
        String nickname = getNickname(player);
        return nickname != null ? nickname : player.getName();
    }
}
