package me.vertex.core.shop;

import me.vertex.core.bucket.SourceBucketManager;
import me.vertex.core.bucket.SourceBucketType;
import me.vertex.core.chunkbuster.ChunkBusterManager;
import me.vertex.core.chunkbuster.ChunkBusterType;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.spawner.SpawnerManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The normal paginated {@code /shop} browser. Custom products are displayed
 * in the same item grid as configured shop materials: Chunk Busters and
 * Source Buckets are part of Raiding Materials, while buyable Spawners are
 * part of Spawners & Mob Drops. No category uses a top-row shortcut or a
 * separate shop page.
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

    public static final String SPAWNERS_HOST_CATEGORY = "spawners_and_mob_drops";
    public static final String RAIDING_HOST_CATEGORY = "raiding";

    public enum Mode {
        CATEGORIES, ITEMS
    }

    public enum CustomProductKind {
        SPAWNER, CHUNK_BUSTER, SOURCE_BUCKET
    }

    /** A config-backed custom item placed in the ordinary shop item grid. */
    public record CustomProduct(CustomProductKind kind, String id) {
    }

    private ShopMenu() {
    }

    public static void openCategories(Player player, ShopManager manager, SpawnerManager ignoredSpawnerManager,
                                      Messages messages) {
        Holder holder = new Holder(Mode.CATEGORIES, null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get(player, "shop.gui-title"));
        holder.inventory = inventory;

        List<ShopCategory> categories = manager.categories();
        for (int slot = 0; slot < categories.size() && slot < 9; slot++) {
            ShopCategory category = categories.get(slot);
            inventory.setItem(slot, categoryIcon(player, messages, category));
            holder.slotCategoryIds.put(slot, category.id());
        }
        player.openInventory(inventory);
    }

    public static void openCategory(Player player, ShopManager manager, SpawnerManager spawnerManager,
                                    ChunkBusterManager chunkBusterManager, SourceBucketManager sourceBucketManager,
                                    Messages messages, String categoryId, int requestedPage) {
        ShopCategory category = manager.category(categoryId);
        if (category == null) {
            openCategories(player, manager, spawnerManager, messages);
            return;
        }

        List<CustomProduct> customProducts = customProducts(categoryId, spawnerManager, chunkBusterManager,
                sourceBucketManager);
        List<ShopEntry> entries = category.entries();
        int totalEntries = entries.size() + customProducts.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalEntries / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        Holder holder = new Holder(Mode.ITEMS, categoryId, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                messages.get(player, "shop.category-gui-title", "category", category.displayName()));
        holder.inventory = inventory;
        for (int slot = 0; slot < 9; slot++) inventory.setItem(slot, border());
        for (int slot = 45; slot < 54; slot++) inventory.setItem(slot, border());
        inventory.setItem(SLOT_BACK, backButton(player, messages));
        inventory.setItem(SLOT_BALANCE, balanceIcon(player, messages));

        int start = page * PAGE_SIZE;
        int end = Math.min(totalEntries, start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            int slot = gridSlot(index - start);
            if (index < entries.size()) {
                ShopEntry entry = entries.get(index);
                inventory.setItem(slot, blockIcon(player, manager, messages, entry));
                holder.slotMaterials.put(slot, entry.material());
            } else {
                CustomProduct product = customProducts.get(index - entries.size());
                inventory.setItem(slot, customProductIcon(player, messages, spawnerManager, chunkBusterManager,
                        sourceBucketManager, product));
                holder.slotProducts.put(slot, product);
            }
        }

        inventory.setItem(SLOT_PREV_PAGE,
                pageButton(messages, player, "shop.previous-page", Material.RED_DYE, page > 0));
        inventory.setItem(SLOT_NEXT_PAGE,
                pageButton(messages, player, "shop.next-page", Material.LIME_DYE, page < totalPages - 1));
        player.openInventory(inventory);
    }

    private static List<CustomProduct> customProducts(String categoryId, SpawnerManager spawnerManager,
                                                       ChunkBusterManager chunkBusterManager,
                                                       SourceBucketManager sourceBucketManager) {
        List<CustomProduct> products = new ArrayList<>();
        if (SPAWNERS_HOST_CATEGORY.equals(categoryId) && spawnerManager != null) {
            spawnerManager.getMobConfigs().stream()
                    .sorted(Comparator.comparing(config -> config.mobType().name()))
                    .forEach(config -> products.add(new CustomProduct(CustomProductKind.SPAWNER,
                            config.mobType().name())));
        }
        if (RAIDING_HOST_CATEGORY.equals(categoryId)) {
            if (chunkBusterManager != null) {
                for (ChunkBusterType type : chunkBusterManager.enabledTypes()) {
                    products.add(new CustomProduct(CustomProductKind.CHUNK_BUSTER, type.name()));
                }
            }
            if (sourceBucketManager != null) {
                for (SourceBucketType type : sourceBucketManager.enabledVariants()) {
                    products.add(new CustomProduct(CustomProductKind.SOURCE_BUCKET, type.id()));
                }
            }
        }
        return products;
    }

    private static ItemStack customProductIcon(Player player, Messages messages, SpawnerManager spawnerManager,
                                               ChunkBusterManager chunkBusterManager,
                                               SourceBucketManager sourceBucketManager, CustomProduct product) {
        return switch (product.kind()) {
            case SPAWNER -> spawnerIcon(player, messages, spawnerManager, product.id());
            case CHUNK_BUSTER -> chunkBusterIcon(player, messages, chunkBusterManager, product.id());
            case SOURCE_BUCKET -> sourceBucketIcon(player, messages, sourceBucketManager, product.id());
        };
    }

    private static ItemStack spawnerIcon(Player player, Messages messages, SpawnerManager manager, String id) {
        EntityType type = EntityType.valueOf(id);
        SpawnerManager.MobConfig config = manager.getMobConfig(type);
        ItemStack item = SpawnerManager.createSpawnerItem(type, MessageFormatter.deserialize(config.displayName()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(meta.displayName()));
        meta.lore(List.of(noItalic(messages.get(player, "spawner.shop-price", "amount", EconomyHook.format(config.price()))),
                noItalic(messages.get(player, "spawner.shop-hint"))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack chunkBusterIcon(Player player, Messages messages, ChunkBusterManager manager, String id) {
        ChunkBusterType type = ChunkBusterType.valueOf(id);
        ItemStack item = manager.createItem(type);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(noItalic(messages.get(player, "chunkbuster.shop-price", "amount", EconomyHook.format(manager.price(type)))));
        lore.add(noItalic(messages.get(player, "chunkbuster.shop-hint")));
        meta.displayName(noItalic(meta.displayName()));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack sourceBucketIcon(Player player, Messages messages, SourceBucketManager manager, String id) {
        SourceBucketType type = manager.variant(id);
        ItemStack item = manager.createItem(type);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
        lore.add(noItalic(messages.get(player, "sourcebucket.shop-price", "amount", EconomyHook.format(type.shopPrice()))));
        lore.add(noItalic(messages.get(player, "sourcebucket.shop-hint")));
        meta.displayName(noItalic(meta.displayName()));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static int gridSlot(int index) {
        return (index / GRID_COLUMNS + 1) * GRID_COLUMNS + index % GRID_COLUMNS;
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
        List<Component> lore = new ArrayList<>(List.of(buyLine, sellLine));
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
            if (!builder.isEmpty()) builder.append(' ');
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
        return component == null ? Component.empty() : component.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final Mode mode;
        private final String categoryId;
        private final int page;
        private final java.util.Map<Integer, Material> slotMaterials = new java.util.HashMap<>();
        private final java.util.Map<Integer, CustomProduct> slotProducts = new java.util.HashMap<>();
        private final java.util.Map<Integer, String> slotCategoryIds = new java.util.HashMap<>();
        private Inventory inventory;

        Holder(Mode mode, String categoryId, int page) {
            this.mode = mode;
            this.categoryId = categoryId;
            this.page = page;
        }

        public Mode mode() { return mode; }
        public String categoryId() { return categoryId; }
        public int page() { return page; }
        public Material materialAtSlot(int slot) { return slotMaterials.get(slot); }
        public CustomProduct productAtSlot(int slot) { return slotProducts.get(slot); }
        public String categoryIdAtSlot(int slot) { return slotCategoryIds.get(slot); }
        @Override public Inventory getInventory() { return inventory; }
    }
}
