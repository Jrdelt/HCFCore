package me.hcfcore.core.ability;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.worldguard.WorldGuardHook;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Stops a thrown ender pearl from landing a player inside a protected zone
 * (spawn/safezone by default) -- otherwise a player being chased in combat
 * can pearl straight into safety. HIGH priority, ahead of
 * TimeWarpPearlListener's MONITOR-priority origin recorder (ignoreCancelled
 * = true there), so a blocked pearl is never recorded as a valid warp-back
 * point either.
 */
public final class NoPearlSpawnListener implements Listener {

    private final Plugin plugin;
    private final Messages messages;

    public NoPearlSpawnListener(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPearl(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        if (isProtected(plugin, event.getTo())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(messages.get(event.getPlayer(), "combat.no-pearl-spawn"));
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
