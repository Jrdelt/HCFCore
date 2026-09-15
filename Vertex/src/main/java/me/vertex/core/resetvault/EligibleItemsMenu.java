package me.vertex.core.resetvault;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays deposit candidates from the player's hotbar (0-8) and main inventory (9-35).
 * Armor and offhand are strictly excluded.
 */
public final class EligibleItemsMenu {

    public static final int BACK_SLOT = 49;

    private EligibleItemsMenu() {}

    public static void open(Player player, ResetVaultManager manager, Messages messages) {
        Holder holder = new Holder(manager, messages);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "reset-vault.eligible.title"));
        holder.inventory = inventory;

        render(player, holder, inventory);
        player.openInventory(inventory);
    }

    public static void render(Player player, Holder holder, Inventory inventory) {
        inventory.clear();
        ResetVaultManager manager = holder.manager;
        Messages messages = holder.messages;

        // Player hotbar is slots 0-8, main inventory is slots 9-35. Exactly 36 slots.
        for (int i = 0; i < 36; i++) {
            ItemStack source = player.getInventory().getItem(i);
            if (source == null || source.getType().isAir()) {
                ItemStack empty = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
                ItemMeta meta = empty.getItemMeta();
                meta.displayName(Component.text(" "));
                empty.setItemMeta(meta);
                inventory.setItem(i, empty);
                continue;
            }

            ItemStack display = source.clone();
            ResetVaultManager.CheckResult check = manager.checkEligibility(source);
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());

            if (check.result() == ResetVaultManager.EligibilityResult.ELIGIBLE) {
                lore.add(messages.getGui(player, "reset-vault.eligible.click-to-deposit")
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                display.setItemMeta(meta);
                inventory.setItem(i, display);
            } else {
                lore.add(messages.getGui(player, check.rejectionKey())
                        .decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                display.setItemMeta(meta);
                inventory.setItem(i, display);
            }
        }

        // Fill row 5 (slots 36-44) and row 6 controls
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" "));
        filler.setItemMeta(meta);
        for (int slot = 36; slot < 54; slot++) {
            inventory.setItem(slot, filler);
        }

        // Back button
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.displayName(messages.getGui(player, "reset-vault.eligible.back-button").decoration(TextDecoration.ITALIC, false));
        back.setItemMeta(backMeta);
        inventory.setItem(BACK_SLOT, back);
    }

    public static final class Holder implements InventoryHolder {
        final ResetVaultManager manager;
        final Messages messages;
        Inventory inventory;

        public Holder(ResetVaultManager manager, Messages messages) {
            this.manager = manager;
            this.messages = messages;
        }

        public ResetVaultManager manager() { return manager; }
        public Messages messages() { return messages; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
