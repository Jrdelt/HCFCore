package me.vertex.core.nametag;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;

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

        // NametagManager registers one team per (viewer, subject) pair on
        // *the viewer's own* scoreboard, specifically so the same subject
        // can show a different relation color to different viewers at
        // once. That only works if every player actually has a distinct
        // Scoreboard object -- left on Bukkit's single shared main
        // scoreboard (the default for a freshly-joined player), every
        // viewer's team calls collide on the same object and each
        // overwrites the last, so everyone ends up seeing whichever
        // relation color was computed for the most recently processed
        // viewer. Giving each player their own blank scoreboard here is
        // the only thing that makes per-viewer nametag colors possible.
        if (player.getScoreboard() == Bukkit.getScoreboardManager().getMainScoreboard()) {
            Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(board);
        }

        // updatePlayerNametag pushes this player's own nametag out to
        // everyone (including themselves); applyAllNametagsTo populates
        // this player's scoreboard with every other already-online
        // player's nametag, which the change-detection in
        // updatePlayerNametag alone would skip since those players'
        // faction state hasn't "changed".
        nametagManager.applyAllNametagsTo(player);
        nametagManager.updatePlayerNametag(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        nametagManager.removePlayerNametag(player);
    }
}
