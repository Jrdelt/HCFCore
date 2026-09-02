package me.hcfcore.core.kit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Read-only view of a kit's armor + contents. Top row (first 4 slots) is
 * armor, everything below mirrors the 36-slot player inventory layout.
 */
public final class KitPreviewMenu {

    public static void open(Player player, Kit kit) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 45,
                Component.text("Preview: " + kit.getName(), NamedTextColor.DARK_AQUA));
        holder.inventory = inventory;

        ItemStack[] armor = kit.getArmor();
        for (int i = 0; i < armor.length && i < 4; i++) {
            if (armor[i] != null) {
                inventory.setItem(i, armor[i].clone());
            }
        }

        ItemStack[] contents = kit.getContents();
        for (int i = 0; i < contents.length && i < 36; i++) {
            if (contents[i] != null) {
                inventory.setItem(9 + i, contents[i].clone());
            }
        }

        player.openInventory(inventory);
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
