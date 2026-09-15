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

import java.util.ArrayList;
import java.util.List;

/**
 * 54-slot paginated admin editor for Reset Vault blacklist entries.
 * Left-click removes an entry.
 * Right-clicking the Add position opens the admin selector menu.
 */
public final class BlacklistEditorMenu {

    private static final int PAGE_SIZE = 45;
    private static final int PREV_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private BlacklistEditorMenu() {}

    public static void open(Player player, ResetVaultManager manager, Messages messages, int page) {
        Holder holder = new Holder(manager, messages, Math.max(0, page));
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "reset-vault.blacklist.editor-title"));
        holder.inventory = inventory;

        render(player, holder, inventory);
        player.openInventory(inventory);
    }

    public static void render(Player player, Holder holder, Inventory inventory) {
        inventory.clear();
        Messages messages = holder.messages;
        List<ResetVaultStorage.BlacklistEntry> entries = holder.manager.blacklist();
        int page = holder.page;

        // Number of items to display includes entries plus 1 "Add" button
        int totalItems = entries.size() + 1;
        int totalPages = Math.max(1, (totalItems + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) {
            page = totalPages - 1;
            holder.page = page;
        }

        int start = page * PAGE_SIZE;

        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int itemIndex = start + slot;
            if (itemIndex < entries.size()) {
                ResetVaultStorage.BlacklistEntry entry = entries.get(itemIndex);
                ItemStack item;
                if ("MATERIAL".equalsIgnoreCase(entry.entryType())) {
                    Material mat = Material.matchMaterial(entry.entryKey());
                    item = new ItemStack(mat != null && !mat.isAir() && mat.isItem() ? mat : Material.BARRIER);
                } else {
                    item = new ItemStack(Material.PAPER);
                }

                ItemMeta meta = item.getItemMeta();
                meta.displayName(messages.getGui(player, "reset-vault.blacklist.entry-name",
                        "type", entry.entryType(),
                        "key", entry.entryKey()).decoration(TextDecoration.ITALIC, false));

                List<Component> lore = new ArrayList<>();
                lore.addAll(messages.getGuiList(player, "reset-vault.blacklist.entry-lore",
                        "id", String.valueOf(entry.id()),
                        "type", entry.entryType(),
                        "key", entry.entryKey(),
                        "rejection", entry.rejectionKey().isBlank() ? "default" : entry.rejectionKey()));
                lore.add(Component.empty());
                lore.add(messages.getGui(player, "reset-vault.blacklist.click-to-remove")
                        .decoration(TextDecoration.ITALIC, false));

                meta.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
                item.setItemMeta(meta);
                inventory.setItem(slot, item);

            } else if (itemIndex == entries.size()) {
                // The Add button position
                ItemStack add = new ItemStack(Material.ANVIL);
                ItemMeta meta = add.getItemMeta();
                meta.displayName(messages.getGui(player, "reset-vault.blacklist.add-name").decoration(TextDecoration.ITALIC, false));
                meta.lore(messages.getGuiList(player, "reset-vault.blacklist.add-lore").stream()
                        .map(l -> l.decoration(TextDecoration.ITALIC, false))
                        .toList());
                add.setItemMeta(meta);
                inventory.setItem(slot, add);
            } else {
                // Empty position
                ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
                ItemMeta meta = filler.getItemMeta();
                meta.displayName(Component.text(" "));
                filler.setItemMeta(meta);
                inventory.setItem(slot, filler);
            }
        }

        // Bottom row
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" "));
        filler.setItemMeta(meta);
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        if (page > 0) {
            ItemStack prev = new ItemStack(Material.RED_DYE);
            ItemMeta pMeta = prev.getItemMeta();
            pMeta.displayName(messages.getGui(player, "reset-vault.gui.prev-page", "page", String.valueOf(page))
                    .decoration(TextDecoration.ITALIC, false));
            prev.setItemMeta(pMeta);
            inventory.setItem(PREV_SLOT, prev);
        }

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta cMeta = close.getItemMeta();
        cMeta.displayName(messages.getGui(player, "reset-vault.gui.close").decoration(TextDecoration.ITALIC, false));
        close.setItemMeta(cMeta);
        inventory.setItem(CLOSE_SLOT, close);

        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.GREEN_DYE);
            ItemMeta nMeta = next.getItemMeta();
            nMeta.displayName(messages.getGui(player, "reset-vault.gui.next-page", "page", String.valueOf(page + 2))
                    .decoration(TextDecoration.ITALIC, false));
            next.setItemMeta(nMeta);
            inventory.setItem(NEXT_SLOT, next);
        }
    }

    public static final class Holder implements InventoryHolder {
        final ResetVaultManager manager;
        final Messages messages;
        int page;
        Inventory inventory;

        public Holder(ResetVaultManager manager, Messages messages, int page) {
            this.manager = manager;
            this.messages = messages;
            this.page = page;
        }

        public int page() { return page; }
        public void setPage(int page) { this.page = page; }
        public ResetVaultManager manager() { return manager; }
        public Messages messages() { return messages; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
