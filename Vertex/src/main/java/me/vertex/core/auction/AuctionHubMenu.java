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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * "Your Auction Page" -- a small hub linking to a player's own Active
 * Listings, Expired Items, Collection Box, Watchlist, and Auction
 * History. Opened from the {@code player_head} button in the main
 * {@link AuctionMenu} browser.
 */
public final class AuctionHubMenu {

    private static final int SIZE = 27;
    public static final int SLOT_CLOSE = 0;
    public static final int SLOT_ACTIVE_LISTINGS = 9;
    public static final int SLOT_EXPIRED_ITEMS = 11;
    public static final int SLOT_COLLECTION_BOX = 13;
    public static final int SLOT_WATCHLIST = 15;
    public static final int SLOT_HISTORY = 17;

    private AuctionHubMenu() {
    }

    public static void open(Player player, AuctionManager manager, Messages messages) {
        // The hub used to execute two SQL queries (including up to 1,000 log
        // rows) from the inventory-click thread. Fetch those values first,
        // then create Bukkit objects only once we are back on the main thread.
        CompletableFuture<List<AuctionLogEntry>> logs = manager.loadLogAsync(player.getUniqueId(), 1000, 0);
        CompletableFuture<List<ItemStack>> claims = manager.loadClaimItemsAsync(player.getUniqueId());
        CompletableFuture.allOf(logs, claims).thenRun(() -> Bukkit.getScheduler().runTask(
                org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(AuctionHubMenu.class),
                () -> {
                    if (player.isOnline()) {
                        openLoaded(player, manager, messages, logs.join(), claims.join());
                    }
                }));
    }

    private static void openLoaded(Player player, AuctionManager manager, Messages messages,
            List<AuctionLogEntry> logs, List<ItemStack> claims) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, messages.getGui(player, "auction.hub-gui-title"));
        holder.inventory = inventory;

        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, border());
        }
        inventory.setItem(SLOT_CLOSE, closeButton(player, messages));
        inventory.setItem(SLOT_ACTIVE_LISTINGS, actionIcon(player, messages, Material.ENDER_CHEST,
                "auction.hub-active-listings-title", "auction.hub-active-listings-lore", "auction.hub-active-listings-count",
                manager.activeListings().stream().filter(l -> l.sellerUuid().equals(player.getUniqueId())).count()));
        long expiredCount = logs.stream().filter(entry -> entry.status() == AuctionLogEntry.Status.EXPIRED).count();
        inventory.setItem(SLOT_EXPIRED_ITEMS, actionIcon(player, messages, Material.CHEST,
                "auction.hub-expired-items-title", "auction.hub-expired-items-lore", "auction.hub-expired-items-count", expiredCount));
        inventory.setItem(SLOT_COLLECTION_BOX, actionIcon(player, messages, Material.YELLOW_SHULKER_BOX,
                "auction.hub-collection-box-title", "auction.hub-collection-box-lore", "auction.hub-collection-box-count",
                claims.size()));
        inventory.setItem(SLOT_WATCHLIST, actionIcon(player, messages, Material.SPYGLASS,
                "auction.hub-watchlist-title", "auction.hub-watchlist-lore", "auction.hub-watchlist-count",
                manager.watchedListings(player.getUniqueId()).size()));
        inventory.setItem(SLOT_HISTORY, actionIcon(player, messages, Material.OAK_SIGN,
                "auction.hub-history-title", "auction.hub-history-lore", null, -1));

        player.openInventory(inventory);
    }

    private static ItemStack actionIcon(Player player, Messages messages, Material material,
            String titleKey, String loreKey, String countKey, long count) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, titleKey)));
        java.util.List<Component> lore = new java.util.ArrayList<>();
        if (countKey != null) {
            lore.add(noItalic(messages.getGui(player, countKey, "count", String.valueOf(count))));
        }
        lore.add(noItalic(messages.getGui(player, loreKey)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack closeButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, "auction.hub-close-button")));
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
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
