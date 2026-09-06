package me.vertex.core.blueprint;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** Confirmation shown immediately after a Blueprint beacon is placed. */
public final class BlueprintActivationMenu {

    static final int CANCEL_SLOT = 2;
    static final int INFO_SLOT = 4;
    static final int ENABLE_SLOT = 6;

    private BlueprintActivationMenu() {
    }

    public static void open(Player player, Messages messages, Location anchor, BlueprintTemplate template) {
        Holder holder = new Holder(anchor, template.name());
        Inventory inventory = Bukkit.createInventory(holder, 9, messages.get(player, "blueprint.activate-title"));
        holder.inventory = inventory;

        ItemStack info = new ItemStack(Material.BEACON);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(noItalic(MessageFormatter.deserialize(template.displayName())));
        infoMeta.lore(List.of(noItalic(messages.get(player, "blueprint.activate-info-lore"))));
        info.setItemMeta(infoMeta);
        inventory.setItem(INFO_SLOT, info);

        ItemStack enable = new ItemStack(Material.LIME_DYE);
        ItemMeta enableMeta = enable.getItemMeta();
        enableMeta.displayName(noItalic(messages.get(player, "blueprint.activate-enable-title")));
        enableMeta.lore(List.of(noItalic(messages.get(player, "blueprint.activate-enable-lore"))));
        enable.setItemMeta(enableMeta);
        inventory.setItem(ENABLE_SLOT, enable);

        ItemStack cancel = new ItemStack(Material.BARRIER);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(noItalic(messages.get(player, "blueprint.activate-cancel-title")));
        cancelMeta.lore(List.of(noItalic(messages.get(player, "blueprint.activate-cancel-lore"))));
        cancel.setItemMeta(cancelMeta);
        inventory.setItem(CANCEL_SLOT, cancel);

        player.openInventory(inventory);
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    static final class Holder implements InventoryHolder {
        private final Location anchor;
        private final String templateName;
        private Inventory inventory;

        Holder(Location anchor, String templateName) {
            this.anchor = anchor.clone();
            this.templateName = templateName;
        }

        Location anchor() {
            return anchor.clone();
        }

        String templateName() {
            return templateName;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
