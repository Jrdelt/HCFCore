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
            // An ordinary gray pane can otherwise merge into an identical
            // border stack. Shift-clicks are routed only to wager slots.
            if (event.isShiftClick()) {
                event.setCancelled(true);
                moveToGrid(event.getInventory(), event.getCurrentItem());
            }
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
        List<ItemStack> returns = new ArrayList<>();
        for (int slot = CoinflipWagerMenu.GRID_START;
                slot < CoinflipWagerMenu.GRID_START + CoinflipWagerMenu.GRID_SLOTS; slot++) {
            ItemStack item = event.getInventory().getItem(slot);
            if (item != null && !item.isEmpty()) returns.add(item.clone());
        }
        if (returns.isEmpty()) return;
        if (!manager.queueOverflow(player, returns, "coinflip-wager-return")) {
            player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
            org.bukkit.Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                if (player.isOnline()) player.openInventory(event.getInventory());
            });
            return;
        }
        for (int slot = CoinflipWagerMenu.GRID_START;
                slot < CoinflipWagerMenu.GRID_START + CoinflipWagerMenu.GRID_SLOTS; slot++) {
            event.getInventory().setItem(slot, null);
        }
    }

    private static boolean isGridSlot(int slot) {
        return slot >= CoinflipWagerMenu.GRID_START && slot < CoinflipWagerMenu.GRID_START + CoinflipWagerMenu.GRID_SLOTS;
    }

    private static void moveToGrid(Inventory inventory, ItemStack source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        ItemStack remaining = source.clone();
        for (int slot = CoinflipWagerMenu.GRID_START;
                slot < CoinflipWagerMenu.GRID_START + CoinflipWagerMenu.GRID_SLOTS && remaining.getAmount() > 0; slot++) {
            ItemStack current = inventory.getItem(slot);
            if (current == null || current.isEmpty() || !current.isSimilar(remaining)) {
                continue;
            }
            int moved = Math.min(current.getMaxStackSize() - current.getAmount(), remaining.getAmount());
            if (moved > 0) {
                current.setAmount(current.getAmount() + moved);
                remaining.setAmount(remaining.getAmount() - moved);
            }
        }
        for (int slot = CoinflipWagerMenu.GRID_START;
                slot < CoinflipWagerMenu.GRID_START + CoinflipWagerMenu.GRID_SLOTS && remaining.getAmount() > 0; slot++) {
            if (inventory.getItem(slot) != null && !inventory.getItem(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
            ItemStack placed = remaining.clone();
            placed.setAmount(moved);
            inventory.setItem(slot, placed);
            remaining.setAmount(remaining.getAmount() - moved);
        }
        source.setAmount(remaining.getAmount());
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
            case PERSIST_FAILED -> "coinflip.create-persist-failed";
            case RECOVERY_REQUIRED -> "coinflip.create-recovery-required";
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
