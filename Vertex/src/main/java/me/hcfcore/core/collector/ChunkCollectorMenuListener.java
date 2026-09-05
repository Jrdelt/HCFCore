package me.hcfcore.core.collector;

import me.hcfcore.core.economy.EconomyHook;
import me.hcfcore.core.lang.Messages;
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

/** Handles withdraw (left-click one stack / shift-click all) and upgrade clicks in {@link ChunkCollectorMenu}. */
public final class ChunkCollectorMenuListener implements Listener {

    private final ChunkCollectorManager manager;
    private final Messages messages;

    public ChunkCollectorMenuListener(ChunkCollectorManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ChunkCollectorMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ChunkCollectorMenu.Holder holder)) {
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

        if (event.getSlot() == ChunkCollectorMenu.UPGRADE_SLOT) {
            upgrade(player, location, data);
        } else {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) {
                return;
            }
            withdraw(player, location, data, clicked.getType(), event.isShiftClick());
        }
        player.closeInventory();
    }

    private void withdraw(Player player, Location location, ChunkCollectorData data, Material material, boolean withdrawAll) {
        long stored = data.stored(material);
        if (stored <= 0) {
            return;
        }
        long toWithdraw = withdrawAll ? stored : Math.min(stored, material.getMaxStackSize());
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
