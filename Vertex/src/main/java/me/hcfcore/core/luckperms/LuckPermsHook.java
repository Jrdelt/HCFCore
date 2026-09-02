package me.hcfcore.core.luckperms;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.TimeUnit;

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
    public static void grantTemporaryPermission(Player player, String permissionNode, long seconds) {
        if (!isAvailable()) {
            return;
        }
        grant(player, permissionNode, seconds);
    }

    private static void grant(Player player, String permissionNode, long seconds) {
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
        userManager.saveUser(user);
    }
}
