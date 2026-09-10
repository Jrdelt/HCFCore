package me.vertex.core.dupe;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/** Queues inexpensive authoritative ID scans after item movement has settled. */
public final class DupeListener implements Listener {
    private final DupeManager manager;

    public DupeListener(DupeManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.scheduleScan();
        manager.notifyStaffOnJoin(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        manager.scheduleScan();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        manager.scheduleScan();
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        manager.scheduleScan();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        // The actual scan is batched by DupeManager; this just makes newly
        // available, previously unopened inventories eligible promptly.
        manager.scheduleScan();
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        manager.scanInventorySoon(event.getView().getTopInventory(), "open-container");
        manager.scheduleScan();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        manager.scanInventorySoon(event.getView().getTopInventory(), "open-container");
        manager.scheduleScan();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        manager.scanInventorySoon(event.getInventory(), "closed-container");
        manager.scheduleScan();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        manager.scanInventorySoon(event.getSource(), "hopper-source");
        manager.scanInventorySoon(event.getDestination(), "hopper-destination");
    }
}
