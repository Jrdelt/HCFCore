package me.vertex.core.luckperms;

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
import java.util.Comparator;
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
        Group group = highestWeightGroup(api, user);
        String groupName = group == null ? user.getPrimaryGroup() : group.getName();
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

    /**
     * The player's primary group's id, display name, and weight, resolved
     * in one LuckPerms lookup -- for callers (the grouped tab list) that
     * need all three together every render tick, rather than paying for
     * {@link #getPrimaryGroupDisplayName} and a separate weight lookup
     * each. Null under the same conditions as {@link #getPrimaryGroupDisplayName}:
     * LuckPerms missing, no loaded user, or the group is LuckPerms' own
     * unconfigured "default" base group.
     */
    public record GroupInfo(String id, String displayName, int weight) {
    }

    public static GroupInfo getPrimaryGroupInfo(Player player) {
        if (!isAvailable()) {
            return null;
        }
        LuckPerms api = LuckPermsProvider.get();
        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        Group group = highestWeightGroup(api, user);
        String groupId = group == null ? user.getPrimaryGroup() : group.getName();
        if ("default".equalsIgnoreCase(groupId)) {
            return null;
        }
        String displayName = group == null ? null : group.getDisplayName();
        int weight = group == null ? 0 : group.getWeight().orElse(0);
        return new GroupInfo(groupId, displayName == null ? groupId : displayName, weight);
    }

    /**
     * LuckPerms' primary group is administrator-selectable and need not be
     * the rank that should win a visual sort. Vertex consistently uses the
     * highest inherited group weight for tab ordering and rank display.
     */
    private static Group highestWeightGroup(LuckPerms api, User user) {
        return user.getInheritedGroups(user.getQueryOptions()).stream()
                .filter(group -> !"default".equalsIgnoreCase(group.getName()))
                .max(Comparator.<Group>comparingInt(group -> group.getWeight().orElse(0))
                        .thenComparing(Group::getName, String.CASE_INSENSITIVE_ORDER))
                .orElse(null);
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
