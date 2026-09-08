package me.vertex.core.auction;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/** Handles clicks in {@link AuctionMenu}'s browse and claim modes. */
public final class AuctionMenuListener implements Listener {

    private final AuctionManager manager;
    private final Messages messages;

    public AuctionMenuListener(AuctionManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AuctionMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AuctionMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof AuctionMenu.Holder;
        if (!clickedTop) {
            return;
        }

        switch (holder.mode()) {
            case BROWSE -> handleBrowseClick(player, holder, event);
            case CLAIM -> handleClaimClick(player, holder, event);
        }
    }

    private void handleBrowseClick(Player player, AuctionMenu.Holder holder, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == AuctionMenu.SLOT_HUB) {
            AuctionHubMenu.open(player, manager, messages);
            return;
        }
        if (slot == AuctionMenu.SLOT_SORT) {
            if (event.isShiftClick()) {
                holder.setSortDirection(holder.sortDirection().flip());
            } else {
                holder.setSortMode(holder.sortMode().next());
            }
            AuctionMenu.refresh(player, manager, messages, holder, holder.page());
            return;
        }
        if (slot == AuctionMenu.SLOT_CURRENCY) {
            holder.cycleCurrencyFilter();
            AuctionMenu.refresh(player, manager, messages, holder, holder.page());
            return;
        }
        if (slot == AuctionMenu.SLOT_PREV_PAGE) {
            AuctionMenu.refresh(player, manager, messages, holder, holder.page() - 1);
            return;
        }
        if (slot == AuctionMenu.SLOT_NEXT_PAGE) {
            AuctionMenu.refresh(player, manager, messages, holder, holder.page() + 1);
            return;
        }

        Integer listingId = holder.listingIdAtSlot(slot);
        if (listingId == null) {
            return;
        }
        AuctionListing listing = manager.getListing(listingId);
        if (listing == null) {
            player.sendMessage(messages.get(player, "auction.gone"));
            AuctionMenu.refresh(player, manager, messages, holder, holder.page());
            return;
        }

        if (event.isShiftClick()) {
            if (!manager.canCancel(player, listing)) {
                return;
            }
            boolean cancelled = manager.cancel(listing, player);
            player.sendMessage(messages.get(player, cancelled ? "auction.cancelled" : "auction.gone"));
            AuctionMenu.refresh(player, manager, messages, holder, holder.page());
            return;
        }

        if (event.isRightClick()) {
            boolean nowWatching = manager.toggleWatch(player.getUniqueId(), listingId);
            player.sendMessage(messages.get(player, nowWatching ? "auction.watch-added" : "auction.watch-removed"));
            AuctionMenu.refresh(player, manager, messages, holder, holder.page());
            return;
        }

        if (listing.sellerUuid().equals(player.getUniqueId())) {
            return;
        }
        AuctionManager.BuyResult result = manager.buy(listingId, player);
        if (result != AuctionManager.BuyResult.OK) {
            player.sendMessage(messages.get(player, buyFailureKey(result)));
        } else {
            player.sendMessage(messages.get(player, "auction.bought"));
        }
        AuctionMenu.refresh(player, manager, messages, holder, holder.page());
    }

    private void handleClaimClick(Player player, AuctionMenu.Holder holder, InventoryClickEvent event) {
        if (event.getRawSlot() != AuctionMenu.SLOT_CLAIM_ALL) {
            return;
        }
        java.util.List<ItemStack> items = holder.claimItems();
        if (items.isEmpty()) {
            return;
        }
        for (ItemStack item : items) {
            player.getInventory().addItem(item).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
        manager.clearClaims(player.getUniqueId());
        player.sendMessage(messages.get(player, "auction.claim-all-claimed"));
        player.closeInventory();
    }

    private static String buyFailureKey(AuctionManager.BuyResult result) {
        return switch (result) {
            case GONE -> "auction.gone";
            case IS_SELLER -> "auction.is-seller";
            case NO_ECONOMY -> "spawner.no-economy";
            case CANNOT_AFFORD -> "auction.cannot-afford";
            case OK -> "auction.bought";
        };
    }
}
