package me.hcfcore.core.nametag;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Handles nametag cleanup on player quit.
 * Nametags are automatically created/updated by the NametagManager update task.
 */
public final class NametagListener implements Listener {

    private final NametagManager nametagManager;

    public NametagListener(NametagManager nametagManager) {
        this.nametagManager = nametagManager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        nametagManager.removePlayerNametag(player);
    }
}
