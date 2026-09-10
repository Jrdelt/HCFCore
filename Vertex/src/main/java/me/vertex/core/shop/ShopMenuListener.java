package me.vertex.core.shop;

import me.vertex.core.bucket.SourceBucketManager;
import me.vertex.core.bucket.SourceBucketShopMenu;
import me.vertex.core.chunkbuster.ChunkBusterManager;
import me.vertex.core.chunkbuster.ChunkBusterShopMenu;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.spawner.SpawnerManager;
import me.vertex.core.spawner.SpawnerShopMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Handles buy/sell clicks and pagination in {@link ShopMenu}. */
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
        if (event.getInventory().getHolder() instanceof ShopMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ShopMenu.Holder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        boolean clickedTop = event.getClickedInventory() != null
                && event.getClickedInventory().getHolder() instanceof ShopMenu.Holder;
        if (!clickedTop) {
            return;
        }

        int slot = event.getRawSlot();
        if (holder.mode() == ShopMenu.Mode.CATEGORIES) {
            String categoryId = holder.categoryIdAtSlot(slot);
            if (ShopMenu.SPAWNERS_CATEGORY_ID.equals(categoryId)) {
                SpawnerShopMenu.open(player, spawnerManager, messages);
            } else if (ShopMenu.CHUNK_BUSTERS_CATEGORY_ID.equals(categoryId)) {
                ChunkBusterShopMenu.open(player, chunkBusterManager, messages);
            } else if (ShopMenu.SOURCE_BUCKETS_CATEGORY_ID.equals(categoryId)) {
                SourceBucketShopMenu.open(player, sourceBucketManager, messages);
            } else if (categoryId != null) {
                ShopMenu.openCategory(player, manager, spawnerManager, messages, categoryId, 0);
            }
            return;
        }

        if (slot == ShopMenu.SLOT_BACK) {
            ShopMenu.openCategories(player, manager, spawnerManager, messages);
            return;
        }
        // The buyable-spawner button now sits in the Spawners & Mob Drops
        // control row rather than beside the categories; the buyable-Chunk-
        // Buster button similarly sits in Raiding Materials' control row.
        if (ShopMenu.SPAWNERS_CATEGORY_ID.equals(holder.categoryIdAtSlot(slot))) {
            SpawnerShopMenu.open(player, spawnerManager, messages);
            return;
        }
        if (ShopMenu.CHUNK_BUSTERS_CATEGORY_ID.equals(holder.categoryIdAtSlot(slot))) {
            ChunkBusterShopMenu.open(player, chunkBusterManager, messages);
            return;
        }
        // The buyable-Source-Bucket button similarly sits in Miscellaneous's control row.
        if (ShopMenu.SOURCE_BUCKETS_CATEGORY_ID.equals(holder.categoryIdAtSlot(slot))) {
            SourceBucketShopMenu.open(player, sourceBucketManager, messages);
            return;
        }
        if (slot == ShopMenu.SLOT_PREV_PAGE) {
            ShopMenu.openCategory(player, manager, spawnerManager, messages, holder.categoryId(), holder.page() - 1);
            return;
        }
        if (slot == ShopMenu.SLOT_NEXT_PAGE) {
            ShopMenu.openCategory(player, manager, spawnerManager, messages, holder.categoryId(), holder.page() + 1);
            return;
        }

        Material material = holder.materialAtSlot(slot);
        if (material == null) {
            return;
        }

        if (event.isLeftClick()) {
            int amount = event.isShiftClick() ? ShopMenu.STACK_AMOUNT : manager.defaultBuyAmount();
            ShopManager.TradeOutcome outcome = manager.buy(player, material, amount);
            if (outcome.result() == ShopManager.TradeResult.OK) {
                player.sendMessage(messages.get(player, "shop.bought", "amount", String.valueOf(amount),
                        "block", material.name(), "total", EconomyHook.format(outcome.total())));
            } else {
                player.sendMessage(messages.get(player, buyFailureKey(outcome.result())));
            }
        } else if (event.isRightClick()) {
            int amount = event.isShiftClick() ? ShopMenu.STACK_AMOUNT : manager.defaultBuyAmount();
            ShopManager.TradeOutcome outcome = manager.sell(player, material, amount);
            if (outcome.result() == ShopManager.TradeResult.OK) {
                player.sendMessage(messages.get(player, "shop.sold", "amount", String.valueOf(amount),
                        "block", material.name(), "total", EconomyHook.format(outcome.total())));
            } else {
                player.sendMessage(messages.get(player, sellFailureKey(outcome.result())));
            }
        } else {
            return;
        }
        ShopMenu.openCategory(player, manager, spawnerManager, messages, holder.categoryId(), holder.page());
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
