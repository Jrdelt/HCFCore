package me.vertex.core.collector;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/**
 * Opened by right-clicking a tracked Chunk Collector. 27 slots: one per
 * stored item type (up to the fixed 24-type safety cap), a summary icon
 * (tier + aggregate totals), and an upgrade button.
 */
public final class ChunkCollectorMenu {

    public static final int SIZE = 27;
    public static final int SUMMARY_SLOT = 24;
    public static final int UPGRADE_SLOT = 26;

    private ChunkCollectorMenu() {
    }

    public static void open(Player player, ChunkCollectorManager manager, Messages messages, Location location, ChunkCollectorData data) {
        Holder holder = new Holder(location);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                messages.getGui(player, "collector.title", "tier", String.valueOf(data.upgradeTier())));
        holder.inventory = inventory;

        long capacity = manager.capacityFor(data.upgradeTier());
        List<Material> types = data.stored().keySet().stream()
                .sorted((a, b) -> Long.compare(data.stored(b), data.stored(a)))
                .toList();

        long totalStored = data.totalStored();
        int slot = 0;
        for (Material material : types) {
            if (slot >= SUMMARY_SLOT) {
                continue;
            }
            long stored = data.stored(material);
            ItemStack icon = new ItemStack(material);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(nonItalic(messages.getGui(player, "collector.item-title", "item", material.name())));
            meta.lore(List.of(
                    nonItalic(messages.getGui(player, "collector.stored-lore",
                            "stored", String.format("%,d", stored))),
                    nonItalic(messages.getGui(player, "collector.withdraw-amount-lore")),
                    nonItalic(messages.getGui(player, "collector.withdraw-shift-lore",
                            "amount", String.format("%,d", manager.shiftWithdrawAmount())))));
            icon.setItemMeta(meta);
            inventory.setItem(slot, icon);
            slot++;
        }

        inventory.setItem(SUMMARY_SLOT, summaryIcon(player, messages, data, types.size(), totalStored, capacity));

        double upgradeCost = manager.upgradeCost(data.upgradeTier());
        inventory.setItem(UPGRADE_SLOT, upgradeIcon(player, messages, data.upgradeTier(), manager.maxUpgradeTier(), upgradeCost));

        player.openInventory(inventory);
    }

    private static ItemStack summaryIcon(Player player, Messages messages, ChunkCollectorData data,
                                          int typeCount, long totalStored, long capacity) {
        ItemStack icon = new ItemStack(Material.BOOK);
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(nonItalic(messages.getGui(player, "collector.summary-title")));
        meta.lore(List.of(
                nonItalic(messages.getGui(player, "collector.summary-tier", "tier", String.valueOf(data.upgradeTier()))),
                nonItalic(messages.getGui(player, "collector.summary-stored",
                        "stored", String.format("%,d", totalStored), "types", String.valueOf(typeCount))),
                nonItalic(messages.getGui(player, "collector.summary-capacity", "capacity", String.format("%,d", capacity)))));
        icon.setItemMeta(meta);
        return icon;
    }

    private static ItemStack upgradeIcon(Player player, Messages messages, int tier, int maxTier, double cost) {
        ItemStack icon = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (cost < 0) {
            meta.displayName(nonItalic(messages.getGui(player, "collector.upgrade-maxed-title")));
            lore.add(nonItalic(messages.getGui(player, "collector.upgrade-maxed", "tier", String.valueOf(tier))));
        } else {
            meta.displayName(nonItalic(messages.getGui(player, "collector.upgrade-title")));
            lore.add(nonItalic(messages.getGui(player, "collector.upgrade-lore", "tier", String.valueOf(tier + 1),
                    "cost", String.format("%,.0f", cost))));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private static Component nonItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final Location location;
        private Inventory inventory;

        private Holder(Location location) {
            this.location = location;
        }

        public Location location() {
            return location;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
