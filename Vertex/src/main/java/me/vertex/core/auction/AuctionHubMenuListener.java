package me.vertex.core.auction;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Routes clicks on {@link AuctionHubMenu}'s navigation icons to each sub-page. */
public final class AuctionHubMenuListener implements Listener {

    private final AuctionManager manager;
    private final Messages messages;

    public AuctionHubMenuListener(AuctionManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AuctionHubMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AuctionHubMenu.Holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof AuctionHubMenu.Holder;
        if (!clickedTop) {
            return;
        }

        switch (event.getRawSlot()) {
            case AuctionHubMenu.SLOT_CLOSE -> player.closeInventory();
            case AuctionHubMenu.SLOT_ACTIVE_LISTINGS -> AuctionMenu.openMyListings(player, manager, messages, 0);
            case AuctionHubMenu.SLOT_EXPIRED_ITEMS -> AuctionHistoryMenu.openExpiredItems(player, manager, messages, 0);
            case AuctionHubMenu.SLOT_COLLECTION_BOX -> AuctionMenu.openClaim(player, manager, messages);
            case AuctionHubMenu.SLOT_WATCHLIST -> AuctionMenu.openWatchlist(player, manager, messages, 0);
            case AuctionHubMenu.SLOT_HISTORY -> AuctionHistoryMenu.openHistory(player, manager, messages, 0);
            default -> { }
        }
    }
}
