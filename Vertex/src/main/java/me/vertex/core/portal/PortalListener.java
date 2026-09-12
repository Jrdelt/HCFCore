package me.vertex.core.portal;

import me.vertex.core.lang.Messages;
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
    private final Messages messages;

    public PortalListener(PortalManager portals, Messages messages) { this.portals = portals; this.messages = messages; }

    /**
     * This must receive cancelled interaction events. Essentials and other
     * item listeners commonly cancel air clicks before the portal selector
     * gets here; ignoring those events made sneak-air save appear unreliable.
     * The selector is identified by its PDC before this listener changes the
     * event, so accepting cancelled events does not affect ordinary items.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
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
                if (event.getClickedBlock() == null) return;
                event.setCancelled(true);
                feedback(player, portals.isRouteSelecting(player.getUniqueId())
                        ? portals.addRoutePoint(player, event.getClickedBlock().getLocation().add(.5D, 1D, .5D))
                        : portals.selectCorner(player, event.getClickedBlock().getLocation(), true));
            }
            case RIGHT_CLICK_BLOCK -> {
                if (event.getClickedBlock() == null) return;
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
        // Do not restrict this to block changes. A one-block portal can be
        // entered or triggered by a jump while the player's feet remain in
        // the same block, and the movement event is the only reliable signal
        // for that case. PortalManager applies its own activation cooldown.
        if (event.getTo() != null) portals.tryEnter(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && portals.isFlying(player.getUniqueId())) portals.releaseFlight(player, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (portals.isFlying(event.getPlayer().getUniqueId())) portals.releaseFlight(event.getPlayer(), false);
    }

    private void feedback(Player player, String result) {
        player.sendMessage(messages.get(player, "ok".equals(result) ? "portals.selection-updated" : "portals.selection-error",
                "reason", result));
    }
}
