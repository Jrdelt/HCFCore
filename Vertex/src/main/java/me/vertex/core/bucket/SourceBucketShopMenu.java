package me.vertex.core.bucket;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opened from the "Buy Source Buckets" control-row button in {@code /shop}'s
 * Miscellaneous category -- a catalog of every enabled Source Bucket
 * variant (both liquids together, since there are only ever a handful),
 * priced per {@code sourcebuckets.yml} (not {@code shop.yml}). Mirrors
 * {@code ChunkBusterShopMenu} exactly, for the same reason: a Source Bucket
 * is a custom item with a config-driven price, not a plain vanilla
 * material, so it cannot fit the dynamic-pricing block-market model {@code
 * ShopEntry} assumes.
 */
public final class SourceBucketShopMenu {

    /** Reserved slot for the "back to shop" button -- variant icons start at 1. */
    public static final int SLOT_BACK = 0;

    private SourceBucketShopMenu() {
    }

    public static void open(Player player, SourceBucketManager manager, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.get(player, "sourcebucket.shop-title"));
        holder.inventory = inventory;

        inventory.setItem(SLOT_BACK, backButton(player, messages));

        int slot = SLOT_BACK + 1;
        for (SourceBucketType variant : manager.enabledVariants()) {
            if (slot >= inventory.getSize()) {
                break;
            }
            holder.slotToVariant.put(slot, variant);
            inventory.setItem(slot, buildIcon(player, messages, manager, variant));
            slot++;
        }
        player.openInventory(inventory);
    }

    private static ItemStack backButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get(player, "shop.back-button").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack buildIcon(Player player, Messages messages, SourceBucketManager manager,
            SourceBucketType variant) {
        ItemStack item = manager.createItem(variant);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
        lore.add(messages.get(player, "sourcebucket.shop-price", "amount", EconomyHook.format(variant.shopPrice())));
        lore.add(messages.get(player, "sourcebucket.shop-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private final Map<Integer, SourceBucketType> slotToVariant = new LinkedHashMap<>();
        private Inventory inventory;

        private Holder() {
        }

        public SourceBucketType variantAt(int slot) {
            return slotToVariant.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
