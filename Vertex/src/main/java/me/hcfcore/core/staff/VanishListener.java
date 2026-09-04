package me.hcfcore.core.staff;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (staffManager.isVanished(player.getUniqueId())) {
            event.quitMessage(null);
        }
        staffManager.forget(player.getUniqueId());
    }
}
