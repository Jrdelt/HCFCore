package me.vertex.core.storage;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Since {@code ClaimDeliveryGuard} was removed, marked handoff items can be
 * dropped and used like any other item. Nothing else clears the marker once
 * an item leaves a player's tracked inventory, so without this listener a
 * dropped or death-dropped marked item keeps blocking shard transfers, wand
 * use, and invsee for whoever (including the original owner) next holds it.
 */
public final class DeliveryMarkerCleanupListener implements Listener {
    private final Plugin plugin;

    public DeliveryMarkerCleanupListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ClaimDelivery.stripMarker(plugin, event.getItemDrop().getItemStack());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        // Only actual ground drops -- items kept via keepInventory or a
        // no-drop rule are still pending delivery and must stay marked so a
        // restart can't pay the same claim twice.
        for (ItemStack item : event.getDrops()) {
            ClaimDelivery.stripMarker(plugin, item);
        }
    }
}
