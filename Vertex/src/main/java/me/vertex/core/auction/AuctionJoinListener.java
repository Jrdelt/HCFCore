package me.vertex.core.auction;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Applies any experience owed from an experience-currency sale that settled while the seller was offline. */
public final class AuctionJoinListener implements Listener {

    private final AuctionManager manager;

    public AuctionJoinListener(AuctionManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.applyPendingExp(event.getPlayer());
    }
}
