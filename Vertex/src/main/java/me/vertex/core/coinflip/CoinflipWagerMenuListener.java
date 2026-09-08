package me.vertex.core.coinflip;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Handles item placement and Confirm/Cancel for {@link CoinflipWagerMenu}. */
public final class CoinflipWagerMenuListener implements Listener {

    private final CoinflipManager manager;
    private final Messages messages;

    public CoinflipWagerMenuListener(CoinflipManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof CoinflipWagerMenu.Holder
                && event.getRawSlots().stream().anyMatch(slot -> slot < event.getInventory().getSize() && !isGridSlot(slot))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof CoinflipWagerMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof CoinflipWagerMenu.Holder;
        if (!clickedTop) {
            return;
        }
        if (!isGridSlot(event.getRawSlot())) {
            event.setCancelled(true);
            if (event.getRawSlot() == CoinflipWagerMenu.SLOT_CONFIRM) {
                confirm(player, holder, event.getInventory());
            } else if (event.getRawSlot() == CoinflipWagerMenu.SLOT_CANCEL) {
                player.closeInventory();
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof CoinflipWagerMenu.Holder holder)
                || !(event.getPlayer() instanceof Player player) || holder.isResolved()) {
            return;
        }
        for (int slot = CoinflipWagerMenu.GRID_START; slot < CoinflipWagerMenu.GRID_START + CoinflipWagerMenu.GRID_SLOTS; slot++) {
            ItemStack item = event.getInventory().getItem(slot);
            if (item == null || item.isEmpty()) {
                continue;
            }
            player.getInventory().addItem(item).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    private static boolean isGridSlot(int slot) {
        return slot >= CoinflipWagerMenu.GRID_START && slot < CoinflipWagerMenu.GRID_START + CoinflipWagerMenu.GRID_SLOTS;
    }

    private void confirm(Player player, CoinflipWagerMenu.Holder holder, Inventory inventory) {
        List<ItemStack> gathered = new ArrayList<>();
        for (int slot = CoinflipWagerMenu.GRID_START; slot < CoinflipWagerMenu.GRID_START + CoinflipWagerMenu.GRID_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.isEmpty()) {
                gathered.add(item);
            }
        }
        if (gathered.isEmpty()) {
            player.sendMessage(messages.get(player, "coinflip.wager-empty"));
            return;
        }
        if (gathered.size() > manager.maxItemStacksPerWager()) {
            player.sendMessage(messages.get(player, "coinflip.wager-too-many-items",
                    "max", String.valueOf(manager.maxItemStacksPerWager())));
            return;
        }
        ItemStack[] items = gathered.toArray(new ItemStack[0]);

        if (holder.mode() == CoinflipWagerMenu.Mode.CREATE) {
            CoinflipManager.CreateOutcome outcome = manager.createItemsCoinflip(player, items, holder.targetUuid());
            if (outcome.result() != CoinflipManager.CreateResult.OK) {
                player.sendMessage(messages.get(player, createFailureKey(outcome.result())));
                return;
            }
            holder.markResolved();
            player.closeInventory();
            player.sendMessage(messages.get(player, "coinflip.wager-created"));
        } else {
            CoinflipManager.PlayResult result = manager.requestItemMatch(holder.joiningCoinflipId(), player, items);
            if (result != CoinflipManager.PlayResult.OK) {
                player.sendMessage(messages.get(player, playFailureKey(result)));
                return;
            }
            holder.markResolved();
            player.closeInventory();
            player.sendMessage(messages.get(player, "coinflip.item-match-awaiting-approval"));
        }
    }

    private static String createFailureKey(CoinflipManager.CreateResult result) {
        return switch (result) {
            case DISABLED -> "coinflip.disabled";
            case BANNED -> "coinflip.you-are-banned";
            case EMPTY_WAGER -> "coinflip.wager-empty";
            case TOO_MANY_ITEMS -> "coinflip.wager-too-many-items-generic";
            case ALREADY_HOSTING -> "coinflip.already-hosting";
            default -> "coinflip.create-failed";
        };
    }

    private static String playFailureKey(CoinflipManager.PlayResult result) {
        return switch (result) {
            case GONE -> "coinflip.play-gone";
            case BANNED -> "coinflip.you-are-banned";
            case IS_HOST -> "coinflip.play-is-host";
            case NOT_TARGETED -> "coinflip.play-not-targeted";
            case ALREADY_PENDING_MATCH -> "coinflip.item-match-already-pending";
            case ALREADY_TAKING_ONE -> "coinflip.already-taking-one";
            default -> "coinflip.create-failed";
        };
    }
}
