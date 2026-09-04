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
        // updatePlayerNametag pushes this player's own nametag out to
        // everyone (including themselves); applyAllNametagsTo populates
        // their brand-new scoreboard (from ScoreboardManager.setup(),
        // which already ran -- this listener is MONITOR priority) with
        // every other already-online player's nametag, which the
        // change-detection in updatePlayerNametag alone would skip since
        // those players' faction state hasn't "changed".
        nametagManager.applyAllNametagsTo(player);
        nametagManager.updatePlayerNametag(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        nametagManager.removePlayerNametag(player);
    }
}
