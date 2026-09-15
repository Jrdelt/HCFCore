package me.vertex.core.resetvault;

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

/**
 * 27-slot confirmation preview menu before committing a deposit.
 * Center (slot 13): Processed preview of the item.
 * Left (slot 11): GREEN_STAINED_GLASS_PANE to Confirm.
 * Right (slot 15): RED_STAINED_GLASS_PANE to Cancel.
 */
public final class DepositConfirmMenu {

    public static final int CONFIRM_SLOT = 11;
    public static final int PREVIEW_SLOT = 13;
    public static final int CANCEL_SLOT = 15;

    private DepositConfirmMenu() {}

    public static void open(
            Player player,
            ResetVaultManager manager,
            Messages messages,
            int sourceSlot,
            ItemStack originalItem,
            ItemStack processedItem
    ) {
        Holder holder = new Holder(manager, messages, sourceSlot, originalItem, processedItem);
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "reset-vault.confirm.title"));
        holder.inventory = inventory;

        // Fill background
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" "));
        filler.setItemMeta(meta);
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        // Preview item at slot 13
        inventory.setItem(PREVIEW_SLOT, processedItem.clone());

        // Confirm button at slot 11
        ItemStack confirm = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta confirmMeta = confirm.getItemMeta();
        confirmMeta.displayName(messages.getGui(player, "reset-vault.confirm.accept-name").decoration(TextDecoration.ITALIC, false));
        confirmMeta.lore(messages.getGuiList(player, "reset-vault.confirm.accept-lore").stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList());
        confirm.setItemMeta(confirmMeta);
        inventory.setItem(CONFIRM_SLOT, confirm);

        // Cancel button at slot 15
        ItemStack cancel = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(messages.getGui(player, "reset-vault.confirm.cancel-name").decoration(TextDecoration.ITALIC, false));
        cancelMeta.lore(messages.getGuiList(player, "reset-vault.confirm.cancel-lore").stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList());
        cancel.setItemMeta(cancelMeta);
        inventory.setItem(CANCEL_SLOT, cancel);

        player.openInventory(inventory);
    }

    public static final class Holder implements InventoryHolder {
        final ResetVaultManager manager;
        final Messages messages;
        final int sourceSlot;
        final ItemStack originalItem;
        final ItemStack processedItem;
        Inventory inventory;

        public Holder(ResetVaultManager manager, Messages messages, int sourceSlot,
                      ItemStack originalItem, ItemStack processedItem) {
            this.manager = manager;
            this.messages = messages;
            this.sourceSlot = sourceSlot;
            this.originalItem = originalItem;
            this.processedItem = processedItem;
        }

        public int sourceSlot() { return sourceSlot; }
        public ItemStack originalItem() { return originalItem; }
        public ItemStack processedItem() { return processedItem; }
        public ResetVaultManager manager() { return manager; }
        public Messages messages() { return messages; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
