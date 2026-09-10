package me.vertex.core.chunkbuster;

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
 * Opened from the "Buy Chunk Busters" control-row button in {@code /shop}'s
 * Raiding Materials category -- a catalog of every enabled Chunk Buster
 * type, priced per {@code chunkbuster.yml} (not {@code shop.yml}), same
 * precedent as {@code SpawnerShopMenu} being priced from {@code
 * spawners.yml} rather than becoming literal {@code ShopEntry} rows: a
 * Chunk Buster is a custom item, not a plain vanilla material, so it
 * cannot fit the dynamic-pricing block-market model {@code ShopEntry}
 * assumes.
 */
public final class ChunkBusterShopMenu {

    /** Reserved slot for the "back to shop" button -- type icons start at 1. */
    public static final int SLOT_BACK = 0;

    private ChunkBusterShopMenu() {
    }

    public static void open(Player player, ChunkBusterManager manager, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.get(player, "chunkbuster.shop-title"));
        holder.inventory = inventory;

        inventory.setItem(SLOT_BACK, backButton(player, messages));

        int slot = SLOT_BACK + 1;
        for (ChunkBusterType type : manager.enabledTypes()) {
            if (slot >= inventory.getSize()) {
                break;
            }
            holder.slotToType.put(slot, type);
            inventory.setItem(slot, buildIcon(player, messages, manager, type));
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

    private static ItemStack buildIcon(Player player, Messages messages, ChunkBusterManager manager, ChunkBusterType type) {
        ItemStack item = manager.createItem(type);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
        lore.add(messages.get(player, "chunkbuster.shop-price", "amount", EconomyHook.format(manager.price(type))));
        lore.add(messages.get(player, "chunkbuster.shop-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private final Map<Integer, ChunkBusterType> slotToType = new LinkedHashMap<>();
        private Inventory inventory;

        private Holder() {
        }

        public ChunkBusterType typeAt(int slot) {
            return slotToType.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
