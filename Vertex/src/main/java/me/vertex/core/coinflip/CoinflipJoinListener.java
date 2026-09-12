package me.vertex.core.coinflip;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Applies durable payouts and result notifications owed while a player was offline. */
public final class CoinflipJoinListener implements Listener {

    private final CoinflipManager manager;

    public CoinflipJoinListener(CoinflipManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.processPendingPayouts(event.getPlayer().getUniqueId());
        manager.applyPendingResultNotifications(event.getPlayer());
    }
}
