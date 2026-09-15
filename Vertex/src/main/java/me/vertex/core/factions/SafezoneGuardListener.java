package me.vertex.core.factions;

import me.vertex.core.lang.Messages;
import me.vertex.core.pvp.CombatManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Two related SafeZone anti-exploit rules, neither of which existed before:
 *
 * <p>1. A combat-tagged player is denied entry outright -- the exact move
 * that would cross from non-SafeZone into SafeZone is cancelled, with a
 * message, rather than let them duck into a no-PvP claim mid-fight.
 *
 * <p>2. Anyone already inside a SafeZone within {@link #BORDER_PUSH_DISTANCE}
 * blocks of its edge is nudged back toward the interior. Standing exactly on
 * the line let a player's hitbox reach past the border while the server
 * still treated their feet location as protected -- hittable outward,
 * unhittable inward. Pushing border-huggers inward removes that gap instead
 * of trying to detect and special-case the straddling hitbox itself.
 */
public final class SafezoneGuardListener implements Listener {

    private static final double BORDER_PUSH_DISTANCE = 1.5D;
    private static final double BORDER_PUSH_STRENGTH = 3.0D;
    private static final Vector[] CARDINAL_DIRECTIONS = {
            new Vector(1, 0, 0), new Vector(-1, 0, 0), new Vector(0, 0, 1), new Vector(0, 0, -1)
    };

    private final Plugin plugin;
    private final FactionService factions;
    private final CombatManager combat;
    private final Messages messages;

    public SafezoneGuardListener(Plugin plugin, FactionService factions, CombatManager combat, Messages messages) {
        this.plugin = plugin;
        this.factions = factions;
        this.combat = combat;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            return;
        }
        Player player = event.getPlayer();
        boolean enteringSafezone = !factions.isSystemProtectedZone(from) && factions.isSystemProtectedZone(to);
        if (enteringSafezone && combat.isTagged(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "factions.safezone-entry-denied"));
            return;
        }
        if (!factions.isSystemProtectedZone(to)) {
            return;
        }
        Vector push = borderPushVector(to);
        if (push != null) {
            player.setVelocity(player.getVelocity().add(push));
        }
    }

    /** @return a push vector toward the SafeZone's interior, or null if not near any edge. */
    private Vector borderPushVector(Location inside) {
        for (Vector direction : CARDINAL_DIRECTIONS) {
            Location probe = inside.clone().add(direction.getX() * BORDER_PUSH_DISTANCE, 0D,
                    direction.getZ() * BORDER_PUSH_DISTANCE);
            if (!factions.isSystemProtectedZone(probe)) {
                return new Vector(-direction.getX(), 0D, -direction.getZ())
                        .normalize().multiply(BORDER_PUSH_STRENGTH).setY(0.2D);
            }
        }
        return null;
    }
}
