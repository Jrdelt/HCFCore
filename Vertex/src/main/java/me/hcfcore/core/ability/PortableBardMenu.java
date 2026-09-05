package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class PortableBardMenu {

    public static void open(Player player, Messages messages) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get(player, "ability.bard-gui-title"));
        holder.inventory = inventory;

        inventory.setItem(0, icon(Material.SUGAR,
                messages.get(player, "ability.bard-speed-name"), messages.get(player, "ability.bard-speed-lore")));
        inventory.setItem(2, icon(Material.BLAZE_POWDER,
                messages.get(player, "ability.bard-strength-name"), messages.get(player, "ability.bard-strength-lore")));
        inventory.setItem(4, icon(Material.IRON_INGOT,
                messages.get(player, "ability.bard-resistance-name"), messages.get(player, "ability.bard-resistance-lore")));
        inventory.setItem(6, icon(Material.GHAST_TEAR,
                messages.get(player, "ability.bard-regen-name"), messages.get(player, "ability.bard-regen-lore")));
        inventory.setItem(8, icon(Material.FEATHER,
                messages.get(player, "ability.bard-jumpboost-name"), messages.get(player, "ability.bard-jumpboost-lore")));

        player.openInventory(inventory);
    }

    private static ItemStack icon(Material material, Component name, Component loreLine) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(List.of(loreLine));
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
