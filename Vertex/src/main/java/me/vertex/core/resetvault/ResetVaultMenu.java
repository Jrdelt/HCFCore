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
import java.util.UUID;

/**
 * 54-slot player Reset Vault GUI.
 * Slots 0-44: 45 sequential vault positions.
 * Bottom row: controls (prev, info book, next, filler).
 * Content/state driven pagination.
 */
public final class ResetVaultMenu {

    private static final int VAULT_SLOTS_PER_PAGE = 45;
    private static final int PREV_SLOT = 45;
    private static final int INFO_SLOT = 48;
    private static final int NEXT_SLOT = 53;

    private ResetVaultMenu() {}

    public static void open(Player player, ResetVaultManager manager, Messages messages, int page, int accessBlockId) {
        if (manager.phase() == ResetVaultPhase.BACKUP_RUNNING || manager.phase() == ResetVaultPhase.BACKUP_PENDING) {
            player.sendMessage(messages.get(player, "reset-vault.session-read-only"));
            return;
        }
        int sanitizedPage = Math.max(0, page);
        Holder holder = new Holder(player.getUniqueId(), manager, messages, sanitizedPage, accessBlockId);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "reset-vault.gui.title"));
        holder.inventory = inventory;

        manager.startSession(player.getUniqueId(), accessBlockId);
        render(player, holder, inventory);
        player.openInventory(inventory);
    }

    public static void refresh(Player player, ResetVaultManager manager, Messages messages) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder) {
            render(player, holder, holder.inventory);
        }
    }

    public static void render(Player player, Holder holder, Inventory inventory) {
        inventory.clear();
        UUID uuid = holder.playerUuid;
        ResetVaultManager manager = holder.manager;
        Messages messages = holder.messages;
        int page = holder.page;

        ResetVaultData data = manager.getVaultData(uuid);
        int used = data.itemCount();
        int capacity = manager.totalCapacity(player);
        ResetVaultPhase phase = manager.phase();
        boolean canDeposit = phase == ResetVaultPhase.DEPOSIT && used < capacity;

        int maxLogical = canDeposit ? used : Math.max(0, used - 1);
        int totalPages = Math.max(1, (maxLogical / VAULT_SLOTS_PER_PAGE) + 1);
        if (page >= totalPages) {
            page = totalPages - 1;
            holder.page = page;
        }

        // Render slots 0-44
        for (int slot = 0; slot < VAULT_SLOTS_PER_PAGE; slot++) {
            int logicalIndex = page * VAULT_SLOTS_PER_PAGE + slot;
            if (logicalIndex < used) {
                // Stored item
                ItemStack item = data.items().get(logicalIndex).clone();
                ItemMeta meta = item.getItemMeta();
                List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                lore.add(Component.empty());
                if (phase == ResetVaultPhase.WITHDRAW) {
                    lore.add(messages.getGui(player, "reset-vault.gui.item.withdraw-hint").decoration(TextDecoration.ITALIC, false));
                } else {
                    lore.add(messages.getGui(player, "reset-vault.gui.item.deposit-hint").decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(lore);
                item.setItemMeta(meta);
                inventory.setItem(slot, item);
            } else if (logicalIndex == used && canDeposit) {
                // Nether Star deposit slot (at most one exists)
                ItemStack star = new ItemStack(Material.NETHER_STAR);
                ItemMeta meta = star.getItemMeta();
                meta.displayName(messages.getGui(player, "reset-vault.gui.nether-star.name").decoration(TextDecoration.ITALIC, false));
                meta.lore(messages.getGuiList(player, "reset-vault.gui.nether-star.lore").stream()
                        .map(line -> line.decoration(TextDecoration.ITALIC, false))
                        .toList());
                star.setItemMeta(meta);
                inventory.setItem(slot, star);
            } else if (logicalIndex == used && used >= capacity) {
                // First black pane after last unlocked slot
                ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
                ItemMeta meta = pane.getItemMeta();
                meta.displayName(messages.getGui(player, "reset-vault.gui.no-more-slots.name").decoration(TextDecoration.ITALIC, false));
                meta.lore(messages.getGuiList(player, "reset-vault.gui.no-more-slots.lore").stream()
                        .map(line -> line.decoration(TextDecoration.ITALIC, false))
                        .toList());
                pane.setItemMeta(meta);
                inventory.setItem(slot, pane);
            } else if (logicalIndex >= capacity) {
                // Locked slot
                ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
                ItemMeta meta = pane.getItemMeta();
                meta.displayName(messages.getGui(player, "reset-vault.gui.locked-slot.name").decoration(TextDecoration.ITALIC, false));
                pane.setItemMeta(meta);
                inventory.setItem(slot, pane);
            } else {
                // Empty position within capacity (after deposit star)
                ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
                ItemMeta meta = filler.getItemMeta();
                meta.displayName(Component.text(" "));
                filler.setItemMeta(meta);
                inventory.setItem(slot, filler);
            }
        }

        // Render bottom row
        ItemStack bottomFiller = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = bottomFiller.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        bottomFiller.setItemMeta(fillerMeta);

        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, bottomFiller);
        }

        // Previous Page (Slot 45)
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.RED_DYE);
            ItemMeta meta = prev.getItemMeta();
            meta.displayName(messages.getGui(player, "reset-vault.gui.prev-page", "page", String.valueOf(page))
                    .decoration(TextDecoration.ITALIC, false));
            prev.setItemMeta(meta);
            inventory.setItem(PREV_SLOT, prev);
        }

        // Book Info (Slot 48)
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta bookMeta = book.getItemMeta();
        bookMeta.displayName(messages.getGui(player, "reset-vault.gui.info.name").decoration(TextDecoration.ITALIC, false));
        bookMeta.lore(messages.getGuiList(player, "reset-vault.gui.info.lore",
                "used", String.valueOf(used),
                "total", String.valueOf(capacity),
                "phase", messages.getRaw(player, phase.langKey())).stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList());
        book.setItemMeta(bookMeta);
        inventory.setItem(INFO_SLOT, book);

        // Next Page (Slot 53)
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.GREEN_DYE);
            ItemMeta meta = next.getItemMeta();
            meta.displayName(messages.getGui(player, "reset-vault.gui.next-page", "page", String.valueOf(page + 2))
                    .decoration(TextDecoration.ITALIC, false));
            next.setItemMeta(meta);
            inventory.setItem(NEXT_SLOT, next);
        }
    }

    public static final class Holder implements InventoryHolder {
        final UUID playerUuid;
        final ResetVaultManager manager;
        final Messages messages;
        final int accessBlockId;
        int page;
        Inventory inventory;

        public Holder(UUID playerUuid, ResetVaultManager manager, Messages messages, int page, int accessBlockId) {
            this.playerUuid = playerUuid;
            this.manager = manager;
            this.messages = messages;
            this.page = page;
            this.accessBlockId = accessBlockId;
        }

        public int page() { return page; }
        public void setPage(int page) { this.page = page; }
        public UUID playerUuid() { return playerUuid; }
        public ResetVaultManager manager() { return manager; }
        public Messages messages() { return messages; }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
