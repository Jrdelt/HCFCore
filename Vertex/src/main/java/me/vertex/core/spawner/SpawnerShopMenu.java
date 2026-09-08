package me.vertex.core.spawner;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opened as the "Spawners" category from {@code /shop} -- a catalog of
 * every spawner type buyable from spawners.yml. Priced per mob type
 * (spawners.yml), not per Material, so this stays its own menu rather
 * than becoming literal {@code ShopEntry} rows.
 */
public final class SpawnerShopMenu {

    /** Reserved slot for the "back to shop" button -- mob icons start at 1. */
    public static final int SLOT_BACK = 0;

    private SpawnerShopMenu() {
    }

    public static void open(Player player, SpawnerManager manager, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.get(player, "spawner.shop-title"));
        holder.inventory = inventory;

        inventory.setItem(SLOT_BACK, backButton(player, messages));

        int slot = SLOT_BACK + 1;
        for (SpawnerManager.MobConfig config : manager.getMobConfigs()) {
            if (slot >= inventory.getSize()) {
                break;
            }
            holder.slotToMob.put(slot, config.mobType());
            inventory.setItem(slot, buildIcon(player, messages, config));
            slot++;
        }
        player.openInventory(inventory);
    }

    private static ItemStack backButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.get(player, "shop.back-button")
                .decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack buildIcon(Player player, Messages messages, SpawnerManager.MobConfig config) {
        ItemStack item = SpawnerManager.createSpawnerItem(config.mobType(), MessageFormatter.deserialize(config.displayName()));
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(messages.get(player, "spawner.shop-price", "amount", EconomyHook.format(config.price())));
        lore.add(messages.get(player, "spawner.shop-hint"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private final Map<Integer, EntityType> slotToMob = new LinkedHashMap<>();
        private Inventory inventory;

        private Holder() {
        }

        public EntityType mobTypeAt(int slot) {
            return slotToMob.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
