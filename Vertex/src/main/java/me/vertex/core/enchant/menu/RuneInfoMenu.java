package me.vertex.core.enchant.menu;

import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.lang.Messages;
import me.vertex.core.user.User;
import me.vertex.core.user.UserManager;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code /runeinfo}'s GUI: every custom enchant currently applied to the
 * inspected item, pulled from the exact same {@link EnchantDefinition}
 * {@link RuneCatalogMenu} already renders from -- one source of truth, no
 * duplicated descriptions. Read-only, same {@code InventoryHolder} +
 * Bukkit {@code Inventory} pattern as every other menu in this package.
 */
public final class RuneInfoMenu {

    private static final List<Integer> CONTENT_SLOTS = List.of(
            19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43);

    private RuneInfoMenu() {
    }

    public record Entry(String enchantId, int level) {
    }

    public static void openHome(Player player, EnchantManager manager, RuneCooldownStore cooldowns,
            UserManager users, Messages messages, List<Entry> entries) {
        HomeHolder holder = new HomeHolder(entries);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "rune.info-title"));
        holder.inventory = inventory;
        User user = users == null ? null : users.get(player.getUniqueId());
        for (int index = 0; index < entries.size() && index < CONTENT_SLOTS.size(); index++) {
            Entry entry = entries.get(index);
            EnchantDefinition definition = manager.definition(entry.enchantId());
            if (definition == null || definition.level(entry.level()) == null) {
                continue;
            }
            inventory.setItem(CONTENT_SLOTS.get(index), summaryIcon(manager, cooldowns, user, messages, player, definition, entry.level()));
        }
        player.openInventory(inventory);
    }

    public static void openDetail(Player player, EnchantManager manager, RuneCooldownStore cooldowns,
            UserManager users, Messages messages, List<Entry> home, String enchantId, int level) {
        EnchantDefinition definition = manager.definition(enchantId);
        EnchantDefinition.Level levelConfig = definition == null ? null : definition.level(level);
        if (definition == null || levelConfig == null) {
            return;
        }
        DetailHolder holder = new DetailHolder(home);
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "rune.info-detail-title"));
        holder.inventory = inventory;
        User user = users == null ? null : users.get(player.getUniqueId());
        inventory.setItem(13, detailIcon(manager, cooldowns, user, messages, player, definition, level));
        inventory.setItem(22, button(Material.ARROW, messages.getGui(player, "rune.info-back"),
                List.of(messages.getGui(player, "rune.info-back-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack summaryIcon(EnchantManager manager, RuneCooldownStore cooldowns, User user,
            Messages messages, Player player, EnchantDefinition definition, int level) {
        ItemStack item = manager.createEnchantItem(definition.id(), level, tierGuess(manager, definition));
        ItemMeta meta = item.getItemMeta();
        EnchantDefinition.Level levelConfig = definition.level(level);
        List<Component> lore = new ArrayList<>();
        lore.add(messages.getGui(player, "rune.info-description", "description",
                RuneFormatting.smallCaps(definition.description())));
        lore.add(cooldownLine(cooldowns, user, messages, player, definition, levelConfig));
        lore.add(messages.getGui(player, "rune.info-tags", "tags", tagList(definition)));
        lore.add(messages.getGui(player, "rune.info-slots", "slots",
                RuneFormatting.smallCaps(String.join(", ", definition.compatibleTypes()))));
        lore.add(messages.getGui(player, "rune.info-click-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack detailIcon(EnchantManager manager, RuneCooldownStore cooldowns, User user,
            Messages messages, Player player, EnchantDefinition definition, int level) {
        ItemStack item = manager.createEnchantItem(definition.id(), level, tierGuess(manager, definition));
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.getGui(player, "rune.info-description", "description",
                RuneFormatting.smallCaps(definition.description())));
        lore.add(Component.empty());
        for (EnchantDefinition.Level levelConfig : definition.levels()) {
            lore.add(messages.getGui(player, "rune.catalog-level-detail",
                    "level", RuneFormatting.roman(levelConfig.level()),
                    "maximum", levelConfig.level() == definition.maxLevel() ? "ᴍᴀx" : "",
                    "value", RuneFormatting.percent(levelConfig.abilityValue()),
                    "success", RuneFormatting.percent(levelConfig.successRate()),
                    "failure", RuneFormatting.percent(100D - levelConfig.successRate())));
        }
        lore.add(Component.empty());
        lore.add(cooldownLine(cooldowns, user, messages, player, definition, definition.level(level)));
        lore.add(messages.getGui(player, "rune.info-tags", "tags", tagList(definition)));
        lore.add(messages.getGui(player, "rune.info-slots", "slots",
                RuneFormatting.smallCaps(String.join(", ", definition.compatibleTypes()))));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component cooldownLine(RuneCooldownStore cooldowns, User user, Messages messages, Player player,
            EnchantDefinition definition, EnchantDefinition.Level levelConfig) {
        double cooldownSeconds = levelConfig.setting("cooldown-seconds", 0D);
        if (cooldownSeconds <= 0D) {
            return messages.getGui(player, "rune.info-no-cooldown");
        }
        long remaining = cooldowns == null ? 0L : cooldowns.remainingMillis(user, definition.id());
        if (remaining > 0L) {
            return messages.getGui(player, "rune.info-cooldown-active", "seconds",
                    String.format(java.util.Locale.ROOT, "%.1f", remaining / 1000D));
        }
        return messages.getGui(player, "rune.info-cooldown-ready", "seconds",
                RuneFormatting.percent(cooldownSeconds));
    }

    private static String tagList(EnchantDefinition definition) {
        if (definition.tags().isEmpty()) {
            return "-";
        }
        return definition.tags().stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    /**
     * Only affects this icon's cosmetic tier color/model -- the applied
     * enchant's real origin tier isn't tracked by /runeinfo. Seasonal
     * enchants are checked first and directly, since they never have any
     * roll-table entry to find (by design, they're never rollable) and
     * would otherwise always fall through to the wrong SIMPLE guess.
     */
    private static me.vertex.core.enchant.RuneTier tierGuess(EnchantManager manager, EnchantDefinition definition) {
        if (manager.isSeasonal(definition.id())) {
            return me.vertex.core.enchant.RuneTier.SEASONAL;
        }
        for (me.vertex.core.enchant.RuneTier tier : me.vertex.core.enchant.RuneTier.values()) {
            if (!manager.rollTable(tier).isEmpty() && manager.rollTable(tier).entries().stream()
                    .anyMatch(entry -> entry.enchantId().equals(definition.id()))) {
                return tier;
            }
        }
        return me.vertex.core.enchant.RuneTier.SIMPLE;
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class HomeHolder implements InventoryHolder {
        private final List<Entry> entries;
        private Inventory inventory;

        private HomeHolder(List<Entry> entries) {
            this.entries = entries;
        }

        public List<Entry> entries() {
            return entries;
        }

        public Entry entryAt(int slot) {
            int index = CONTENT_SLOTS.indexOf(slot);
            return index < 0 || index >= entries.size() ? null : entries.get(index);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class DetailHolder implements InventoryHolder {
        private final List<Entry> home;
        private Inventory inventory;

        private DetailHolder(List<Entry> home) {
            this.home = home;
        }

        public List<Entry> home() {
            return home;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
