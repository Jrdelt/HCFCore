package me.vertex.core.gc;

import me.vertex.core.lang.Messages;
import me.vertex.core.util.Numbers;
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
import java.util.List;
import java.util.UUID;

/**
 * A player's own read-only GC transaction history. Deliberately self-
 * filtered only -- a full cross-player staff view stays behind {@code
 * vertex.gc.logs} and {@code /gc admin logs}, never exposed here. Copies
 * {@code AuctionHistoryMenu}'s exact shape: async load, paginated grid,
 * Back/Prev/Next.
 */
public final class GcLogMenu {

    private static final int GRID_ROWS = 4;
    private static final int GRID_COLUMNS = 9;
    private static final int PAGE_SIZE = GRID_ROWS * GRID_COLUMNS;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")
            .withZone(ZoneId.systemDefault());
    public static final int SLOT_BACK = 0;
    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_NEXT_PAGE = 53;

    private GcLogMenu() {
    }

    public static void open(Player player, GcManager manager, Messages messages, int requestedPage) {
        manager.loadLogAsync(player.getUniqueId(), 1000, 0).thenAccept(entries ->
                Bukkit.getScheduler().runTask(org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(GcLogMenu.class), () -> {
                    if (player.isOnline()) {
                        openLoaded(player, messages, requestedPage, entries);
                    }
                }));
    }

    private static void openLoaded(Player player, Messages messages, int requestedPage, List<GcLogEntry> entries) {
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        Holder holder = new Holder(page);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "gc.log-gui-title"));
        holder.setInventory(inventory);

        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, border());
        }
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, border());
        }
        inventory.setItem(SLOT_BACK, backButton(player, messages));

        int start = page * PAGE_SIZE;
        int end = Math.min(entries.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            inventory.setItem(gridSlot(i - start), entryIcon(player, messages, entries.get(i)));
        }

        inventory.setItem(SLOT_PREV_PAGE, pageButton(messages, player, "gc.previous-page", Material.RED_DYE, page > 0));
        inventory.setItem(SLOT_NEXT_PAGE, pageButton(messages, player, "gc.next-page", Material.LIME_DYE, page < totalPages - 1));

        player.openInventory(inventory);
    }

    private static int gridSlot(int index) {
        int row = index / GRID_COLUMNS;
        int column = index % GRID_COLUMNS;
        return (row + 1) * 9 + column;
    }

    private static ItemStack entryIcon(Player viewer, Messages messages, GcLogEntry entry) {
        Material material = switch (entry.action()) {
            case DEPOSIT, REDEEM, STAFF_GIVE, COINFLIP_PAYOUT, AUCTION_SALE, AUCTION_FEE_REFUND, AUCTION_REFUND,
                    COINFLIP_REFUND -> Material.LIME_DYE;
            case WITHDRAW, WITHDRAW_CODE, STAFF_REMOVE, COINFLIP_WAGER, AUCTION_FEE, AUCTION_PURCHASE -> Material.RED_DYE;
            case STAFF_SET, STAFF_ZERO -> Material.YELLOW_DYE;
        };
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(noItalic(messages.getGui(viewer, "gc.log-entry-title", "action", entry.action().name())));

        String actorName = entry.actorUuid() == null ? "-" : nameOf(entry.actorUuid());
        meta.lore(List.of(
                noItalic(messages.getGui(viewer, "gc.log-amount", "amount", Numbers.formatFull(entry.amount()))),
                noItalic(messages.getGui(viewer, "gc.log-balance-after", "balance", Numbers.formatFull(entry.balanceAfter()))),
                noItalic(messages.getGui(viewer, "gc.log-actor", "player", actorName)),
                noItalic(messages.getGui(viewer, "gc.log-note", "note", entry.note() == null ? "-" : entry.note())),
                noItalic(messages.getGui(viewer, "gc.log-date", "date", DATE_FORMAT.format(Instant.ofEpochMilli(entry.createdAtMillis()))))
        ));
        icon.setItemMeta(meta);
        return icon;
    }

    private static String nameOf(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? "?" : name;
    }

    private static ItemStack backButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, "gc.back-button")));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack pageButton(Messages messages, Player player, String key, Material material, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? material : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, key)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack border() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final int page;
        private Inventory inventory;

        Holder(int page) {
            this.page = page;
        }

        public int page() {
            return page;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
