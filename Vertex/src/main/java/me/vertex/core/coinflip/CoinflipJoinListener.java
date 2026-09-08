package me.vertex.core.coinflip;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Applies any experience a player is owed from a coinflip that was refunded/won while they were offline. */
public final class CoinflipJoinListener implements Listener {

    private final CoinflipManager manager;

    public CoinflipJoinListener(CoinflipManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.applyPendingExp(event.getPlayer());
    }
}
