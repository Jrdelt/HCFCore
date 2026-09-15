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
 * 27-slot confirmation GUI for /rv backup.
 * Requires 2 green clicks to arm and confirm (30-second expiry).
 */
public final class BackupConfirmMenu {

    public static final int CONFIRM_SLOT = 11;
    public static final int CANCEL_SLOT = 15;
    private static final long ARM_EXPIRY_MILLIS = 30_000L;

    private BackupConfirmMenu() {}

    public static void open(Player player, ResetVaultManager manager, Messages messages) {
        Holder holder = new Holder(manager, messages);
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "reset-vault.backup.confirm-title"));
        holder.inventory = inventory;

        render(player, holder, inventory);
        player.openInventory(inventory);
    }

    public static void render(Player player, Holder holder, Inventory inventory) {
        inventory.clear();
        Messages messages = holder.messages;

        // Fill background
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" "));
        filler.setItemMeta(meta);
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler);
        }

        // Green button (Confirm)
        ItemStack green = new ItemStack(Material.GREEN_STAINED_GLASS);
        ItemMeta greenMeta = green.getItemMeta();
        if (holder.isArmed()) {
            greenMeta.displayName(messages.getGui(player, "reset-vault.backup.armed-name").decoration(TextDecoration.ITALIC, false));
            greenMeta.lore(messages.getGuiList(player, "reset-vault.backup.armed-lore").stream()
                    .map(l -> l.decoration(TextDecoration.ITALIC, false))
                    .toList());
            greenMeta.setEnchantmentGlintOverride(true);
        } else {
            greenMeta.displayName(messages.getGui(player, "reset-vault.backup.confirm-name").decoration(TextDecoration.ITALIC, false));
            greenMeta.lore(messages.getGuiList(player, "reset-vault.backup.confirm-lore").stream()
                    .map(l -> l.decoration(TextDecoration.ITALIC, false))
                    .toList());
        }
        green.setItemMeta(greenMeta);
        inventory.setItem(CONFIRM_SLOT, green);

        // Red button (Cancel)
        ItemStack red = new ItemStack(Material.RED_STAINED_GLASS);
        ItemMeta redMeta = red.getItemMeta();
        redMeta.displayName(messages.getGui(player, "reset-vault.backup.cancel-name").decoration(TextDecoration.ITALIC, false));
        red.setItemMeta(redMeta);
        inventory.setItem(CANCEL_SLOT, red);
    }

    public static final class Holder implements InventoryHolder {
        final ResetVaultManager manager;
        final Messages messages;
        boolean armed;
        long armedAt;
        Inventory inventory;

        public Holder(ResetVaultManager manager, Messages messages) {
            this.manager = manager;
            this.messages = messages;
        }

        public boolean isArmed() {
            if (!armed) return false;
            if (System.currentTimeMillis() - armedAt > ARM_EXPIRY_MILLIS) {
                armed = false;
                return false;
            }
            return true;
        }

        public void arm() {
            this.armed = true;
            this.armedAt = System.currentTimeMillis();
        }

        public ResetVaultManager manager() { return manager; }
        public Messages messages() { return messages; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
