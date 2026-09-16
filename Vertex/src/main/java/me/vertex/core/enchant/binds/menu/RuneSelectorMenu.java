package me.vertex.core.enchant.binds.menu;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneEquipment;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Only shows {@code bindable: true} runes the player currently has
 * available (armor/hands/hotbar/inventory), deduplicated by rune id at the
 * highest currently-available level -- never the whole registry.
 */
public final class RuneSelectorMenu {

    private static final List<Integer> CONTENT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25);
    public static final int BACK_SLOT = 49;

    private RuneSelectorMenu() {
    }

    public static void open(Player player, EnchantManager manager, Messages messages, int bindIndex, int slotIndex) {
        Map<String, Integer> highestAvailable = availableBindableRunes(player, manager);
        Holder holder = new Holder(bindIndex, slotIndex, List.copyOf(highestAvailable.keySet()));
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "binds.selector-title"));
        holder.inventory = inventory;
        int index = 0;
        for (Map.Entry<String, Integer> entry : highestAvailable.entrySet()) {
            if (index >= CONTENT_SLOTS.size()) {
                break;
            }
            inventory.setItem(CONTENT_SLOTS.get(index), icon(manager, messages, player, entry.getKey(), entry.getValue()));
            index++;
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, messages.getGui(player, "binds.back-button"),
                List.of(messages.getGui(player, "binds.back-lore"))));
        player.openInventory(inventory);
    }

    /** @return bindable rune id -> highest currently-available level, in a stable display order. */
    public static Map<String, Integer> availableBindableRunes(Player player, EnchantManager manager) {
        Map<String, Integer> highest = new LinkedHashMap<>();
        for (ItemStack item : RuneEquipment.bindEligibleItems(player)) {
            for (Map.Entry<String, Integer> entry : manager.enchantsOf(item).entrySet()) {
                EnchantDefinition definition = manager.definition(entry.getKey());
                if (definition == null || !definition.isBindable()) {
                    continue;
                }
                highest.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        return highest;
    }

    private static ItemStack icon(EnchantManager manager, Messages messages, Player player, String enchantId, int level) {
        ItemStack item = RuneEquipment.resolveDisplayItem(player, manager, enchantId, level);
        if (item == null) {
            item = manager.createEnchantItem(enchantId, level, RuneTier.SIMPLE);
        }
        if (item == null) {
            item = new ItemStack(Material.BOOK);
        }
        ItemMeta meta = item.getItemMeta();
        EnchantDefinition definition = manager.definition(enchantId);
        List<Component> lore = meta != null && meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore())
                : new ArrayList<>();
        lore.add(Component.empty());
        if (definition != null && definition.description() != null) {
            lore.add(messages.getGui(player, "binds.selector-description",
                    "description", RuneFormatting.smallCaps(definition.description())));
        }
        lore.add(messages.getGui(player, "binds.selector-hint"));
        if (meta != null) {
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static String enchantIdAt(Holder holder, int slot) {
        int index = CONTENT_SLOTS.indexOf(slot);
        return index < 0 || index >= holder.entries.size() ? null : holder.entries.get(index);
    }

    public static final class Holder implements InventoryHolder {
        private final int bindIndex;
        private final int slotIndex;
        private final List<String> entries;
        private Inventory inventory;

        private Holder(int bindIndex, int slotIndex, List<String> entries) {
            this.bindIndex = bindIndex;
            this.slotIndex = slotIndex;
            this.entries = entries;
        }

        public int bindIndex() {
            return bindIndex;
        }

        public int slotIndex() {
            return slotIndex;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
