package me.vertex.core.auction;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** {@link AuctionHistoryMenu} is read-only except for the Back button and pagination. */
public final class AuctionHistoryMenuListener implements Listener {

    private final AuctionManager manager;
    private final Messages messages;

    public AuctionHistoryMenuListener(AuctionManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AuctionHistoryMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AuctionHistoryMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof AuctionHistoryMenu.Holder;
        if (!clickedTop) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot == AuctionHistoryMenu.SLOT_BACK) {
            AuctionHubMenu.open(player, manager, messages);
        } else if (slot == AuctionHistoryMenu.SLOT_PREV_PAGE) {
            reopen(player, holder, holder.page() - 1);
        } else if (slot == AuctionHistoryMenu.SLOT_NEXT_PAGE) {
            reopen(player, holder, holder.page() + 1);
        }
    }

    private void reopen(Player player, AuctionHistoryMenu.Holder holder, int page) {
        if (holder.statusFilter() == AuctionLogEntry.Status.EXPIRED) {
            AuctionHistoryMenu.openExpiredItems(player, manager, messages, page);
        } else {
            AuctionHistoryMenu.openHistory(player, manager, messages, page);
        }
    }
}
