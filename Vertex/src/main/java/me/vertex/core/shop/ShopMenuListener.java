package me.vertex.core.shop;

import me.vertex.core.bucket.SourceBucketManager;
import me.vertex.core.bucket.SourceBucketType;
import me.vertex.core.chunkbuster.ChunkBusterManager;
import me.vertex.core.chunkbuster.ChunkBusterType;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.spawner.SpawnerManager;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/** Handles normal-material trades and custom-product purchases in {@link ShopMenu}. */
public final class ShopMenuListener implements Listener {

    private final ShopManager manager;
    private final SpawnerManager spawnerManager;
    private final ChunkBusterManager chunkBusterManager;
    private final SourceBucketManager sourceBucketManager;
    private final Messages messages;

    public ShopMenuListener(ShopManager manager, SpawnerManager spawnerManager, ChunkBusterManager chunkBusterManager,
                            SourceBucketManager sourceBucketManager, Messages messages) {
        this.manager = manager;
        this.spawnerManager = spawnerManager;
        this.chunkBusterManager = chunkBusterManager;
        this.sourceBucketManager = sourceBucketManager;
        this.messages = messages;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ShopMenu.Holder) event.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof ShopMenu.Holder)) return;

        int slot = event.getRawSlot();
        if (holder.mode() == ShopMenu.Mode.CATEGORIES) {
            String categoryId = holder.categoryIdAtSlot(slot);
            if (categoryId != null) openCategory(player, categoryId, 0);
            return;
        }
        if (slot == ShopMenu.SLOT_BACK) {
            ShopMenu.openCategories(player, manager, spawnerManager, messages);
            return;
        }
        if (slot == ShopMenu.SLOT_PREV_PAGE) {
            openCategory(player, holder.categoryId(), holder.page() - 1);
            return;
        }
        if (slot == ShopMenu.SLOT_NEXT_PAGE) {
            openCategory(player, holder.categoryId(), holder.page() + 1);
            return;
        }

        ShopMenu.CustomProduct product = holder.productAtSlot(slot);
        if (product != null) {
            if (event.isLeftClick()) purchaseCustomProduct(player, product);
            openCategory(player, holder.categoryId(), holder.page());
            return;
        }

        Material material = holder.materialAtSlot(slot);
        if (material == null) return;
        int amount = event.isShiftClick() ? ShopMenu.STACK_AMOUNT : manager.defaultBuyAmount();
        if (event.isLeftClick()) {
            ShopManager.TradeOutcome outcome = manager.buy(player, material, amount);
            if (outcome.result() == ShopManager.TradeResult.OK) {
                player.sendMessage(messages.get(player, "shop.bought", "amount", String.valueOf(amount),
                        "block", material.name(), "total", EconomyHook.format(outcome.total())));
            } else player.sendMessage(messages.get(player, buyFailureKey(outcome.result())));
        } else if (event.isRightClick()) {
            ShopManager.TradeOutcome outcome = manager.sell(player, material, amount);
            if (outcome.result() == ShopManager.TradeResult.OK) {
                player.sendMessage(messages.get(player, "shop.sold", "amount", String.valueOf(amount),
                        "block", material.name(), "total", EconomyHook.format(outcome.total())));
            } else player.sendMessage(messages.get(player, sellFailureKey(outcome.result())));
        } else return;
        openCategory(player, holder.categoryId(), holder.page());
    }

    private void purchaseCustomProduct(Player player, ShopMenu.CustomProduct product) {
        switch (product.kind()) {
            case SPAWNER -> purchaseSpawner(player, product.id());
            case CHUNK_BUSTER -> purchaseChunkBuster(player, product.id());
            case SOURCE_BUCKET -> purchaseSourceBucket(player, product.id());
        }
    }

    private void purchaseSpawner(Player player, String id) {
        EntityType type;
        try { type = EntityType.valueOf(id); } catch (IllegalArgumentException ignored) { return; }
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(type);
        if (config == null) return;
        if (!withdraw(player, config.price(), "spawner.no-economy", "spawner.cannot-afford")) return;
        give(player, SpawnerManager.createSpawnerItem(type, MessageFormatter.deserialize(config.displayName())));
        player.sendMessage(messages.get(player, "spawner.purchased", "amount", EconomyHook.format(config.price())));
    }

    private void purchaseChunkBuster(Player player, String id) {
        ChunkBusterType type;
        try { type = ChunkBusterType.valueOf(id); } catch (IllegalArgumentException ignored) { return; }
        if (!chunkBusterManager.isEnabled(type)) return;
        double price = chunkBusterManager.price(type);
        if (!withdraw(player, price, "chunkbuster.no-economy", "chunkbuster.cannot-afford")) return;
        give(player, chunkBusterManager.createItem(type));
        player.sendMessage(messages.get(player, "chunkbuster.purchased", "type", chunkBusterManager.displayName(type),
                "amount", EconomyHook.format(price)));
    }

    private void purchaseSourceBucket(Player player, String id) {
        SourceBucketType type = sourceBucketManager.variant(id);
        if (type == null || !type.enabled()) return;
        if (!withdraw(player, type.shopPrice(), "sourcebucket.no-economy", "sourcebucket.cannot-afford")) return;
        give(player, sourceBucketManager.createItem(type));
        player.sendMessage(messages.get(player, "sourcebucket.purchased",
                "amount", EconomyHook.format(type.shopPrice())));
    }

    private boolean withdraw(Player player, double price, String noEconomyKey, String cannotAffordKey) {
        if (!EconomyHook.isAvailable()) {
            player.sendMessage(messages.get(player, noEconomyKey));
            return false;
        }
        EconomyResponse response = EconomyHook.getEconomy().withdrawPlayer(player, price);
        if (response.transactionSuccess()) return true;
        player.sendMessage(messages.get(player, cannotAffordKey, "amount", EconomyHook.format(price)));
        return false;
    }

    private static void give(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private void openCategory(Player player, String categoryId, int page) {
        ShopMenu.openCategory(player, manager, spawnerManager, chunkBusterManager, sourceBucketManager, messages,
                categoryId, page);
    }

    private static String buyFailureKey(ShopManager.TradeResult result) {
        return switch (result) {
            case DISABLED -> "shop.disabled";
            case NO_ECONOMY -> "spawner.no-economy";
            case CANNOT_AFFORD -> "shop.cannot-afford";
            default -> "shop.trade-failed";
        };
    }

    private static String sellFailureKey(ShopManager.TradeResult result) {
        return switch (result) {
            case DISABLED -> "shop.disabled";
            case NO_ECONOMY -> "spawner.no-economy";
            case NOT_ENOUGH_ITEMS -> "shop.not-enough-items";
            default -> "shop.trade-failed";
        };
    }
}
