package me.hcfcore.core.spawner;

import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Opened by right-clicking a tracked spawner with an empty (or non-matching) hand. */
public final class SpawnerManagementMenu {

    public static final int WITHDRAW_ONE_SLOT = 2;
    public static final int WITHDRAW_ALL_SLOT = 3;
    public static final int SELL_ONE_SLOT = 5;
    public static final int SELL_ALL_SLOT = 6;

    private SpawnerManagementMenu() {
    }

    public static void open(Player player, SpawnerManager manager, Messages messages, Location location, SpawnerData data) {
        Holder holder = new Holder(location);
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get(player, "spawner.management-title"));
        holder.inventory = inventory;

        SpawnerManager.MobConfig config = manager.getMobConfig(data.mobType());
        Component mobName = config != null ? MessageFormatter.deserialize(config.displayName())
                : Component.text(data.mobType().name());

        inventory.setItem(4, icon(Material.SPAWNER, mobName, List.of(
                messages.get(player, "spawner.info-stack-size", "size", String.valueOf(data.stackSize())))));
        inventory.setItem(WITHDRAW_ONE_SLOT, icon(Material.CHEST,
                messages.get(player, "spawner.withdraw-one"), List.of()));
        inventory.setItem(WITHDRAW_ALL_SLOT, icon(Material.ENDER_CHEST,
                messages.get(player, "spawner.withdraw-all"), List.of()));

        double price = config != null ? config.price() : 0;
        double refund = price * manager.sellRefundPercent() / 100.0;
        inventory.setItem(SELL_ONE_SLOT, icon(Material.GOLD_INGOT,
                messages.get(player, "spawner.sell-one", "amount", String.valueOf((long) refund)), List.of()));
        inventory.setItem(SELL_ALL_SLOT, icon(Material.GOLD_BLOCK,
                messages.get(player, "spawner.sell-all", "amount", String.valueOf((long) (refund * data.stackSize()))),
                List.of()));

        player.openInventory(inventory);
    }

    private static ItemStack icon(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private final Location location;
        private Inventory inventory;

        private Holder(Location location) {
            this.location = location;
        }

        public Location location() {
            return location;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
