package me.vertex.core.collector;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.staff.StaffManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;

/** Handles collector withdrawals, amount entry, and upgrades. */
public final class ChunkCollectorMenuListener implements Listener {

    private final ChunkCollectorManager manager;
    private final StaffManager staffManager;
    private final Messages messages;
    private final RallyManager rolePermissions;

    public ChunkCollectorMenuListener(ChunkCollectorManager manager, StaffManager staffManager, Messages messages,
            RallyManager rolePermissions) {
        this.manager = manager;
        this.staffManager = staffManager;
        this.messages = messages;
        this.rolePermissions = rolePermissions;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ChunkCollectorMenu.Holder
                || event.getInventory().getHolder() instanceof CollectorWithdrawAmountMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof CollectorWithdrawAmountMenu.Holder holder)
                || !(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        Long amount = CollectorWithdrawAmountMenu.readAmount(event.getView(), event.getInventory());
        holder.setLastPreparedAmount(amount);
        event.setResult(CollectorWithdrawAmountMenu.confirmButton(player, holder.messages(), amount));
        // Set after the result, not before: some Paper builds recompute a
        // fresh repair cost from the new result item when setResult() runs,
        // silently undoing an override applied earlier in the handler.
        event.getView().setRepairCost(0);
        event.getView().setMaximumRepairCost(CollectorWithdrawAmountMenu.FREE_ANVIL_MAX_COST);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Object rawHolder = event.getInventory().getHolder();
        if (rawHolder instanceof CollectorWithdrawAmountMenu.Holder amountHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() == CollectorWithdrawAmountMenu.SLOT_RESULT
                    && event.getWhoClicked() instanceof Player player) {
                completeAmountWithdrawal(player, event, amountHolder);
            }
            return;
        }
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
        if (!canAccess(player, location, data)) {
            player.closeInventory();
            return;
        }

        if (event.getSlot() == ChunkCollectorMenu.UPGRADE_SLOT) {
            upgrade(player, location, data);
            // Keep the collector open after an upgrade attempt so the player
            // can immediately buy another tier; reopening also refreshes the
            // tier, capacity, and next-upgrade cost shown by the GUI.
            ChunkCollectorMenu.open(player, manager, messages, location, data);
            return;
        } else if (event.getRawSlot() < ChunkCollectorMenu.SUMMARY_SLOT) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) {
                return;
            }
            if (event.isShiftClick()) {
                withdraw(player, location, data, clicked.getType(), manager.shiftWithdrawAmount());
                refresh(player, location);
            } else {
                CollectorWithdrawAmountMenu.open(player, messages, location, clicked.getType());
            }
        }
    }

    private void completeAmountWithdrawal(Player player, InventoryClickEvent event,
                                          CollectorWithdrawAmountMenu.Holder holder) {
        // The result item has the parsed amount embedded in its PDC. This
        // survives the vanilla anvil clearing its rename field while the
        // result is clicked; only use text/cache fallbacks for API mocks or
        // another plugin that replaced the result item.
        Long amount = CollectorWithdrawAmountMenu.confirmedAmount(event.getCurrentItem());
        if (amount == null) {
            amount = CollectorWithdrawAmountMenu.readAmount(event.getView(), event.getInventory());
        }
        if (amount == null) {
            amount = holder.lastPreparedAmount();
        }
        if (amount == null) {
            player.sendMessage(messages.get(player, "collector.withdraw-invalid"));
            return;
        }
        Location location = holder.location();
        ChunkCollectorData data = manager.readData(location);
        if (data == null) {
            player.closeInventory();
            return;
        }
        if (!canAccess(player, location, data)) {
            player.closeInventory();
            return;
        }
        long stored = data.stored(holder.material());
        if (amount > stored) {
            player.sendMessage(messages.get(player, "collector.withdraw-too-many",
                    "available", String.format("%,d", stored)));
            return;
        }
        withdraw(player, location, data, holder.material(), amount);
        Bukkit.getScheduler().runTask(manager.plugin(), () -> refresh(player, location));
    }

    private boolean canAccess(Player player, Location location, ChunkCollectorData data) {
        if (staffManager.isStaffBuild(player.getUniqueId())) {
            return true;
        }
        String claimTag = FactionsHook.getClaimFactionTag(location);
        String playerTag = FactionsHook.getFactionTag(player);
        boolean mayUse = (claimTag != null && claimTag.equalsIgnoreCase(playerTag))
                || (claimTag == null && data.ownerFactionTag() != null
                        && data.ownerFactionTag().equalsIgnoreCase(playerTag));
        if (mayUse) {
            if (!rolePermissions.canUse(player, "collector-open")) {
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
        long remaining = toWithdraw;
        while (remaining > 0) {
            int batch = (int) Math.min(remaining, material.getMaxStackSize());
            for (ItemStack dropped : player.getInventory().addItem(new ItemStack(material, batch)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), dropped);
            }
            remaining -= batch;
        }
        data.setStored(material, stored - toWithdraw);
        manager.writeData(location, data);
        player.sendMessage(messages.get(player, "collector.withdrew",
                "amount", String.format("%,d", toWithdraw), "item", material.name()));
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
