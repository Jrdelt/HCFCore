package me.vertex.core.coinflip;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Applies any experience and result notification a player is owed from a coinflip while they were offline. */
public final class CoinflipJoinListener implements Listener {

    private final CoinflipManager manager;

    public CoinflipJoinListener(CoinflipManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.applyPendingExp(event.getPlayer());
        manager.applyPendingResultNotifications(event.getPlayer());
    }
}
