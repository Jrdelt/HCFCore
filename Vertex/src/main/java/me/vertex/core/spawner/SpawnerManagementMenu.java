package me.vertex.core.spawner;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.faction.FTopManager;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
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

    private SpawnerManagementMenu() {
    }

    public static void open(Player player, SpawnerManager manager, Messages messages, Location location, SpawnerData data) {
        Holder holder = new Holder(location);
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.getGui(player, "spawner.management-title"));
        holder.inventory = inventory;

        SpawnerManager.MobConfig config = manager.getMobConfig(data.mobType());
        Component mobName = config != null ? MessageFormatter.deserialize(config.displayName())
                : Component.text(data.mobType().name());

        FTopManager.StackValue fTop = manager.getFTopValue(location, data);
        inventory.setItem(4, icon(Material.SPAWNER, mobName, List.of(
                messages.getGui(player, "spawner.info-stack-size", "size", String.valueOf(data.stackSize())),
                messages.getGui(player, "spawner.ftop-current", "amount", EconomyHook.format(fTop.currentValue()),
                        "percent", String.format(java.util.Locale.ROOT, "%.1f", fTop.percent())))));
        inventory.setItem(WITHDRAW_ONE_SLOT, icon(Material.CHEST,
                messages.getGui(player, "spawner.withdraw-one"), List.of()));
        inventory.setItem(WITHDRAW_ALL_SLOT, icon(Material.ENDER_CHEST,
                messages.getGui(player, "spawner.withdraw-all"), List.of()));

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
