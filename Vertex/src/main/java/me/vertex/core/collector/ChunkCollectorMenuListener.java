package me.vertex.core.collector;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.staff.StaffManager;
import me.vertex.core.util.ChatAmountPrompt;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Handles collector withdrawals and upgrades.
 *
 * <p>The withdrawal amount is typed in chat via {@link ChatAmountPrompt},
 * not an anvil GUI. An anvil's rename-text field was tried first, but
 * reading it back reliably at click time turned out to depend on several
 * pieces of vanilla anvil state (repair cost, the result item's own PDC,
 * whether the field had just been reset by the client taking the result)
 * that never converged on something dependably correct across clients.
 * Chat has none of that: a message is either delivered to the listener or
 * it isn't, with no client-side container state to fall out of sync with.
 */
public final class ChunkCollectorMenuListener implements Listener {

    private final ChunkCollectorManager manager;
    private final StaffManager staffManager;
    private final Messages messages;
    private final RallyManager rolePermissions;
    private final ChatAmountPrompt chatAmountPrompt;

    public ChunkCollectorMenuListener(ChunkCollectorManager manager, StaffManager staffManager, Messages messages,
            RallyManager rolePermissions, ChatAmountPrompt chatAmountPrompt) {
        this.manager = manager;
        this.staffManager = staffManager;
        this.messages = messages;
        this.rolePermissions = rolePermissions;
        this.chatAmountPrompt = chatAmountPrompt;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ChunkCollectorMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object rawHolder = event.getInventory().getHolder();
        if (!(rawHolder instanceof ChunkCollectorMenu.Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !(event.getClickedInventory().getHolder() instanceof ChunkCollectorMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Location location = holder.location();
        ChunkCollectorData data = manager.readData(location);
        if (data == null) {
            player.closeInventory();
            return;
        }
        if (!canAccess(player, location, data, "collector-open")) {
            player.closeInventory();
            return;
        }

        if (event.getSlot() == ChunkCollectorMenu.UPGRADE_SLOT) {
            if (!canAccess(player, location, data, "collector-upgrade")) {
                return;
            }
            upgrade(player, location, data);
            // Keep the collector open after an upgrade attempt so the player
            // can immediately buy another tier; reopening also refreshes the
            // tier, capacity, and next-upgrade cost shown by the GUI.
            ChunkCollectorMenu.open(player, manager, messages, location, data);
            return;
        } else if (event.getRawSlot() < ChunkCollectorMenu.SUMMARY_SLOT) {
            if (!canAccess(player, location, data, "collector-withdraw")) {
                return;
            }
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) {
                return;
            }
            if (event.isShiftClick()) {
                withdraw(player, location, data, clicked.getType(), manager.shiftWithdrawAmount());
                refresh(player, location);
            } else {
                promptForAmount(player, location, clicked.getType(), data.stored(clicked.getType()));
            }
        }
    }

    private void promptForAmount(Player player, Location location, Material material, long stored) {
        player.closeInventory();
        chatAmountPrompt.request(player,
                messages.get(player, "collector.withdraw-chat-prompt",
                        "item", material.name(), "available", String.format("%,d", stored)),
                amount -> handleTypedWithdrawal(player, location, material, amount),
                () -> { });
    }

    private void handleTypedWithdrawal(Player player, Location location, Material material, long amount) {
        ChunkCollectorData data = manager.readData(location);
        if (data == null) {
            return;
        }
        if (!canAccess(player, location, data, "collector-withdraw")) {
            return;
        }
        long stored = data.stored(material);
        if (amount > stored) {
            player.sendMessage(messages.get(player, "collector.withdraw-too-many",
                    "available", String.format("%,d", stored)));
            return;
        }
        withdraw(player, location, data, material, amount);
        refresh(player, location);
    }

    private boolean canAccess(Player player, Location location, ChunkCollectorData data, String action) {
        if (staffManager.isStaffBuild(player.getUniqueId()) && player.hasPermission("vertex.collector.bypass")) {
            return true;
        }
        String claimTag = FactionsHook.getClaimFactionTag(location);
        String playerTag = FactionsHook.getFactionTag(player);
        boolean mayUse = (claimTag != null && claimTag.equalsIgnoreCase(playerTag))
                || (claimTag == null && data.ownerFactionTag() != null
                        && data.ownerFactionTag().equalsIgnoreCase(playerTag));
        if (mayUse) {
            if (!rolePermissions.canUse(player, action)) {
                player.sendMessage(messages.get(player, "collector.open-permission-denied"));
                return false;
            }
            return true;
        }
        player.sendMessage(messages.get(player, "collector.cannot-access"));
        return false;
    }

    private void refresh(Player player, Location location) {
        ChunkCollectorData refreshed = manager.readData(location);
        if (refreshed != null && player.isOnline()) {
            ChunkCollectorMenu.open(player, manager, messages, location, refreshed);
        }
    }

    private void withdraw(Player player, Location location, ChunkCollectorData data, Material material, long requestedAmount) {
        long stored = data.stored(material);
        if (stored <= 0) {
            return;
        }
        long toWithdraw = Math.min(stored, requestedAmount);
        if (!canFullyFit(player, material, toWithdraw)) {
            player.sendMessage(messages.get(player, "collector.inventory-full"));
            return;
        }
        long remaining = toWithdraw;
        while (remaining > 0) {
            int batch = (int) Math.min(remaining, material.getMaxStackSize());
            if (!player.getInventory().addItem(new ItemStack(material, batch)).isEmpty()) {
                // The preflight above means this can only be a concurrent
                // inventory change. Keep the collector unchanged rather than
                // dropping or deleting anything.
                player.sendMessage(messages.get(player, "collector.inventory-full"));
                return;
            }
            remaining -= batch;
        }
        data.setStored(material, stored - toWithdraw);
        manager.writeData(location, data);
        player.sendMessage(messages.get(player, "collector.withdrew",
                "amount", String.format("%,d", toWithdraw), "item", material.name()));
    }

    private static boolean canFullyFit(Player player, Material material, long amount) {
        long room = 0L;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty()) room += material.getMaxStackSize();
            else if (item.getType() == material && item.isSimilar(new ItemStack(material))) {
                room += Math.max(0, material.getMaxStackSize() - item.getAmount());
            }
            if (room >= amount) return true;
        }
        return room >= amount;
    }

    private void upgrade(Player player, Location location, ChunkCollectorData data) {
        double cost = manager.upgradeCost(data.upgradeTier());
        if (cost < 0) {
            player.sendMessage(messages.get(player, "collector.upgrade-maxed", "tier", String.valueOf(data.upgradeTier())));
            return;
        }
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, "spawner.no-economy"));
            return;
        }
        Economy economy = EconomyHook.getEconomy();
        EconomyResponse response = economy.withdrawPlayer(player, cost);
        if (!response.transactionSuccess()) {
            player.sendMessage(messages.get(player, "collector.cannot-afford", "amount", EconomyHook.format(cost)));
            return;
        }
        data.setUpgradeTier(data.upgradeTier() + 1);
        manager.writeData(location, data);
        player.sendMessage(messages.get(player, "collector.upgraded",
                "tier", String.valueOf(data.upgradeTier()), "capacity", String.format("%,d", manager.capacityFor(data.upgradeTier()))));
    }
}
