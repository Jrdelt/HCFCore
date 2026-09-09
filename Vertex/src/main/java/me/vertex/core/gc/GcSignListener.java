package me.vertex.core.gc;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Wires {@link GcSignPrompt} into the two Bukkit events its shared sign flow depends on. */
public final class GcSignListener implements Listener {

    private final GcSignPrompt prompt;

    public GcSignListener(GcSignPrompt prompt) {
        this.prompt = prompt;
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (prompt.handleSignChange(player, event.getBlock().getLocation(), event.getLine(0))) {
            event.setCancelled(true);
        }
    }

    /** A disconnect mid-edit must release the lock immediately rather than waiting out the full timeout. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompt.forceCancel(event.getPlayer().getUniqueId());
    }
}
