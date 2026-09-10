package me.vertex.core.shop;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.spawner.SpawnerManager;
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
 * {@code /shop} opens a one-row category picker ({@link #openCategories}) --
 * each icon is one {@code shop.yml} category. Clicking one opens that
 * category's own paginated buy/sell browser ({@link #openCategory}): row 0
 * has a Back button and your balance, rows 1-4 list that category's items,
 * row 5 pages.
 */
public final class ShopMenu {

    public static final int GRID_ROWS = 4;
    public static final int GRID_COLUMNS = 9;
    public static final int PAGE_SIZE = GRID_ROWS * GRID_COLUMNS;
    public static final int SLOT_BACK = 0;
    public static final int SLOT_BALANCE = 4;
    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_NEXT_PAGE = 53;
    public static final int STACK_AMOUNT = 64;

    /**
     * Sentinel category id for the Spawners button -- spawners aren't a real
     * {@code shop.yml} category (they're priced per mob type, not per
     * Material, so they don't fit {@link ShopEntry}'s model) and clicking it
     * opens {@link me.vertex.core.spawner.SpawnerShopMenu} directly instead
     * of the normal item-browsing view. Never collides with a real category
     * id, since those come from {@code shop.yml} map keys.
     */
    public static final String SPAWNERS_CATEGORY_ID = "__spawners__";

    /**
     * The shop.yml category the buyable-spawner button lives inside.
     * Spawner blocks are priced per mob type rather than per Material, so
     * they cannot be ordinary entries in that category -- the button sits in
     * its control row instead, which keeps spawners and mob drops in one
     * place without forcing them into a model that does not fit them.
     */
    public static final String SPAWNERS_HOST_CATEGORY = "spawners_and_mob_drops";

    /** Control-row slot holding that button, opposite the Back button. */
    public static final int SLOT_BUY_SPAWNERS = 8;

    /**
     * Sentinel category id for the Chunk Busters button -- same reasoning
     * as {@link #SPAWNERS_CATEGORY_ID}: a Chunk Buster is priced per type
     * in {@code chunkbuster.yml}, not per Material, so it cannot be a real
     * {@code ShopEntry} row either. Clicking it opens {@link
     * me.vertex.core.chunkbuster.ChunkBusterShopMenu} directly.
     */
    public static final String CHUNK_BUSTERS_CATEGORY_ID = "__chunk_busters__";

    /**
     * The shop.yml category the buyable-Chunk-Buster button lives inside.
     * Raiding Materials already holds every other destructive/raiding
     * consumable (TNT, obsidian, flint and steel, ...), so Chunk Busters
     * fit thematically without needing a whole new category slot in the
     * 9-wide /shop picker.
     */
    public static final String CHUNK_BUSTERS_HOST_CATEGORY = "raiding";

    /** Control-row slot holding that button -- the one border slot neither Back nor Balance occupies. */
    public static final int SLOT_BUY_CHUNK_BUSTERS = 7;

    public enum Mode {
        CATEGORIES, ITEMS
    }

    private ShopMenu() {
    }

    public static void openCategories(Player player, ShopManager manager, SpawnerManager spawnerManager,
                                       Messages messages) {
        Holder holder = new Holder(Mode.CATEGORIES, null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get(player, "shop.gui-title"));
        holder.inventory = inventory;

        List<ShopCategory> categories = manager.categories();
        int slot = 0;
        for (; slot < categories.size() && slot < 9; slot++) {
            ShopCategory category = categories.get(slot);
            inventory.setItem(slot, categoryIcon(player, messages, category));
            holder.slotCategoryIds.put(slot, category.id());
        }
        // No separate Spawners button here: buyable spawners live inside the
        // Spawners & Mob Drops category, so the two are not split across the
        // menu.

        player.openInventory(inventory);
    }

    private static ItemStack spawnersCategoryIcon(Player player, Messages messages) {
        ItemStack icon = new ItemStack(Material.SPAWNER);
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "shop.spawners-category-title")));
        meta.lore(List.of(noItalic(messages.get(player, "shop.category-open-lore"))));
        icon.setItemMeta(meta);
        return icon;
    }

    private static ItemStack chunkBustersCategoryIcon(Player player, Messages messages) {
        ItemStack icon = new ItemStack(Material.TNT);
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "shop.chunkbusters-category-title")));
        meta.lore(List.of(noItalic(messages.get(player, "shop.category-open-lore"))));
        icon.setItemMeta(meta);
        return icon;
    }

    public static void openCategory(Player player, ShopManager manager, SpawnerManager spawnerManager,
                                     Messages messages, String categoryId, int requestedPage) {
        ShopCategory category = manager.category(categoryId);
        if (category == null) {
            openCategories(player, manager, spawnerManager, messages);
            return;
        }
        List<ShopEntry> entries = category.entries();
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        Holder holder = new Holder(Mode.ITEMS, categoryId, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                messages.get(player, "shop.category-gui-title", "category", category.displayName()));
        holder.inventory = inventory;

        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, border());
        }
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, border());
        }
        inventory.setItem(SLOT_BACK, backButton(player, messages));
        inventory.setItem(SLOT_BALANCE, balanceIcon(player, messages));
        if (SPAWNERS_HOST_CATEGORY.equals(categoryId)
                && spawnerManager != null && !spawnerManager.getMobConfigs().isEmpty()) {
            inventory.setItem(SLOT_BUY_SPAWNERS, spawnersCategoryIcon(player, messages));
            holder.slotCategoryIds.put(SLOT_BUY_SPAWNERS, SPAWNERS_CATEGORY_ID);
        }
        if (CHUNK_BUSTERS_HOST_CATEGORY.equals(categoryId)) {
            inventory.setItem(SLOT_BUY_CHUNK_BUSTERS, chunkBustersCategoryIcon(player, messages));
            holder.slotCategoryIds.put(SLOT_BUY_CHUNK_BUSTERS, CHUNK_BUSTERS_CATEGORY_ID);
        }

        int start = page * PAGE_SIZE;
        int end = Math.min(entries.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            ShopEntry entry = entries.get(i);
            int slot = gridSlot(i - start);
            inventory.setItem(slot, blockIcon(player, manager, messages, entry));
            holder.slotMaterials.put(slot, entry.material());
        }

        inventory.setItem(SLOT_PREV_PAGE, pageButton(messages, player, "shop.previous-page", Material.RED_DYE, page > 0));
        inventory.setItem(SLOT_NEXT_PAGE, pageButton(messages, player, "shop.next-page", Material.LIME_DYE, page < totalPages - 1));

        player.openInventory(inventory);
    }

    private static int gridSlot(int index) {
        int row = index / GRID_COLUMNS;
        int column = index % GRID_COLUMNS;
        return (row + 1) * 9 + column;
    }

    private static ItemStack categoryIcon(Player player, Messages messages, ShopCategory category) {
        ItemStack icon = new ItemStack(category.icon());
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "shop.category-title", "category", category.displayName())));
        meta.lore(List.of(noItalic(messages.get(player, "shop.category-open-lore"))));
        icon.setItemMeta(meta);
        return icon;
    }

    private static ItemStack blockIcon(Player player, ShopManager manager, Messages messages, ShopEntry entry) {
        ItemStack icon = new ItemStack(entry.material());
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "shop.block-title", "block", displayName(entry.material()))));

        int direction = manager.priceDirection(entry.material());
        Component buyLine = noItalic(messages.get(player, "shop.block-buy-price",
                "price", EconomyHook.format(manager.buyPrice(entry.material()))));
        Component sellLine = noItalic(messages.get(player, "shop.block-sell-price",
                "price", EconomyHook.format(manager.sellPrice(entry.material()))));
        if (direction != 0) {
            Component indicator = Component.space().append(noItalic(messages.get(player,
                    direction > 0 ? "shop.block-price-indicator-above" : "shop.block-price-indicator-below")));
            buyLine = buyLine.append(indicator);
            sellLine = sellLine.append(indicator);
        }

        List<Component> lore = new java.util.ArrayList<>(List.of(buyLine, sellLine));
        lore.add(noItalic(messages.get(player, "shop.block-buy-lore")));
        lore.add(noItalic(messages.get(player, "shop.block-buy-stack-lore")));
        lore.add(noItalic(messages.get(player, "shop.block-sell-lore")));
        lore.add(noItalic(messages.get(player, "shop.block-sell-stack-lore")));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private static String displayName(Material material) {
        String[] words = material.name().toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return builder.toString();
    }

    private static ItemStack balanceIcon(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "shop.balance-title")));
        meta.lore(List.of(noItalic(messages.get(player, "shop.balance-lore", "balance", EconomyHook.getBalance(player)))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack backButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "shop.back-button")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack pageButton(Messages messages, Player player, String key, Material material, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? material : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, key)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack border() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final Mode mode;
        private final String categoryId;
        private final int page;
        private final java.util.Map<Integer, Material> slotMaterials = new java.util.HashMap<>();
        private final java.util.Map<Integer, String> slotCategoryIds = new java.util.HashMap<>();
        private Inventory inventory;

        Holder(Mode mode, String categoryId, int page) {
            this.mode = mode;
            this.categoryId = categoryId;
            this.page = page;
        }

        public Mode mode() {
            return mode;
        }

        public String categoryId() {
            return categoryId;
        }

        public int page() {
            return page;
        }

        public Material materialAtSlot(int slot) {
            return slotMaterials.get(slot);
        }

        public String categoryIdAtSlot(int slot) {
            return slotCategoryIds.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
