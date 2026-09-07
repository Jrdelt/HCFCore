package me.vertex.core.backpack;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * The GUI opened by shift-right-clicking in the air with a Backpack in
 * the offhand. It deliberately never exposes the stored inventory: items
 * enter automatically and can only leave through the Empty button.
 */
public final class BackpackMenu {

    public static final int DISPLAY_SLOT = 11;
    public static final int EMPTY_SLOT = 13;
    public static final int UPGRADE_SLOT = 15;
    public static final int ROWS = 4;

    private BackpackMenu() {
    }

    public static void open(Player player, BackpackManager manager, Messages messages,
            BackpackTier tier, BackpackData data, ItemStack backpackItem) {
        int size = ROWS * 9;
        Holder holder = new Holder(backpackItem, tier);
        Inventory inventory = Bukkit.createInventory(holder, size, messages.get(player, "backpack.gui-title"));
        holder.inventory = inventory;

        for (int slot = 0; slot < size; slot++) {
            inventory.setItem(slot, border());
        }
        inventory.setItem(DISPLAY_SLOT, backpackItem.clone());
        inventory.setItem(EMPTY_SLOT, emptyButton(player, messages));
        inventory.setItem(UPGRADE_SLOT, upgradeButton(player, messages, manager, tier, data));
        player.openInventory(inventory);
    }

    private static ItemStack border() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack emptyButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.CAULDRON);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "backpack.gui-empty-button")));
        meta.lore(messages.getList(player, "backpack.gui-empty-lore").stream().map(BackpackMenu::noItalic).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack upgradeButton(Player player, Messages messages, BackpackManager manager,
            BackpackTier tier, BackpackData data) {
        double cost = manager.upgradeCost(tier, data.level());
        boolean maxed = cost < 0;
        ItemStack item = new ItemStack(maxed ? Material.BARRIER : Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "backpack.gui-upgrade-button")));
        meta.lore(List.of(
                maxed ? noItalic(messages.get(player, "backpack.gui-upgrade-lore-maxed"))
                        : noItalic(messages.get(player, "backpack.gui-upgrade-lore-cost", "cost", EconomyHook.format(cost)))));
        item.setItemMeta(meta);
        return item;
    }

    private static net.kyori.adventure.text.Component noItalic(net.kyori.adventure.text.Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final ItemStack backpackItem;
        private final BackpackTier tier;
        private Inventory inventory;

        Holder(ItemStack backpackItem, BackpackTier tier) {
            this.backpackItem = backpackItem;
            this.tier = tier;
        }

        public ItemStack backpackItem() {
            return backpackItem;
        }

        BackpackTier tier() {
            return tier;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
