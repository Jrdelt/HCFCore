package me.vertex.core.enchant;

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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-only reference menu for every configured Rune category. */
public final class RuneCatalogMenu {
    public enum Category {
        SIMPLE, ELITE, RARE, LEGENDARY, ARENA;

        static Category from(RuneShopMenu.PurchaseProduct product) {
            if (product == RuneShopMenu.PurchaseProduct.ARENA_RUNE) return ARENA;
            return product == null || product.tier() == null ? SIMPLE : valueOf(product.tier().name());
        }
    }

    private static final Map<Category, Integer> TAB_SLOTS = Map.of(
            Category.SIMPLE, 1, Category.ELITE, 2, Category.RARE, 3,
            Category.LEGENDARY, 4, Category.ARENA, 5);
    private static final List<Integer> CONTENT_SLOTS = List.of(
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43);

    private RuneCatalogMenu() {
    }

    public static void open(Player player, EnchantManager manager, ArenaRuneManager arena, Messages messages,
            Category selected) {
        if (selected == Category.ARENA && arena == null) selected = Category.SIMPLE;
        Holder holder = new Holder(selected);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "rune.catalog-title"));
        holder.inventory = inventory;
        for (Map.Entry<Category, Integer> entry : TAB_SLOTS.entrySet()) {
            if (entry.getKey() == Category.ARENA && arena == null) continue;
            inventory.setItem(entry.getValue(), tabIcon(entry.getKey(), manager, arena, messages, player,
                    entry.getKey() == selected));
        }
        inventory.setItem(49, button(Material.ARROW, messages.getGui(player, "rune.catalog-back"),
                List.of(messages.getGui(player, "rune.catalog-back-lore"))));
        List<ItemStack> contents = selected == Category.ARENA
                ? arenaEntries(arena, messages, player) : legacyEntries(manager, selected, messages, player);
        for (int index = 0; index < contents.size() && index < CONTENT_SLOTS.size(); index++) {
            inventory.setItem(CONTENT_SLOTS.get(index), contents.get(index));
        }
        player.openInventory(inventory);
    }

    private static ItemStack tabIcon(Category category, EnchantManager manager, ArenaRuneManager arena,
            Messages messages, Player player, boolean selected) {
        ItemStack item = category == Category.ARENA ? arena.createRune() : manager.createRune(RuneTier.valueOf(category.name()));
        ItemMeta meta = item.getItemMeta();
        meta.lore(List.of(messages.getGui(player, selected ? "rune.catalog-current-tab" : "rune.catalog-tab-hint")));
        item.setItemMeta(meta);
        return item;
    }

    private static List<ItemStack> legacyEntries(EnchantManager manager, Category category, Messages messages,
            Player player) {
        RuneTier tier = RuneTier.valueOf(category.name());
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
            List<Component> lore = new ArrayList<>();
            lore.add(RuneFormatting.plain("ᴄᴜꜱᴛᴏᴍ ᴇɴᴄʜᴀɴᴛᴍᴇɴᴛ", NamedTextColor.DARK_GRAY));
            lore.add(messages.getGui(player, "rune.catalog-description", "description",
                    RuneFormatting.smallCaps(definition.description())));
            lore.add(Component.empty());
            lore.add(messages.getGui(player, "rune.catalog-tier", "tier", RuneFormatting.smallCaps(category.name())));
            lore.add(messages.getGui(player, "rune.catalog-levels", "levels", levels.stream()
                    .map(entry -> RuneFormatting.roman(entry.level())).collect(Collectors.joining(", "))));
            for (RuneRollTable.Entry entry : levels) {
                EnchantDefinition.Level level = definition.level(entry.level());
                lore.add(messages.getGui(player, "rune.catalog-level-detail",
                        "level", RuneFormatting.roman(entry.level()),
                        "maximum", entry.level() == definition.maxLevel() ? "ᴍᴀx" : "",
                        "value", RuneFormatting.percent(level.abilityValue()),
                        "success", RuneFormatting.percent(level.successRate()),
                        "failure", RuneFormatting.percent(100D - level.successRate())));
                if (level.procChance() > 0D) {
                    lore.add(messages.getGui(player, "rune.catalog-proc-detail", "proc",
                            RuneFormatting.percent(level.procChance())));
                }
            }
            lore.add(messages.getGui(player, "rune.catalog-compatible", "items",
                    RuneFormatting.smallCaps(String.join(", ", definition.compatibleTypes()))));
            lore.add(messages.getGui(player, "rune.catalog-obtain", "source", RuneFormatting.smallCaps(category.name()) + " ʀᴜɴᴇ"));
            meta.lore(lore);
            item.setItemMeta(meta);
            entries.add(item);
        }
        return entries;
    }

    private static List<ItemStack> arenaEntries(ArenaRuneManager arena, Messages messages, Player player) {
        List<ItemStack> entries = new ArrayList<>();
        for (ArenaRuneManager.Effect effect : ArenaRuneManager.Effect.values()) {
            ItemStack item = arena.createEnchantItem(new ArenaRuneManager.RuneInfo(effect, 1, 50D));
            ItemMeta meta = item.getItemMeta();
            meta.displayName(RuneFormatting.plain(RuneFormatting.smallCaps(effect.displayName()), NamedTextColor.BLUE));
            List<Component> lore = new ArrayList<>();
            lore.add(RuneFormatting.plain("ᴄᴜꜱᴛᴏᴍ ᴇɴᴄʜᴀɴᴛᴍᴇɴᴛ", NamedTextColor.DARK_GRAY));
            lore.add(messages.getGui(player, "rune.catalog-description", "description",
                    RuneFormatting.smallCaps(effect.description())));
            lore.add(Component.empty());
            lore.add(messages.getGui(player, "rune.catalog-tier", "tier", "ᴍᴏʙ ᴀʀᴇɴᴀ"));
            lore.add(messages.getGui(player, "rune.catalog-arena-levels", "levels", "ɪ–XX",
                    "maximum", "XX ᴍᴀx"));
            lore.add(messages.getGui(player, "rune.catalog-arena-value", "value",
                    RuneFormatting.percent(effect.valueAt(1))));
            lore.add(messages.getGui(player, "rune.catalog-arena-success"));
            if (effect == ArenaRuneManager.Effect.CORRUPTED_DETONATION) {
                lore.add(messages.getGui(player, "rune.catalog-proc-detail", "proc", "30"));
            }
            lore.add(messages.getGui(player, "rune.catalog-compatible", "items",
                    effect.restrictedEquipment() ? "ᴄʜᴇꜱᴛᴘʟᴀᴛᴇ, ꜱᴡᴏʀᴅ, ᴀxᴇ" : "ᴅᴜʀᴀʙʟᴇ ɢᴇᴀʀ"));
            lore.add(messages.getGui(player, "rune.catalog-restriction"));
            lore.add(messages.getGui(player, "rune.catalog-obtain", "source", "ᴍᴏʙ ᴀʀᴇɴᴀ ʀᴜɴᴇ"));
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
        private final Category selected;
        private Inventory inventory;

        private Holder(Category selected) {
            this.selected = selected;
        }

        public Category selected() {
            return selected;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    static Category categoryAt(int slot) {
        return TAB_SLOTS.entrySet().stream().filter(entry -> entry.getValue() == slot)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }
}
