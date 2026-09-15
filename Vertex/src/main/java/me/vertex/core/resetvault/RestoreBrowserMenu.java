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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 54-slot paginated browser of historical Reset Vault backups.
 * Requires highest recovery permission (vertex.reset.recover).
 */
public final class RestoreBrowserMenu {

    private static final int PAGE_SIZE = 45;
    private static final int PREV_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private RestoreBrowserMenu() {}

    public static void open(Player player, ResetVaultManager manager, Messages messages, int page) {
        manager.plugin().getServer().getScheduler().runTaskAsynchronously(manager.plugin(), () -> {
            try {
                List<ResetVaultStorage.BackupRecord> history = manager.storage().loadBackupHistory();
                Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                    Holder holder = new Holder(manager, messages, history, Math.max(0, page));
                    Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "reset-vault.restore.browser-title"));
                    holder.inventory = inventory;
                    render(player, holder, inventory);
                    player.openInventory(inventory);
                });
            } catch (Exception e) {
                player.sendMessage(messages.get(player, "reset-vault.storage-error"));
            }
        });
    }

    public static void render(Player player, Holder holder, Inventory inventory) {
        inventory.clear();
        Messages messages = holder.messages;
        List<ResetVaultStorage.BackupRecord> history = holder.history;
        int page = holder.page;

        int totalPages = Math.max(1, (history.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) {
            page = totalPages - 1;
            holder.page = page;
        }

        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, history.size());

        for (int i = start; i < end; i++) {
            ResetVaultStorage.BackupRecord record = history.get(i);
            int slot = i - start;

            ItemStack item = new ItemStack(record.isSelectable() ? Material.PAPER : Material.BARRIER);
            ItemMeta meta = item.getItemMeta();
            String dateStr = FORMATTER.format(Instant.ofEpochMilli(record.timestamp()));
            meta.displayName(messages.getGui(player, "reset-vault.restore.item-name",
                    "id", String.valueOf(record.id()),
                    "date", dateStr).decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.addAll(messages.getGuiList(player, "reset-vault.restore.item-lore",
                    "id", String.valueOf(record.id()),
                    "map", record.mapLabel(),
                    "initiator", record.initiator(),
                    "vaults", String.valueOf(record.vaultCount()),
                    "size", String.valueOf(record.compressedSize()),
                    "status", record.status(),
                    "date", dateStr));

            meta.lore(lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }

        // Fill remaining top slots if any
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" "));
        filler.setItemMeta(meta);
        for (int i = end - start; i < PAGE_SIZE; i++) {
            inventory.setItem(i, filler);
        }

        // Bottom row
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
        final List<ResetVaultStorage.BackupRecord> history;
        int page;
        Inventory inventory;

        public Holder(ResetVaultManager manager, Messages messages, List<ResetVaultStorage.BackupRecord> history, int page) {
            this.manager = manager;
            this.messages = messages;
            this.history = history;
            this.page = page;
        }

        public int page() { return page; }
        public void setPage(int page) { this.page = page; }
        public List<ResetVaultStorage.BackupRecord> history() { return history; }
        public ResetVaultManager manager() { return manager; }
        public Messages messages() { return messages; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
