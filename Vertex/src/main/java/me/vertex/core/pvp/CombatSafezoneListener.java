package me.vertex.core.pvp;

import me.vertex.core.ability.NoPearlSpawnListener;
import me.vertex.core.lang.Messages;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/**
 * Stops a combat-tagged player from walking or teleporting into a
 * protected zone (spawn/safezone, same list as {@code pvp.no-pearl-*} --
 * see {@link NoPearlSpawnListener#isProtected}) to escape a fight. An
 * untagged player is completely unaffected; this only ever fires for
 * someone currently in combat.
 *
 * <p>Ender pearl teleports are deliberately skipped here --
 * {@link NoPearlSpawnListener} already owns that path end-to-end
 * (including refunding the pearl), and blocking it a second time here
 * would just duplicate that handling.
 */
public final class CombatSafezoneListener implements Listener {

    private final Plugin plugin;
    private final CombatManager combatManager;
    private final Messages messages;

    public CombatSafezoneListener(Plugin plugin, CombatManager combatManager, Messages messages) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ())) {
            // Only a look-around, not an actual block-to-block move -- skip
            // the (relatively expensive) claim/region check on every packet.
            return;
        }
        if (!blocksSafezoneEntry() || !shouldBlock(event.getPlayer(), to)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.get(event.getPlayer(), "combat.safezone-blocked"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        if (!blocksSafezoneEntry() || !shouldBlock(event.getPlayer(), event.getTo())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.get(event.getPlayer(), "combat.safezone-blocked"));
    }

    private boolean blocksSafezoneEntry() {
        return plugin.getConfig().getBoolean("pvp.combat-blocks-safezone-entry", true);
    }

    private boolean shouldBlock(Player player, Location destination) {
        if (destination == null || player.hasPermission("vertex.combat.safezone.bypass")) {
            return false;
        }
        if (!combatManager.isTagged(player.getUniqueId())) {
            return false;
        }
        return NoPearlSpawnListener.isProtected(plugin, destination);
    }
}
