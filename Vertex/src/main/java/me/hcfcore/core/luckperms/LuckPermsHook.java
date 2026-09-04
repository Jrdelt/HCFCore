package me.hcfcore.core.luckperms;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Thin wrapper around LuckPerms. Every call first confirms LuckPerms is
 * actually enabled, so referencing its classes below that guard never
 * happens on a server that doesn't have it installed.
 */
public final class LuckPermsHook {

    private LuckPermsHook() {
    }

    public static boolean isAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    /**
     * Grants a permission node that expires on its own after the given
     * duration -- no manual revoke task needed. No-ops if LuckPerms isn't
     * installed or the player has no loaded LuckPerms user (shouldn't
     * happen for an online player, but LuckPerms returns null rather than
     * throwing in that case).
     */
    public static void grantTemporaryPermission(Plugin plugin, Player player, String permissionNode, long seconds) {
        if (!isAvailable()) {
            return;
        }
        grant(plugin, player, permissionNode, seconds);
    }

    /**
     * The player's primary group's configured display name (set via
     * LuckPerms' own "meta setdisplayname"), falling back to the raw
     * group name if none is set. Null if LuckPerms isn't installed or the
     * player has no loaded LuckPerms user.
     */
    /**
     * Null means "no rank to show" -- both for players with no LuckPerms
     * user record, and for LuckPerms' own built-in "default" base group,
     * which every player starts in and which normally has no configured
     * display name (so falling back to the raw group id would print the
     * literal word "default" as everyone's rank).
     */
    public static String getPrimaryGroupDisplayName(Player player) {
        if (!isAvailable()) {
            return null;
        }
        LuckPerms api = LuckPermsProvider.get();
        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        String groupName = user.getPrimaryGroup();
        Group group = api.getGroupManager().getGroup(groupName);
        String displayName = group == null ? null : group.getDisplayName();
        if (displayName != null) {
            return displayName;
        }
        return "default".equalsIgnoreCase(groupName) ? null : groupName;
    }

    /**
     * The player's LuckPerms prefix meta (set via {@code /lp user <name>
     * meta setprefix "..."}, or inherited from a group), with LuckPerms'
     * own weighted meta-stacking already resolved -- unlike
     * {@link #getPrimaryGroupDisplayName}, this carries whatever color/
     * bracket formatting the admin actually configured, rather than a
     * bare group name this plugin wraps itself. Null if LuckPerms isn't
     * installed, the player has no loaded LuckPerms user, or no prefix
     * applies to them.
     */
    public static String getPrefix(Player player) {
        if (!isAvailable()) {
            return null;
        }
        LuckPerms api = LuckPermsProvider.get();
        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        String prefix = user.getCachedData().getMetaData().getPrefix();
        return prefix == null || prefix.isBlank() ? null : prefix;
    }

    private static void grant(Plugin plugin, Player player, String permissionNode, long seconds) {
        LuckPerms api = LuckPermsProvider.get();
        UserManager userManager = api.getUserManager();
        User user = userManager.getUser(player.getUniqueId());
        if (user == null) {
            return;
        }

        PermissionNode node = PermissionNode.builder(permissionNode)
                .expiry(seconds, TimeUnit.SECONDS)
                .build();
        user.data().add(node);
        // saveUser() is async; a swallowed failure here would look like a
        // successful grant, so log it instead of discarding the future.
        userManager.saveUser(user).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Failed to persist LuckPerms permission '" + permissionNode
                    + "' for " + player.getUniqueId(), error);
            return null;
        });
    }
}
