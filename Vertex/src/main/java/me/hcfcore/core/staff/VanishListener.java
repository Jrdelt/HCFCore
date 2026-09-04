package me.hcfcore.core.staff;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Applies vanish visibility whenever a player joins, and cleans up staff
 * mode state on quit. Vanish itself doesn't survive a rejoin -- see
 * {@link StaffManager}'s class doc -- so a vanished staff member who
 * disconnects becomes visible to everyone again next time they log in,
 * same as any other session-scoped toggle.
 */
public final class VanishListener implements Listener {

    private final StaffManager staffManager;

    public VanishListener(StaffManager staffManager) {
        this.staffManager = staffManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        staffManager.applyVanishToJoiningPlayer(player);
    }

    /**
     * A mob targeting and swinging at an invisible player is a dead
     * giveaway someone's there even though godmode absorbs the damage --
     * hidePlayer() only hides you from other players' clients, it has no
     * effect on mob AI at all.
     */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player && staffManager.isVanished(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (staffManager.isVanished(player.getUniqueId())) {
            event.quitMessage(null);
        }
        staffManager.forget(player.getUniqueId());
    }
}
