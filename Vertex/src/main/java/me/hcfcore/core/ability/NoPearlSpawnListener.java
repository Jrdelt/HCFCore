package me.hcfcore.core.ability;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.worldguard.WorldGuardHook;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Stops a thrown ender pearl from crossing into or out of a protected zone
 * (spawn/safezone by default) -- otherwise a player being chased in combat
 * can pearl straight into safety, or pearl a fight straight into it from the
 * other side. HIGH priority, ahead of TimeWarpPearlListener's MONITOR-priority
 * origin recorder (ignoreCancelled = true there), so a blocked pearl is
 * never recorded as a valid warp-back point either.
 */
public final class NoPearlSpawnListener implements Listener {

    private final Plugin plugin;
    private final Messages messages;

    public NoPearlSpawnListener(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /**
     * Blocks the throw outright if the player is standing inside a
     * protected zone -- covers both "pearling out of a safezone" and,
     * combined with onPearl below checking the landing spot, "pearling
     * across the safezone/warzone boundary" in either direction.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player player)) {
            return;
        }
        if (FakePearlListener.isThrowingFakePearl(player.getUniqueId())) {
            return;
        }
        if (isProtected(plugin, player.getLocation())) {
            event.setCancelled(true);
            refund(player);
            player.sendMessage(messages.get(player, "combat.no-pearl-spawn"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearl(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        if (isProtected(plugin, event.getTo())) {
            event.setCancelled(true);
            refund(event.getPlayer());
            event.getPlayer().sendMessage(messages.get(event.getPlayer(), "combat.no-pearl-spawn"));
        }
    }

    /**
     * The pearl is already gone from the player's hand by the time either
     * handler above cancels it -- vanilla consumes it as part of throwing,
     * not as part of landing -- so blocking the throw/landing has to hand
     * one back explicitly or the player loses a pearl for nothing.
     */
    private static void refund(Player player) {
        ItemStack refund = new ItemStack(Material.ENDER_PEARL, 1);
        for (ItemStack dropped : player.getInventory().addItem(refund).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        }
    }

    /**
     * Shared with TimeWarpPearlListener -- a recorded pearl origin can sit
     * inside a protected zone from a much earlier, perfectly ordinary throw
     * (e.g. pearling out of spawn before a fight even starts), so the
     * recall needs the exact same check on its destination.
     */
    public static boolean isProtected(Plugin plugin, Location location) {
        Set<String> regions = Set.copyOf(plugin.getConfig().getStringList("pvp.no-pearl-regions"));
        if (WorldGuardHook.isInDisabledRegion(location, regions)) {
            return true;
        }
        Set<String> claims = Set.copyOf(plugin.getConfig().getStringList("pvp.no-pearl-claim-names"));
        return FactionsHook.isDisabledClaim(location, claims);
    }
}
