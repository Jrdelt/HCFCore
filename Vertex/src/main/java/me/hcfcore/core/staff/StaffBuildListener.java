package me.hcfcore.core.staff;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Bypasses FactionsUUID's claim protection for players with staff-build
 * on. Runs at MONITOR so claim protection (which listens at a normal
 * priority) has already run and cancelled the event -- this just
 * un-cancels it afterward, rather than trying to duplicate or pre-empt
 * whatever claim logic FactionsUUID applies. Covers block break/place,
 * container/door access, buckets, item frames/paintings, and entity
 * interaction (armor stands etc.) -- the full set of things a claim
 * typically protects.
 */
public final class StaffBuildListener implements Listener {

    private final StaffManager staffManager;

    public StaffBuildListener(StaffManager staffManager) {
        this.staffManager = staffManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        if (event.isCancelled() && bypasses(event.getPlayer())) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        if (event.isCancelled() && bypasses(event.getPlayer())) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (!bypasses(event.getPlayer())) {
            return;
        }
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.ALLOW);
        }
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.isCancelled() && bypasses(event.getPlayer())) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (event.isCancelled() && event.getRemover() instanceof Player player && bypasses(player)) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.isCancelled() && bypasses(event.getPlayer())) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (event.isCancelled() && bypasses(event.getPlayer())) {
            event.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.isCancelled() && event.getPlayer() instanceof Player player && bypasses(player)) {
            event.setCancelled(false);
        }
    }

    private boolean bypasses(Player player) {
        return staffManager.isStaffBuild(player.getUniqueId());
    }
}
