package me.hcfcore.core.staff;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory toggle state for staff mode features. Nothing here is
 * persisted -- every toggle resets to off on rejoin, same as combat tags
 * and other session-scoped state elsewhere in the plugin.
 */
public final class StaffManager {

    private final Plugin plugin;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Set<UUID> staffBuild = ConcurrentHashMap.newKeySet();
    private final Set<UUID> staffChat = ConcurrentHashMap.newKeySet();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    public StaffManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    public boolean isStaffBuild(UUID uuid) {
        return staffBuild.contains(uuid);
    }

    public boolean isStaffChat(UUID uuid) {
        return staffChat.contains(uuid);
    }

    public boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid);
    }

    /**
     * Toggles vanish and immediately applies visibility to every online
     * player: viewers with {@code hcfcore.staff.vanish} can still see the
     * player, everyone else can't. Returns the new state.
     */
    public boolean toggleVanish(Player player) {
        boolean nowVanished = !vanished.contains(player.getUniqueId());
        if (nowVanished) {
            vanished.add(player.getUniqueId());
        } else {
            vanished.remove(player.getUniqueId());
        }
        applyVanishState(player);
        return nowVanished;
    }

    /** Applies the current vanish state of `player` to every online viewer. */
    public void applyVanishState(Player player) {
        boolean hidden = vanished.contains(player.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) {
                continue;
            }
            if (hidden && !viewer.hasPermission("hcfcore.staff.vanish")) {
                viewer.hidePlayer(plugin, player);
            } else {
                viewer.showPlayer(plugin, player);
            }
        }
        if (hidden) {
            clearMobTargets(player);
        }
    }

    /**
     * Stops any mob already mid-chase from continuing to visibly swing at
     * an invisible player -- {@link VanishListener#onTarget} only catches
     * *new* targeting attempts, not a target a mob picked up before this
     * player vanished.
     */
    private void clearMobTargets(Player player) {
        for (Entity entity : player.getNearbyEntities(64, 64, 64)) {
            if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                mob.setTarget(null);
            }
        }
    }

    /**
     * Called when `viewer` joins, so any already-vanished players are hidden
     * from them immediately (unless they can see vanished players too).
     */
    public void applyVanishToJoiningPlayer(Player viewer) {
        if (viewer.hasPermission("hcfcore.staff.vanish")) {
            return;
        }
        for (UUID hiddenId : vanished) {
            Player hidden = Bukkit.getPlayer(hiddenId);
            if (hidden != null && !hidden.equals(viewer)) {
                viewer.hidePlayer(plugin, hidden);
            }
        }
    }

    public boolean toggleStaffBuild(Player player) {
        boolean now = !staffBuild.contains(player.getUniqueId());
        setStaffBuild(player, now);
        return now;
    }

    public void setStaffBuild(Player player, boolean value) {
        if (value) {
            staffBuild.add(player.getUniqueId());
        } else {
            staffBuild.remove(player.getUniqueId());
        }
    }

    public void setVanish(Player player, boolean value) {
        if (value) {
            vanished.add(player.getUniqueId());
        } else {
            vanished.remove(player.getUniqueId());
        }
        applyVanishState(player);
    }

    /**
     * Toggles full staff mode: vanish, staff-build, godmode, and flight
     * together. Only counts as "already on" when vanish and staff-build
     * both are -- so if a player turned one off individually via /vanish
     * or /staffbuild, this turns everything back on rather than
     * surprising them by finishing the job of turning it off.
     */
    public boolean toggleStaffMode(Player player) {
        boolean bothOn = isVanished(player.getUniqueId()) && isStaffBuild(player.getUniqueId());
        boolean nowOn = !bothOn;
        setVanish(player, nowOn);
        setStaffBuild(player, nowOn);
        player.setInvulnerable(nowOn);
        player.setAllowFlight(nowOn);
        player.setFlying(nowOn);
        return nowOn;
    }

    /**
     * Toggles freeze: a frozen player can't move, break/place blocks,
     * interact, deal or take damage, drop items, click their inventory, or
     * run commands -- see {@link FreezeListener}. Leaving the server while
     * frozen is a separate, harsher consequence handled by
     * {@link FreezeListener#onQuit}, not this method.
     */
    public boolean toggleFreeze(Player player) {
        boolean now = !frozen.contains(player.getUniqueId());
        if (now) {
            frozen.add(player.getUniqueId());
        } else {
            frozen.remove(player.getUniqueId());
        }
        return now;
    }

    public boolean toggleStaffChat(Player player) {
        boolean now = !staffChat.contains(player.getUniqueId());
        if (now) {
            staffChat.add(player.getUniqueId());
        } else {
            staffChat.remove(player.getUniqueId());
        }
        return now;
    }

    public void forget(UUID uuid) {
        vanished.remove(uuid);
        staffBuild.remove(uuid);
        staffChat.remove(uuid);
        frozen.remove(uuid);
    }
}
