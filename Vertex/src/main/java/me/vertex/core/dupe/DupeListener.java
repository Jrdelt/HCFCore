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
        org.bukkit.inventory.Inventory top = event.getView().getTopInventory();
        if (isWorldContainer(top)) {
            manager.scanInventorySoon(top, "open-container");
        }
        manager.scheduleScan();
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        org.bukkit.inventory.Inventory top = event.getView().getTopInventory();
        if (isWorldContainer(top)) {
            manager.scanInventorySoon(top, "open-container");
        }
        manager.scheduleScan();
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        org.bukkit.inventory.Inventory inv = event.getInventory();
        if (isWorldContainer(inv)) {
            manager.scanInventorySoon(inv, "closed-container");
        }
        manager.scheduleScan();
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        manager.scanInventorySoon(event.getSource(), "hopper-source");
        manager.scanInventorySoon(event.getDestination(), "hopper-destination");
    }

    static boolean isWorldContainer(org.bukkit.inventory.Inventory inventory) {
        if (inventory == null) return false;
        org.bukkit.inventory.InventoryHolder holder = inventory.getHolder();
        return holder instanceof org.bukkit.block.Container
                || holder instanceof org.bukkit.block.DoubleChest
                || holder instanceof org.bukkit.entity.Vehicle
                || holder instanceof org.bukkit.entity.AbstractHorse;
    }
}
