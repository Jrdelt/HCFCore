package me.vertex.core.coinflip;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * Handles the Accept/Deny buttons in {@link CoinflipMatchReviewMenu}, plus
 * closing it any other way (Escape, opening something else) -- which counts
 * as a Deny too, not "still pending". Every other slot is read-only display.
 */
public final class CoinflipMatchReviewMenuListener implements Listener {

    private final CoinflipManager manager;
    private final Messages messages;

    public CoinflipMatchReviewMenuListener(CoinflipManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CoinflipMatchReviewMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CoinflipMatchReviewMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof CoinflipMatchReviewMenu.Holder;
        if (!clickedTop) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot == CoinflipMatchReviewMenu.SLOT_CONFIRM) {
            CoinflipManager.ApproveOutcome outcome = manager.approveItemMatch(holder.coinflipId(), player);
            holder.markResolved();
            player.closeInventory();
            if (outcome.result() != CoinflipManager.ApprovalResult.OK) {
                player.sendMessage(messages.get(player, approvalFailureKey(outcome.result())));
            }
        } else if (slot == CoinflipMatchReviewMenu.SLOT_DENY) {
            holder.markResolved();
            denyAndNotify(holder, player);
            player.closeInventory();
        }
    }

    /**
     * Closing the review GUI any other way -- Escape, opening a different
     * inventory, disconnecting -- is treated exactly like clicking Deny:
     * the opponent shouldn't have their items stuck in limbo just because
     * the host looked, decided against it, and walked away instead of
     * clicking the red dye.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CoinflipMatchReviewMenu.Holder holder)
                || holder.isResolved() || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        holder.markResolved();
        denyAndNotify(holder, player);
    }

    private void denyAndNotify(CoinflipMatchReviewMenu.Holder holder, Player player) {
        CoinflipManager.ApprovalResult result = manager.denyItemMatch(holder.coinflipId(), player);
        if (result != CoinflipManager.ApprovalResult.OK) {
            player.sendMessage(messages.get(player, approvalFailureKey(result)));
            return;
        }
        player.sendMessage(messages.get(player, "coinflip.item-match-you-denied"));
    }

    private static String approvalFailureKey(CoinflipManager.ApprovalResult result) {
        return switch (result) {
            case NOT_HOST -> "general.no-permission";
            case NO_PENDING_MATCH -> "coinflip.item-match-none-pending";
            case GONE, OK -> "coinflip.play-gone";
        };
    }
}
