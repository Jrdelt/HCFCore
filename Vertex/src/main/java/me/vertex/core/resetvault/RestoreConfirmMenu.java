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

import java.util.List;

/**
 * 27-slot confirmation menu before applying a historical backup restore.
 */
public final class RestoreConfirmMenu {

    public static final int CONFIRM_SLOT = 11;
    public static final int INFO_SLOT = 13;
    public static final int CANCEL_SLOT = 15;

    private RestoreConfirmMenu() {}

    public static void open(Player player, ResetVaultManager manager, Messages messages, ResetVaultStorage.BackupRecord record) {
        Holder holder = new Holder(manager, messages, record);
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "reset-vault.restore.confirm-title"));
        holder.inventory = inventory;

        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fMeta = filler.getItemMeta();
        fMeta.displayName(Component.text(" "));
        filler.setItemMeta(fMeta);
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        // Info at 13
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(messages.getGui(player, "reset-vault.restore.confirm-info-name",
                "id", String.valueOf(record.id())).decoration(TextDecoration.ITALIC, false));
        infoMeta.lore(messages.getGuiList(player, "reset-vault.restore.confirm-info-lore",
                "id", String.valueOf(record.id()),
                "vaults", String.valueOf(record.vaultCount()),
                "size", String.valueOf(record.compressedSize())).stream()
                .map(l -> l.decoration(TextDecoration.ITALIC, false))
                .toList());
        info.setItemMeta(infoMeta);
        inventory.setItem(INFO_SLOT, info);

        // Confirm at 11
        ItemStack confirm = new ItemStack(Material.GREEN_STAINED_GLASS_PANE);
        ItemMeta cMeta = confirm.getItemMeta();
        cMeta.displayName(messages.getGui(player, "reset-vault.restore.confirm-accept-name").decoration(TextDecoration.ITALIC, false));
        confirm.setItemMeta(cMeta);
        inventory.setItem(CONFIRM_SLOT, confirm);

        // Cancel at 15
        ItemStack cancel = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.displayName(messages.getGui(player, "reset-vault.restore.confirm-cancel-name").decoration(TextDecoration.ITALIC, false));
        cancel.setItemMeta(cancelMeta);
        inventory.setItem(CANCEL_SLOT, cancel);

        player.openInventory(inventory);
    }

    public static final class Holder implements InventoryHolder {
        final ResetVaultManager manager;
        final Messages messages;
        final ResetVaultStorage.BackupRecord record;
        Inventory inventory;

        public Holder(ResetVaultManager manager, Messages messages, ResetVaultStorage.BackupRecord record) {
            this.manager = manager;
            this.messages = messages;
            this.record = record;
        }

        public ResetVaultStorage.BackupRecord record() { return record; }
        public ResetVaultManager manager() { return manager; }
        public Messages messages() { return messages; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
