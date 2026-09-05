package me.hcfcore.core.spawner;

import me.hcfcore.core.economy.EconomyHook;
import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

/** Opened by {@code /spawners} -- a catalog of every spawner type buyable from spawners.yml. */
public final class SpawnerShopMenu {

    private SpawnerShopMenu() {
    }

    public static void open(Player player, SpawnerManager manager, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.get(player, "spawner.shop-title"));
        holder.inventory = inventory;

        int slot = 0;
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
