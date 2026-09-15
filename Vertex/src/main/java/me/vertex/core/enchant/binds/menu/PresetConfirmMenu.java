package me.vertex.core.enchant.binds.menu;

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

import java.util.List;

/** Green/red confirmation, reused for both "Update Preset" and "Delete Preset" -- closing the GUI always cancels. */
public final class PresetConfirmMenu {

    public static final int CANCEL_SLOT = 11;
    public static final int CONFIRM_SLOT = 15;

    public enum Action {
        UPDATE, DELETE
    }

    private PresetConfirmMenu() {
    }

    public static void open(Player player, Messages messages, Action action, int presetIndex) {
        Holder holder = new Holder(action, presetIndex);
        String titleKey = action == Action.UPDATE ? "binds.confirm-update-title" : "binds.confirm-delete-title";
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, titleKey, "preset", String.valueOf(presetIndex)));
        holder.inventory = inventory;
        for (int slot = 0; slot < 27; slot++) {
            if (slot != CANCEL_SLOT && slot != CONFIRM_SLOT && slot != 13) {
                inventory.setItem(slot, filler());
            }
        }
        String centerKey = action == Action.UPDATE ? "binds.confirm-update-center" : "binds.confirm-delete-center";
        inventory.setItem(13, button(Material.PAPER, messages.getGui(player, centerKey, "preset", String.valueOf(presetIndex)), List.of()));
        inventory.setItem(CANCEL_SLOT, button(Material.RED_STAINED_GLASS_PANE,
                messages.getGui(player, "binds.confirm-cancel"), List.of(messages.getGui(player, "binds.confirm-cancel-lore"))));
        inventory.setItem(CONFIRM_SLOT, button(Material.GREEN_STAINED_GLASS_PANE,
                messages.getGui(player, "binds.confirm-confirm"), List.of(messages.getGui(player, "binds.confirm-confirm-lore"))));
        player.openInventory(inventory);
    }

    private static ItemStack filler() {
        return button(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
    }

    private static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    public static final class Holder implements InventoryHolder {
        private final Action action;
        private final int presetIndex;
        private Inventory inventory;

        private Holder(Action action, int presetIndex) {
            this.action = action;
            this.presetIndex = presetIndex;
        }

        public Action action() {
            return action;
        }

        public int presetIndex() {
            return presetIndex;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
