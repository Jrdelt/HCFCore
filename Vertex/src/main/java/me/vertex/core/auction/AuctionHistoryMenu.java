package me.vertex.core.auction;

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
import java.util.List;
import java.util.UUID;

/**
 * A player's own read-only Auction House history -- resolved (sold,
 * expired, or cancelled) listings they were the seller or buyer of.
 * Deliberately self-filtered only; the full cross-player staff audit log
 * stays behind {@code vertex.auction.logs} and {@code /ah logs}, never
 * exposed here. Also used, with a status filter, for the hub's "Expired
 * Items" page.
 */
public final class AuctionHistoryMenu {

    private static final int GRID_ROWS = 4;
    private static final int GRID_COLUMNS = 9;
    private static final int PAGE_SIZE = GRID_ROWS * GRID_COLUMNS;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")
            .withZone(ZoneId.systemDefault());
    public static final int SLOT_BACK = 0;
    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_NEXT_PAGE = 53;

    private AuctionHistoryMenu() {
    }

    public static void openHistory(Player player, AuctionManager manager, Messages messages, int requestedPage) {
        open(player, manager, messages, requestedPage, null, "auction.history-gui-title");
    }

    public static void openExpiredItems(Player player, AuctionManager manager, Messages messages, int requestedPage) {
        open(player, manager, messages, requestedPage, AuctionLogEntry.Status.EXPIRED, "auction.expired-items-gui-title");
    }

    private static void open(Player player, AuctionManager manager, Messages messages, int requestedPage,
            AuctionLogEntry.Status statusFilter, String titleKey) {
        manager.loadLogAsync(player.getUniqueId(), 1000, 0).thenAccept(entries -> {
            List<AuctionLogEntry> filtered = entries.stream()
                .filter(entry -> statusFilter == null || entry.status() == statusFilter)
                .toList();
            Bukkit.getScheduler().runTask(org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(AuctionHistoryMenu.class), () -> {
                if (player.isOnline()) {
                    openLoaded(player, messages, requestedPage, statusFilter, titleKey, filtered);
                }
            });
        });
    }

    private static void openLoaded(Player player, Messages messages, int requestedPage,
            AuctionLogEntry.Status statusFilter, String titleKey, List<AuctionLogEntry> entries) {
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) PAGE_SIZE));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));

        Holder holder = new Holder(statusFilter, page);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, titleKey));
        holder.inventory = inventory;

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
            int slot = gridSlot(i - start);
            inventory.setItem(slot, entryIcon(player, messages, entries.get(i)));
        }

        inventory.setItem(SLOT_PREV_PAGE, pageButton(messages, player, "auction.previous-page", Material.RED_DYE, page > 0));
        inventory.setItem(SLOT_NEXT_PAGE, pageButton(messages, player, "auction.next-page", Material.LIME_DYE, page < totalPages - 1));

        player.openInventory(inventory);
    }

    private static int gridSlot(int index) {
        int row = index / GRID_COLUMNS;
        int column = index % GRID_COLUMNS;
        return (row + 1) * 9 + column;
    }

    private static ItemStack entryIcon(Player viewer, Messages messages, AuctionLogEntry entry) {
        Material material = switch (entry.status()) {
            case SOLD -> Material.LIME_DYE;
            case EXPIRED -> Material.CLOCK;
            case CANCELLED -> Material.RED_DYE;
        };
        ItemStack icon = new ItemStack(material);
        ItemMeta meta = icon.getItemMeta();
        meta.displayName(noItalic(messages.getGui(viewer, "auction.history-entry-title", "summary", entry.itemSummary())));

        boolean wasSeller = entry.sellerUuid().equals(viewer.getUniqueId());
        String counterpart = wasSeller
                ? (entry.buyerUuid() == null ? "-" : nameOf(entry.buyerUuid()))
                : nameOf(entry.sellerUuid());
        String roleKey = wasSeller ? "auction.history-role-seller" : "auction.history-role-buyer";
        String statusKey = switch (entry.status()) {
            case SOLD -> "auction.history-status-sold";
            case EXPIRED -> "auction.history-status-expired";
            case CANCELLED -> "auction.history-status-cancelled";
        };

        icon.setItemMeta(withLore(meta, List.of(
                noItalic(messages.getGui(viewer, roleKey)),
                noItalic(messages.getGui(viewer, wasSeller ? "auction.history-buyer" : "auction.history-seller", "player", counterpart)),
                noItalic(messages.getGui(viewer, "auction.history-price", "price", String.valueOf(entry.price()))),
                noItalic(messages.getGui(viewer, statusKey)),
                noItalic(messages.getGui(viewer, "auction.history-date", "date", DATE_FORMAT.format(Instant.ofEpochMilli(entry.resolvedAtMillis()))))
        )));
        return icon;
    }

    private static ItemMeta withLore(ItemMeta meta, List<Component> lore) {
        meta.lore(lore);
        return meta;
    }

    private static String nameOf(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? "?" : name;
    }

    private static ItemStack backButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, "auction.back-button")));
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
        private final AuctionLogEntry.Status statusFilter;
        private final int page;
        private Inventory inventory;

        Holder(AuctionLogEntry.Status statusFilter, int page) {
            this.statusFilter = statusFilter;
            this.page = page;
        }

        public AuctionLogEntry.Status statusFilter() {
            return statusFilter;
        }

        public int page() {
            return page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
