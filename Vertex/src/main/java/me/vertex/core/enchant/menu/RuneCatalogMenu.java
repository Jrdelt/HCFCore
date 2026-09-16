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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only reference menu for every configured Rune tier, Arena included. */
public final class RuneCatalogMenu {

    private static final Map<RuneTier, Integer> TAB_SLOTS = Map.of(
            RuneTier.SIMPLE, 1, RuneTier.ELITE, 2, RuneTier.RARE, 3,
            RuneTier.LEGENDARY, 4, RuneTier.ARENA, 5, RuneTier.SEASONAL, 7);
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
        Map<String, List<Integer>> byEnchant = new LinkedHashMap<>();
        if (tier == RuneTier.SEASONAL) {
            for (String id : manager.seasonalIds()) {
                EnchantDefinition definition = manager.definition(id);
                if (definition == null || definition.isHidden() || definition.levels().isEmpty()) {
                    continue;
                }
                List<Integer> levelNums = new ArrayList<>();
                for (EnchantDefinition.Level lvl : definition.levels()) {
                    levelNums.add(lvl.level());
                }
                byEnchant.put(id, levelNums);
            }
        } else {
            for (RuneRollTable.Entry entry : manager.rollTable(tier).entries()) {
                byEnchant.computeIfAbsent(entry.enchantId(), ignored -> new ArrayList<>()).add(entry.level());
            }
        }
        for (Map.Entry<String, List<Integer>> grouped : byEnchant.entrySet()) {
            EnchantDefinition definition = manager.definition(grouped.getKey());
            List<Integer> levels = grouped.getValue().stream()
                    .filter(lvl -> definition != null && definition.level(lvl) != null)
                    .distinct()
                    .sorted().toList();
            if (definition == null || levels.isEmpty()) continue;
            ItemStack item = manager.createEnchantItem(grouped.getKey(), levels.getFirst(), tier);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(RuneFormatting.plain(RuneFormatting.smallCaps(definition.displayName()),
                    RuneFormatting.tierColor(tier)));
            List<Component> lore = new ArrayList<>();
            if (tier == RuneTier.SEASONAL) {
                // The same full per-level breakdown /runeinfo's detail view
                // shows on a real identified item (description, every
                // level's value, cooldown, tags, valid-on) -- unlike the
                // Seasonal Set preview card (which stays a short wrapped
                // description; that's a "what is this ability" browsing
                // view, not a spec sheet), this catalog tab is exactly where
                // a player comes to see the full numbers.
                lore.add(messages.getGui(player, "rune.info-description", "description",
                        RuneFormatting.smallCaps(definition.description())));
                lore.add(Component.empty());
                for (EnchantDefinition.Level levelConfig : definition.levels()) {
                    lore.add(messages.getGui(player, "rune.catalog-level-detail",
                            "level", RuneFormatting.roman(levelConfig.level()),
                            "maximum", levelConfig.level() == definition.maxLevel() ? "ᴍᴀx" : "",
                            "proc", RuneFormatting.percent(levelConfig.procChance()),
                            "value", RuneFormatting.percent(levelConfig.abilityValue())));
                }
                lore.add(Component.empty());
                double cooldownSeconds = definition.levels().isEmpty() ? 0D
                        : definition.levels().getFirst().setting("cooldown-seconds", 0D);
                lore.add(cooldownSeconds <= 0D ? messages.getGui(player, "rune.info-no-cooldown")
                        : messages.getGui(player, "rune.catalog-cooldown", "seconds", RuneFormatting.percent(cooldownSeconds)));
                lore.add(messages.getGui(player, "rune.info-tags", "tags", tagList(definition)));
                lore.add(messages.getGui(player, "rune.info-slots", "slots",
                        RuneFormatting.smallCaps(String.join(", ", definition.compatibleTypes()))));
            } else {
                // The description, then one line per level with that level's
                // real proc chance and ability value -- exactly what a player
                // is actually rolling for at every tier, not just a min-max
                // range. Application success/fail odds still only ever show
                // on the actual identified item, never here. Built as raw
                // Components (not through Messages) so every line can be
                // small-caps AND colored to match this tier, the same as the
                // item's own display name above -- a translatable lang-file
                // string can't carry a color that varies per tier.
                NamedTextColor tierColor = RuneFormatting.tierColor(tier);
                lore.add(RuneFormatting.plain(RuneFormatting.smallCaps(definition.description()), tierColor));
                for (EnchantDefinition.Level levelConfig : definition.levels()) {
                    String maxTag = levelConfig.level() == definition.maxLevel() ? " ᴍᴀx" : "";
                    String line = RuneFormatting.roman(levelConfig.level()) + maxTag + ": "
                            + RuneFormatting.percent(levelConfig.procChance()) + "% ᴘʀᴏᴄ | +"
                            + RuneFormatting.percent(levelConfig.abilityValue()) + "%";
                    lore.add(RuneFormatting.plain(line, tierColor));
                }
                lore.add(RuneFormatting.plain("ᴀᴘᴘʟɪᴇꜱ ᴛᴏ: "
                        + RuneFormatting.smallCaps(String.join(", ", definition.compatibleTypes())), tierColor));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
            entries.add(item);
        }
        return entries;
    }

    private static String tagList(EnchantDefinition definition) {
        if (definition.tags().isEmpty()) {
            return "-";
        }
        return definition.tags().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(", "));
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
