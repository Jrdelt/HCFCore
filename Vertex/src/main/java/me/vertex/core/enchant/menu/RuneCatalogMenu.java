package me.vertex.core.enchant.menu;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.RuneRollTable;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only reference menu for every configured Rune tier, Arena included. */
public final class RuneCatalogMenu {

    private static final Map<RuneTier, Integer> TAB_SLOTS = Map.of(
            RuneTier.SIMPLE, 1, RuneTier.ELITE, 2, RuneTier.RARE, 3,
            RuneTier.LEGENDARY, 4, RuneTier.ARENA, 5);
    private static final List<Integer> CONTENT_SLOTS = List.of(
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43);

    private RuneCatalogMenu() {
    }

    public static void open(Player player, EnchantManager manager, Messages messages, RuneTier selected) {
        RuneTier tier = selected == null ? RuneTier.SIMPLE : selected;
        Holder holder = new Holder(tier);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "rune.catalog-title"));
        holder.inventory = inventory;
        for (Map.Entry<RuneTier, Integer> entry : TAB_SLOTS.entrySet()) {
            inventory.setItem(entry.getValue(), tabIcon(entry.getKey(), manager, messages, player, entry.getKey() == tier));
        }
        inventory.setItem(49, button(Material.ARROW, messages.getGui(player, "rune.catalog-back"),
                List.of(messages.getGui(player, "rune.catalog-back-lore"))));
        List<ItemStack> contents = catalogEntries(manager, tier, messages, player);
        for (int index = 0; index < contents.size() && index < CONTENT_SLOTS.size(); index++) {
            inventory.setItem(CONTENT_SLOTS.get(index), contents.get(index));
        }
        player.openInventory(inventory);
    }

    private static ItemStack tabIcon(RuneTier tier, EnchantManager manager, Messages messages, Player player, boolean selected) {
        ItemStack item = manager.createRune(tier);
        ItemMeta meta = item.getItemMeta();
        meta.lore(List.of(messages.getGui(player, selected ? "rune.catalog-current-tab" : "rune.catalog-tab-hint")));
        item.setItemMeta(meta);
        return item;
    }

    private static List<ItemStack> catalogEntries(EnchantManager manager, RuneTier tier, Messages messages, Player player) {
        List<ItemStack> entries = new ArrayList<>();
        Map<String, List<RuneRollTable.Entry>> byEnchant = new LinkedHashMap<>();
        for (RuneRollTable.Entry entry : manager.rollTable(tier).entries()) {
            byEnchant.computeIfAbsent(entry.enchantId(), ignored -> new ArrayList<>()).add(entry);
        }
        for (Map.Entry<String, List<RuneRollTable.Entry>> grouped : byEnchant.entrySet()) {
            EnchantDefinition definition = manager.definition(grouped.getKey());
            List<RuneRollTable.Entry> levels = grouped.getValue().stream()
                    .filter(entry -> definition != null && definition.level(entry.level()) != null)
                    .sorted(Comparator.comparingInt(RuneRollTable.Entry::level)).toList();
            if (definition == null || levels.isEmpty()) continue;
            ItemStack item = manager.createEnchantItem(grouped.getKey(), levels.getFirst().level(), tier);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(RuneFormatting.plain(RuneFormatting.smallCaps(definition.displayName()),
                    RuneFormatting.tierColor(tier)));
            // Deliberately terse: the description, the level-I-to-max value
            // range, and what it fits on -- one line each. Everything else
            // (exact per-level numbers, success/fail odds) only ever shows
            // on the actual identified item, never here.
            EnchantDefinition.Level first = definition.level(levels.getFirst().level());
            EnchantDefinition.Level last = definition.level(levels.getLast().level());
            List<Component> lore = new ArrayList<>();
            lore.add(messages.getGui(player, "rune.catalog-description", "description",
                    RuneFormatting.smallCaps(definition.description())));
            lore.add(messages.getGui(player, "rune.catalog-levels-range",
                    "first", RuneFormatting.roman(levels.getFirst().level()),
                    "last", RuneFormatting.roman(levels.getLast().level())));
            lore.add(messages.getGui(player, "rune.catalog-generated-value",
                    "min", RuneFormatting.percent(first.abilityValue()),
                    "max", RuneFormatting.percent(last.abilityValue())));
            lore.add(messages.getGui(player, "rune.catalog-compatible", "items",
                    RuneFormatting.smallCaps(String.join(", ", definition.compatibleTypes()))));
            meta.lore(lore);
            item.setItemMeta(meta);
            entries.add(item);
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

    public static final class Holder implements InventoryHolder {
        private final RuneTier selected;
        private Inventory inventory;

        private Holder(RuneTier selected) {
            this.selected = selected;
        }

        public RuneTier selected() {
            return selected;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    static RuneTier tierAt(int slot) {
        return TAB_SLOTS.entrySet().stream().filter(entry -> entry.getValue() == slot)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }
}
