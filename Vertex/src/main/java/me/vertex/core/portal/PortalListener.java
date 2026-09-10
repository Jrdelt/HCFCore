package me.vertex.core.portal;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Bridges physical portal movement and the setup selector to PortalManager. */
public final class PortalListener implements Listener {
    private final PortalManager portals;

    public PortalListener(PortalManager portals) { this.portals = portals; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (portals.isFlying(player.getUniqueId())) {
            portals.releaseFlight(player, true);
            event.setCancelled(true);
            return;
        }
        if (!portals.isSelector(event.getItem())) return;
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                event.setCancelled(true);
                feedback(player, portals.isRouteSelecting(player.getUniqueId())
                        ? portals.addRoutePoint(player, event.getClickedBlock().getLocation().add(.5D, 1D, .5D))
                        : portals.selectCorner(player, event.getClickedBlock().getLocation(), true));
            }
            case RIGHT_CLICK_BLOCK -> {
                event.setCancelled(true);
                feedback(player, portals.isRouteSelecting(player.getUniqueId()) ? portals.removeRoutePoint(player)
                        : portals.selectCorner(player, event.getClickedBlock().getLocation(), false));
            }
            case LEFT_CLICK_AIR, RIGHT_CLICK_AIR -> {
                if (player.isSneaking()) {
                    event.setCancelled(true);
                    feedback(player, portals.finishSelection(player));
                }
            }
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null && !sameBlock(event)) portals.tryEnter(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && portals.isFlying(player.getUniqueId())) portals.releaseFlight(player, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (portals.isFlying(event.getPlayer().getUniqueId())) portals.releaseFlight(event.getPlayer(), false);
    }

    private static boolean sameBlock(PlayerMoveEvent event) {
        return event.getFrom().getWorld().equals(event.getTo().getWorld())
                && event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ();
    }

    private static void feedback(Player player, String result) {
        player.sendMessage("ok".equals(result) ? "§aPortal selection updated." : "§cPortal selection: " + result);
    }
}
