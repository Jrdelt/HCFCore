package me.hcfcore.core.collector;

import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Opened by right-clicking a tracked Chunk Collector. 27 slots: one per stored item type, plus an upgrade button. */
public final class ChunkCollectorMenu {

    public static final int SIZE = 27;
    public static final int UPGRADE_SLOT = 26;

    private ChunkCollectorMenu() {
    }

    public static void open(Player player, ChunkCollectorManager manager, Messages messages, Location location, ChunkCollectorData data) {
        Holder holder = new Holder(location);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, messages.get(player, "collector.title"));
        holder.inventory = inventory;

        long capacity = manager.capacityFor(data.upgradeTier());
        List<Material> types = data.stored().keySet().stream()
                .sorted((a, b) -> Long.compare(data.stored(b), data.stored(a)))
                .toList();

        int slot = 0;
        for (Material material : types) {
            if (slot >= UPGRADE_SLOT) {
                break;
            }
            long stored = data.stored(material);
            ItemStack icon = new ItemStack(material);
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(Component.text(material.name()));
            meta.lore(List.of(messages.get(player, "collector.stored-lore",
                    "stored", String.format("%,d", stored), "capacity", String.format("%,d", capacity))));
            icon.setItemMeta(meta);
            inventory.setItem(slot, icon);
            slot++;
        }

        double upgradeCost = manager.upgradeCost(data.upgradeTier());
        inventory.setItem(UPGRADE_SLOT, upgradeIcon(player, messages, data.upgradeTier(), manager.maxUpgradeTier(), upgradeCost));

        player.openInventory(inventory);
    }

    private static ItemStack upgradeIcon(Player player, Messages messages, int tier, int maxTier, double cost) {
        ItemStack icon = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (cost < 0) {
            meta.displayName(MessageFormatter.deserialize("<gold>Max Tier"));
            lore.add(messages.get(player, "collector.upgrade-maxed", "tier", String.valueOf(tier)));
        } else {
            meta.displayName(MessageFormatter.deserialize("<gold>Upgrade Storage"));
            lore.add(messages.get(player, "collector.upgrade-lore", "tier", String.valueOf(tier + 1),
                    "cost", String.format("%,.0f", cost)));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
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
