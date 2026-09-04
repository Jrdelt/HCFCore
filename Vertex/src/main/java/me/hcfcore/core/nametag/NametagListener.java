package me.hcfcore.core.nametag;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Handles nametag creation on join and cleanup on quit.
 * Nametags are updated immediately on join and periodically by the NametagManager update task.
 */
public final class NametagListener implements Listener {

    private final NametagManager nametagManager;

    public NametagListener(NametagManager nametagManager) {
        this.nametagManager = nametagManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        nametagManager.updatePlayerNametag(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        nametagManager.removePlayerNametag(player);
    }
}
