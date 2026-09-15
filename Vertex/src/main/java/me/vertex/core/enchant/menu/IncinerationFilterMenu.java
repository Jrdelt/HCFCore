package me.vertex.core.enchant.menu;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.RuneRollTable;
import me.vertex.core.enchant.RuneTier;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Incineration Filter Catalog: mirrors {@link RuneCatalogMenu}'s exact
 * tab/tier iteration over {@code manager.rollTable(tier)} rather than a
 * second hardcoded rune list -- seasonal/non-standalone enchants are
 * excluded automatically because they never appear in any roll table, and
 * a newly added or removed standalone rune appears/disappears here for
 * free. One icon per enchant (not per level); clicking opens {@link
 * LevelFilterMenu} for that enchant's levels.
 */
public final class IncinerationFilterMenu {

    private static final Map<RuneTier, Integer> TAB_SLOTS = Map.of(
            RuneTier.SIMPLE, 1, RuneTier.ELITE, 2, RuneTier.RARE, 3,
            RuneTier.LEGENDARY, 4, RuneTier.ARENA, 5);
    private static final List<Integer> CONTENT_SLOTS = List.of(
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43);
    public static final int BACK_SLOT = 49;

    private IncinerationFilterMenu() {
    }

    public static void open(Player player, EnchantManager manager, Messages messages, RuneTier selected) {
        RuneTier tier = selected == null ? RuneTier.SIMPLE : selected;
        Holder holder = new Holder(tier);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "rune.filter-catalog-title"));
        holder.inventory = inventory;
        for (Map.Entry<RuneTier, Integer> entry : TAB_SLOTS.entrySet()) {
            inventory.setItem(entry.getValue(), tabIcon(entry.getKey(), manager, messages, player, entry.getKey() == tier));
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, messages.getGui(player, "rune.catalog-back"),
                List.of(messages.getGui(player, "rune.filter-back-lore"))));
        List<String> enchantIds = new ArrayList<>();
        List<ItemStack> contents = entries(manager, tier, messages, player, enchantIds);
        for (int index = 0; index < contents.size() && index < CONTENT_SLOTS.size(); index++) {
            inventory.setItem(CONTENT_SLOTS.get(index), contents.get(index));
        }
        holder.enchantIds.addAll(enchantIds);
        player.openInventory(inventory);
    }

    private static ItemStack tabIcon(RuneTier tier, EnchantManager manager, Messages messages, Player player, boolean selected) {
        ItemStack item = manager.createRune(tier);
        ItemMeta meta = item.getItemMeta();
        meta.lore(List.of(messages.getGui(player, selected ? "rune.catalog-current-tab" : "rune.catalog-tab-hint")));
        item.setItemMeta(meta);
        return item;
    }

    private static List<ItemStack> entries(EnchantManager manager, RuneTier tier, Messages messages, Player player, List<String> enchantIds) {
        List<ItemStack> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RuneRollTable.Entry rollEntry : manager.rollTable(tier).entries()) {
            if (!seen.add(rollEntry.enchantId())) {
                continue;
            }
            EnchantDefinition definition = manager.definition(rollEntry.enchantId());
            if (definition == null) {
                continue;
            }
            ItemStack item = manager.createEnchantItem(rollEntry.enchantId(), rollEntry.level(), tier);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(RuneFormatting.plain(RuneFormatting.smallCaps(definition.displayName()), RuneFormatting.tierColor(tier)));
            meta.lore(List.of(
                    messages.getGui(player, "rune.filter-description", "description", RuneFormatting.smallCaps(definition.description())),
                    messages.getGui(player, "rune.filter-click-hint")));
            item.setItemMeta(meta);
            entries.add(item);
            enchantIds.add(rollEntry.enchantId());
        }
        return entries;
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static RuneTier tierAt(int slot) {
        return TAB_SLOTS.entrySet().stream().filter(entry -> entry.getValue() == slot)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    public static final class Holder implements InventoryHolder {
        private final RuneTier selected;
        private final List<String> enchantIds = new ArrayList<>();
        private Inventory inventory;

        private Holder(RuneTier selected) {
            this.selected = selected;
        }

        public RuneTier selected() {
            return selected;
        }

        public String enchantIdAt(int contentSlotIndex) {
            return contentSlotIndex < 0 || contentSlotIndex >= enchantIds.size() ? null : enchantIds.get(contentSlotIndex);
        }

        public int indexOfSlot(int slot) {
            return CONTENT_SLOTS.indexOf(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
