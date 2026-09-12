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
import org.bukkit.inventory.ItemStack;

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
        ItemStack held = event.getItem();
        if (held == null) held = player.getInventory().getItemInMainHand();
        if (!portals.isSelector(held)) return;
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                if (event.getClickedBlock() == null) return;
                event.setCancelled(true);
                boolean route = portals.isRouteSelecting(player.getUniqueId());
                String result = route
                        ? portals.addRoutePoint(player, event.getClickedBlock().getLocation().add(.5D, 1D, .5D))
                        : portals.selectCorner(player, event.getClickedBlock().getLocation(), true);
                if (route) feedback(player, result);
                else portalCornerFeedback(player, result, true);
            }
            case RIGHT_CLICK_BLOCK -> {
                if (event.getClickedBlock() == null) return;
                event.setCancelled(true);
                boolean route = portals.isRouteSelecting(player.getUniqueId());
                String result = route ? portals.removeRoutePoint(player)
                        : portals.selectCorner(player, event.getClickedBlock().getLocation(), false);
                if (route) feedback(player, result);
                else portalCornerFeedback(player, result, false);
            }
            case LEFT_CLICK_AIR, RIGHT_CLICK_AIR -> {
                if (player.isSneaking()) {
                    event.setCancelled(true);
                    boolean route = portals.isRouteSelecting(player.getUniqueId());
                    String id = portals.selectedPortalId(player.getUniqueId());
                    PortalTarget target = portals.selectedTarget(player.getUniqueId());
                    String result = portals.finishSelection(player);
                    if (route) feedback(player, result);
                    else portalSaveFeedback(player, result, id, target);
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

    private void portalCornerFeedback(Player player, String result, boolean first) {
        if (!"ok".equals(result)) {
            String key = "world".equals(result) ? "portals.selection-world-mismatch" : "portals.selection-error";
            player.sendMessage(messages.get(player, key, "reason", result));
            return;
        }
        org.bukkit.Location location = portals.selectedCorner(player.getUniqueId(), first);
        if (location == null) {
            player.sendMessage(messages.get(player, "portals.selection-error", "reason", "missing-corner"));
            return;
        }
        player.sendMessage(messages.get(player, first ? "portals.selection-first" : "portals.selection-second",
                "x", String.valueOf(location.getBlockX()), "y", String.valueOf(location.getBlockY()),
                "z", String.valueOf(location.getBlockZ())));
    }

    private void portalSaveFeedback(Player player, String result, String id, PortalTarget target) {
        if ("ok".equals(result)) {
            player.sendMessage(messages.get(player, "portals.created", "name", id,
                    "target", target == null ? "destination" : target.displayName()));
            return;
        }
        String key = "incomplete".equals(result) ? "portals.selection-incomplete" : "portals.selection-save-failed";
        player.sendMessage(messages.get(player, key, "reason", result));
    }
}
