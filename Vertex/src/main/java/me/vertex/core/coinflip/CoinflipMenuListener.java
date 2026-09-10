package me.vertex.core.coinflip;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/** Handles clicks in {@link CoinflipMenu}'s browse, claim, and view-items modes. */
public final class CoinflipMenuListener implements Listener {

    private final CoinflipManager manager;
    private final Messages messages;

    public CoinflipMenuListener(CoinflipManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CoinflipMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CoinflipMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof CoinflipMenu.Holder;
        if (!clickedTop) {
            return;
        }

        switch (holder.mode()) {
            case BROWSE -> handleBrowseClick(player, holder, event);
            case CLAIM -> handleClaimClick(player, holder, event);
            case VIEW_ITEMS -> {
                // Read-only.
            }
        }
    }

    private void handleBrowseClick(Player player, CoinflipMenu.Holder holder, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == CoinflipMenu.SLOT_CLAIM) {
            CoinflipMenu.openClaim(player, manager, messages);
            return;
        }
        if (slot == CoinflipMenu.SLOT_SELF_BAN) {
            handleSelfBanClick(player);
            return;
        }
        if (slot == CoinflipMenu.SLOT_HELP) {
            return;
        }
        if (slot == CoinflipMenu.SLOT_PREV_PAGE) {
            CoinflipMenu.openBrowse(player, manager, messages, holder.page() - 1);
            return;
        }
        if (slot == CoinflipMenu.SLOT_NEXT_PAGE) {
            CoinflipMenu.openBrowse(player, manager, messages, holder.page() + 1);
            return;
        }

        Integer coinflipId = holder.coinflipIdAtSlot(slot);
        if (coinflipId == null) {
            return;
        }
        Coinflip coinflip = manager.getCoinflip(coinflipId);
        if (coinflip == null) {
            player.sendMessage(messages.get(player, "coinflip.play-gone"));
            CoinflipMenu.openBrowse(player, manager, messages, holder.page());
            return;
        }

        if (event.isShiftClick()) {
            if (!manager.canCancel(player, coinflip)) {
                return;
            }
            boolean cancelled = manager.cancel(coinflip, player);
            player.sendMessage(messages.get(player, cancelled ? "coinflip.cancelled" : "coinflip.play-gone"));
            CoinflipMenu.openBrowse(player, manager, messages, holder.page());
            return;
        }
        if (event.isRightClick()) {
            if (coinflip.type() == CoinflipType.ITEMS && coinflip.items().length > 1) {
                CoinflipMenu.openViewItems(player, messages, coinflip);
            }
            return;
        }

        if (coinflip.hostUuid().equals(player.getUniqueId())) {
            player.sendMessage(messages.get(player, "coinflip.play-is-host"));
            return;
        }
        if (coinflip.type() == CoinflipType.ITEMS) {
            if (manager.hasPendingItemMatch(coinflipId)) {
                player.sendMessage(messages.get(player, "coinflip.item-match-already-pending"));
                return;
            }
            player.closeInventory();
            CoinflipWagerMenu.openForJoin(player, messages, coinflipId);
            return;
        }

        CoinflipManager.PlayOutcome outcome = manager.play(coinflipId, player);
        if (outcome.result() != CoinflipManager.PlayResult.OK) {
            player.sendMessage(messages.get(player, playFailureKey(outcome.result())));
            CoinflipMenu.openBrowse(player, manager, messages, holder.page());
        }
        // On success, manager.play() has already opened the shared coinflip
        // animation for both players -- reopening Browse here would instantly
        // replace it, since a player can only have one inventory open at a time.
    }

    private void handleSelfBanClick(Player player) {
        if (manager.isBanned(player.getUniqueId())) {
            if (manager.liftBanIfExpired(player.getUniqueId())) {
                player.sendMessage(messages.get(player, "coinflip.self-ban-lifted"));
            } else {
                player.sendMessage(messages.get(player, "coinflip.self-ban-remaining",
                        "time", formatDuration(manager.banRemainingMillis(player.getUniqueId()))));
            }
            player.closeInventory();
            return;
        }
        manager.requestBanConfirmation(player.getUniqueId());
        player.closeInventory();
        player.sendMessage(messages.get(player, "coinflip.self-ban-confirm-prompt"));
    }

    private static String formatDuration(long millis) {
        long days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(millis);
        long hours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(millis) % 24;
        return days + "d " + hours + "h";
    }

    private void handleClaimClick(Player player, CoinflipMenu.Holder holder, InventoryClickEvent event) {
        if (event.getRawSlot() != CoinflipMenu.SLOT_CLAIM_ALL) {
            return;
        }
        manager.takeClaims(player.getUniqueId()).whenComplete((batch, error) ->
                org.bukkit.Bukkit.getScheduler().runTask(
                        org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(CoinflipMenuListener.class), () -> {
                            if (error != null || batch == null) {
                                return;
                            }
                            if (!player.isOnline()) {
                                manager.restoreClaimBatch(player.getUniqueId(), batch);
                                return;
                            }
                            if (batch.items().isEmpty()) {
                                return;
                            }
                            for (ItemStack item : batch.items()) {
                                player.getInventory().addItem(item).values().forEach(leftover ->
                                        player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                            }
                            player.sendMessage(messages.get(player, "coinflip.claim-all-claimed"));
                            player.closeInventory();
                        }));
    }

    private static String playFailureKey(CoinflipManager.PlayResult result) {
        return switch (result) {
            case GONE -> "coinflip.play-gone";
            case BANNED -> "coinflip.you-are-banned";
            case IS_HOST -> "coinflip.play-is-host";
            case NOT_TARGETED -> "coinflip.play-not-targeted";
            case CANNOT_AFFORD -> "coinflip.play-cannot-afford";
            case NEEDS_ITEM_WAGER -> "coinflip.play-gone";
            default -> "coinflip.create-failed";
        };
    }
}
