package me.vertex.core.enchant.binds.menu;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneEquipment;
import me.vertex.core.enchant.RuneFormatting;
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

import java.util.ArrayList;
import java.util.List;

/** One bind's 3 ordered rune slots -- left-click replaces, right-click removes, shift-left/right reorders. */
public final class BindEditMenu {

    public static final List<Integer> SLOTS = List.of(11, 13, 15);
    public static final int BACK_SLOT = 22;

    private BindEditMenu() {
    }

    public static void open(Player player, EnchantManager manager, PlayerBinds binds, Messages messages, int bindIndex) {
        Holder holder = new Holder(bindIndex);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                messages.getGui(player, "binds.edit-title", "bind", String.valueOf(bindIndex)));
        holder.inventory = inventory;
        List<String> runeIds = binds.bind(bindIndex);
        for (int slotIndex = 0; slotIndex < SLOTS.size(); slotIndex++) {
            inventory.setItem(SLOTS.get(slotIndex),
                    slotIndex < runeIds.size()
                            ? assignedIcon(player, manager, messages, bindIndex, slotIndex, runeIds.get(slotIndex))
                            : emptyIcon(player, messages));
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, messages.getGui(player, "binds.back-button"),
                List.of(messages.getGui(player, "binds.back-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack assignedIcon(Player player, EnchantManager manager, Messages messages, int bindIndex,
            int slotIndex, String enchantId) {
        EnchantDefinition definition = manager.definition(enchantId);
        int level = RuneEquipment.highestAvailableLevel(player, manager, enchantId);
        int targetLevel = Math.max(1, Math.min(level, definition == null ? 1 : definition.maxLevel()));
        ItemStack item = RuneEquipment.resolveDisplayItem(player, manager, enchantId, targetLevel);
        if (item == null) {
            item = manager.createEnchantItem(enchantId, targetLevel, me.vertex.core.enchant.RuneTier.SIMPLE);
        }
        if (item == null) {
            item = new ItemStack(Material.BOOK);
        }
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = meta != null && meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore())
                : new ArrayList<>();
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }
        lore.add(messages.getGui(player, level > 0 ? "binds.slot-available" : "binds.slot-unavailable"));
        lore.add(Component.empty());
        lore.add(messages.getGui(player, "binds.slot-left-click"));
        lore.add(messages.getGui(player, "binds.slot-right-click"));
        if (slotIndex > 0) {
            lore.add(messages.getGui(player, "binds.slot-shift-left"));
        }
        lore.add(messages.getGui(player, "binds.slot-shift-right"));
        if (meta != null) {
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack emptyIcon(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(RuneFormatting.plain("ᴇᴍᴘᴛʏ ꜱʟᴏᴛ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(messages.getGui(player, "binds.slot-empty-hint")));
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

    public static Integer slotIndexAt(int rawSlot) {
        int index = SLOTS.indexOf(rawSlot);
        return index < 0 ? null : index;
    }

    public static final class Holder implements InventoryHolder {
        private final int bindIndex;
        private Inventory inventory;

        private Holder(int bindIndex) {
            this.bindIndex = bindIndex;
        }

        public int bindIndex() {
            return bindIndex;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
