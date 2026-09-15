package me.vertex.core.enchant.menu;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.RunePreferenceManager;
import me.vertex.core.enchant.RuneTier;
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

/**
 * One rune's levels, centered -- each icon uses that level's own configured
 * material (materials always stay per-level; only the display *amount*
 * here encodes the level number, visual-only). Right-click protects,
 * left-click unprotects, backed directly by {@link RunePreferenceManager}
 * (Phase 2), so no new storage exists for filters at all.
 */
public final class LevelFilterMenu {

    private static final List<Integer> CONTENT_SLOTS = List.of(
            10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43);
    public static final int BACK_SLOT = 49;

    private LevelFilterMenu() {
    }

    public static void open(Player player, EnchantManager manager, RunePreferenceManager preferences, Messages messages,
            RuneTier backTier, String enchantId) {
        EnchantDefinition definition = manager.definition(enchantId);
        if (definition == null) {
            return;
        }
        Holder holder = new Holder(backTier, enchantId);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                messages.getGui(player, "rune.filter-level-title", "rune", RuneFormatting.smallCaps(definition.displayName())));
        holder.inventory = inventory;
        for (int index = 0; index < definition.levels().size() && index < CONTENT_SLOTS.size(); index++) {
            int level = index + 1;
            inventory.setItem(CONTENT_SLOTS.get(index), levelIcon(player, manager, preferences, messages, definition, level));
        }
        inventory.setItem(BACK_SLOT, button(Material.ARROW, messages.getGui(player, "rune.catalog-back"),
                List.of(messages.getGui(player, "rune.filter-back-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack levelIcon(Player player, EnchantManager manager, RunePreferenceManager preferences,
            Messages messages, EnchantDefinition definition, int level) {
        ItemStack item = manager.createEnchantItem(definition.id(), level, RuneTier.SIMPLE);
        item.setAmount(Math.max(1, Math.min(64, level)));
        boolean protectedNow = Boolean.parseBoolean(
                preferences.get(player.getUniqueId(), definition.id(), "protected:" + level, "false"));
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        lore.add(Component.empty());
        lore.add(messages.getGui(player, protectedNow ? "rune.filter-protected" : "rune.filter-unprotected"));
        lore.add(messages.getGui(player, "rune.filter-level-protect-hint"));
        lore.add(messages.getGui(player, "rune.filter-level-unprotect-hint"));
        meta.lore(lore);
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

    public static Integer levelAt(int slot) {
        int index = CONTENT_SLOTS.indexOf(slot);
        return index < 0 ? null : index + 1;
    }

    public static final class Holder implements InventoryHolder {
        private final RuneTier backTier;
        private final String enchantId;
        private Inventory inventory;

        private Holder(RuneTier backTier, String enchantId) {
            this.backTier = backTier;
            this.enchantId = enchantId;
        }

        public RuneTier backTier() {
            return backTier;
        }

        public String enchantId() {
            return enchantId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
