package me.vertex.core.enchant.menu;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
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
 * Red/green confirmation for a single incineration or a TNT "incinerate
 * all" batch -- closing the GUI always cancels, matching every other
 * confirm menu in this codebase (e.g. {@code
 * me.vertex.core.enchant.binds.menu.PresetConfirmMenu}).
 */
public final class IncineratorConfirmMenu {

    public static final int CANCEL_SLOT = 11;
    public static final int CONFIRM_SLOT = 15;

    private IncineratorConfirmMenu() {
    }

    /** Individual confirmation: shows the exact item being targeted. */
    public static void openSingle(Player player, Messages messages, int inventorySlot, ItemStack item) {
        Holder holder = new Holder(Mode.SINGLE, List.of(inventorySlot));
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "rune.incinerate-confirm-title"));
        holder.inventory = inventory;
        fillGlass(inventory);
        ItemStack display = item.clone();
        inventory.setItem(13, display);
        placeButtons(player, messages, inventory);
        player.openInventory(inventory);
    }

    /** Batch confirmation: shows a TNT icon with the eligible count and an estimated reward range. */
    public static void openBatch(Player player, Messages messages, List<Integer> inventorySlots, String estimatedRange) {
        Holder holder = new Holder(Mode.BATCH, inventorySlots);
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "rune.incinerate-all-confirm-title"));
        holder.inventory = inventory;
        fillGlass(inventory);
        ItemStack tnt = new ItemStack(Material.TNT, Math.min(64, inventorySlots.size()));
        ItemMeta meta = tnt.getItemMeta();
        meta.displayName(messages.getGui(player, "rune.incinerate-all-confirm-name", "count", String.valueOf(inventorySlots.size()))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                messages.getGui(player, "rune.incinerate-all-confirm-estimate", "range", estimatedRange),
                messages.getGui(player, "rune.incinerate-all-confirm-warning")));
        tnt.setItemMeta(meta);
        inventory.setItem(13, tnt);
        placeButtons(player, messages, inventory);
        player.openInventory(inventory);
    }

    /** {@code /ce auto} enable-confirmation: warns that currently-unprotected standalone runes may be immediately destroyed. */
    public static void openAutoEnable(Player player, Messages messages) {
        Holder holder = new Holder(Mode.AUTO_ENABLE, List.of());
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "rune.auto-confirm-title"));
        holder.inventory = inventory;
        fillGlass(inventory);
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(messages.getGui(player, "rune.auto-confirm-name").decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(messages.getGui(player, "rune.auto-confirm-warning")));
        book.setItemMeta(meta);
        inventory.setItem(13, book);
        placeButtons(player, messages, inventory);
        player.openInventory(inventory);
    }

    private static void fillGlass(Inventory inventory) {
        for (int slot = 0; slot < 27; slot++) {
            if (slot != CANCEL_SLOT && slot != CONFIRM_SLOT && slot != 13) {
                inventory.setItem(slot, button(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of()));
            }
        }
    }

    private static void placeButtons(Player player, Messages messages, Inventory inventory) {
        inventory.setItem(CANCEL_SLOT, button(Material.RED_STAINED_GLASS_PANE,
                messages.getGui(player, "rune.incinerate-cancel"), List.of(messages.getGui(player, "rune.incinerate-cancel-lore"))));
        inventory.setItem(CONFIRM_SLOT, button(Material.GREEN_STAINED_GLASS_PANE,
                messages.getGui(player, "rune.incinerate-confirm"), List.of(messages.getGui(player, "rune.incinerate-confirm-lore"))));
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public enum Mode {
        SINGLE, BATCH, AUTO_ENABLE
    }

    public static final class Holder implements InventoryHolder {
        private final Mode mode;
        private final List<Integer> inventorySlots;
        private Inventory inventory;

        private Holder(Mode mode, List<Integer> inventorySlots) {
            this.mode = mode;
            this.inventorySlots = inventorySlots;
        }

        public Mode mode() {
            return mode;
        }

        public List<Integer> inventorySlots() {
            return inventorySlots;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
