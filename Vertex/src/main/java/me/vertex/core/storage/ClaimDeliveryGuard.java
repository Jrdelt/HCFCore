package me.vertex.core.storage;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Keeps marked handoff items in the player's inventory until SQL acknowledgement. */
public final class ClaimDeliveryGuard implements Listener {
    private final Plugin plugin;

    public ClaimDeliveryGuard(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (ClaimDelivery.isMarked(plugin, event.getCurrentItem())
                || ClaimDelivery.isMarked(plugin, event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player player && event.getHotbarButton() >= 0
                && ClaimDelivery.isMarked(plugin, player.getInventory().getItem(event.getHotbarButton()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (ClaimDelivery.isMarked(plugin, event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (ClaimDelivery.isMarked(plugin, event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && ClaimDelivery.hasMarkedItem(plugin, player)) {
            // A collection-box delivery is acknowledged asynchronously. Do
            // not permit a death to split ownership between SQL and drops in
            // that very small handoff window.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        // If death lands inside the acknowledgement window, keep SQL as the
        // authority and let the same reservation reconcile after respawn.
        event.getDrops().removeIf(item -> ClaimDelivery.isMarked(plugin, item));
    }
}
