package me.vertex.core.enchant.binds.menu;

import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.binds.BindManager;
import me.vertex.core.enchant.binds.PlayerBinds;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** One row of up to 7 preset slots, centered -- locked past the player's rank entitlement. No custom names, just "Preset N". */
public final class PresetMenu {

    private static final List<Integer> SLOTS = List.of(10, 11, 12, 13, 14, 15, 16);
    public static final int BACK_SLOT = 22;

    private PresetMenu() {
    }

    public static void open(Player player, BindManager manager, PlayerBinds binds, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "binds.presets-title"));
        holder.inventory = inventory;
        int limit = manager.presetLimit(player);
        for (int presetIndex = 1; presetIndex <= PlayerBinds.HARD_MAX_PRESETS; presetIndex++) {
            int slot = SLOTS.get(presetIndex - 1);
            if (presetIndex > limit) {
                inventory.setItem(slot, locked(player, messages, presetIndex));
            } else if (binds.presetIndexes().contains(presetIndex)) {
                inventory.setItem(slot, existing(player, messages, presetIndex, binds.activePresetIndex() != null
                        && binds.activePresetIndex() == presetIndex));
            } else {
                inventory.setItem(slot, empty(player, messages, presetIndex));
            }
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, messages.getGui(player, "binds.back-button"),
                List.of(messages.getGui(player, "binds.back-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack locked(Player player, Messages messages, int presetIndex) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(RuneFormatting.plain("ᴘʀᴇꜱᴇᴛ " + presetIndex, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(messages.getGui(player, "binds.preset-locked")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack existing(Player player, Messages messages, int presetIndex, boolean active) {
        ItemStack item = new ItemStack(active ? Material.LIME_DYE : Material.BLUE_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(RuneFormatting.plain("ᴘʀᴇꜱᴇᴛ " + presetIndex, active ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                messages.getGui(player, active ? "binds.preset-active" : "binds.preset-click-load"),
                messages.getGui(player, "binds.preset-click-delete")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack empty(Player player, Messages messages, int presetIndex) {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(RuneFormatting.plain("ᴘʀᴇꜱᴇᴛ " + presetIndex, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(messages.getGui(player, "binds.preset-click-save")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** @return the preset index (1-9) for a slot, or null if it's not a preset slot. */
    public static Integer presetIndexAt(int slot) {
        int index = SLOTS.indexOf(slot);
        return index < 0 ? null : index + 1;
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
