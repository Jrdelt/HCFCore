package me.vertex.core.resetvault;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 54-slot admin item selector GUI for adding blacklist entries.
 * Shows items from the admin's hotbar (0-8) and main inventory (9-35).
 * Representative items are NOT consumed.
 */
public final class AdminItemSelectorMenu {

    public static final int BACK_SLOT = 49;

    private AdminItemSelectorMenu() {}

    public static void open(Player player, ResetVaultManager manager, Messages messages) {
        Holder holder = new Holder(manager, messages);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "reset-vault.blacklist.selector-title"));
        holder.inventory = inventory;

        render(player, holder, inventory);
        player.openInventory(inventory);
    }

    public static void render(Player player, Holder holder, Inventory inventory) {
        inventory.clear();
        Messages messages = holder.messages;

        // Display slots 0-35 from player inventory
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
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(messages.getGui(player, "reset-vault.blacklist.click-to-blacklist")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            display.setItemMeta(meta);
            inventory.setItem(i, display);
        }

        // Fill remaining slots
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fMeta = filler.getItemMeta();
        fMeta.displayName(Component.text(" "));
        filler.setItemMeta(fMeta);
        for (int i = 36; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        // Back button
        ItemStack back = new ItemStack(Material.ARROW);
        ItemMeta bMeta = back.getItemMeta();
        bMeta.displayName(messages.getGui(player, "reset-vault.blacklist.back-button").decoration(TextDecoration.ITALIC, false));
        back.setItemMeta(bMeta);
        inventory.setItem(BACK_SLOT, back);
    }

    public static String determineBlacklistKey(ItemStack item, ResetVaultManager manager) {
        if (item == null || item.getType().isAir()) return null;

        if (manager.plugin().getServer().getPluginManager().isPluginEnabled("Vertex")) {
            // Check backpack
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                    if ("backpack".equalsIgnoreCase(key.getKey())) {
                        return "backpack";
                    }
                    if ("rv_slot_token".equalsIgnoreCase(key.getKey())) {
                        return "rv_slot_token";
                    }
                    if (key.getKey().contains("ability") || key.getKey().contains("wand") || key.getKey().contains("chunk_buster")) {
                        return key.getKey();
                    }
                }
            }
        }

        return item.getType().name();
    }

    public static String determineEntryType(ItemStack item, ResetVaultManager manager) {
        if (item == null || item.getType().isAir()) return "MATERIAL";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && !meta.getPersistentDataContainer().isEmpty()) {
            for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
                if (key.getNamespace().equalsIgnoreCase("vertex")) {
                    return "CUSTOM_ITEM";
                }
            }
        }
        return "MATERIAL";
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
