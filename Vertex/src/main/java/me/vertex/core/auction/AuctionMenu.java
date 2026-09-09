package me.vertex.core.auction;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.util.Numbers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The Auction House browser: the player head opens the personal auction hub;
 * rows 1-4 list
 * listings (filtered/sorted per the current view); row 5 pages. Left-click
 * buys, shift-click cancels (seller or {@code vertex.auction.remove}),
 * right-click toggles the watchlist.
 */
public final class AuctionMenu {

    public static final int GRID_ROWS = 4;
    public static final int GRID_COLUMNS = 9;
    public static final int PAGE_SIZE = GRID_ROWS * GRID_COLUMNS;
    public static final int SLOT_HUB = 4;
    public static final int SLOT_SORT = 48;
    public static final int SLOT_CURRENCY = 50;
    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_NEXT_PAGE = 53;
    public static final int SLOT_CLAIM_ALL = 49;

    public enum Mode {
        BROWSE, CLAIM
    }

    public enum SortMode {
        DATE_POSTED, PRICE;

        SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum SortDirection {
        ASCENDING, DESCENDING;

        SortDirection flip() {
            return this == ASCENDING ? DESCENDING : ASCENDING;
        }
    }

    /** Which slice of listings a Browse view shows -- everything, just the viewer's own, or just their watchlist. */
    public enum ViewFilter {
        ALL, MINE, WATCHLIST
    }

    private AuctionMenu() {
    }

    public static void openBrowse(Player player, AuctionManager manager, Messages messages, int requestedPage) {
        open(player, manager, messages, requestedPage, ViewFilter.ALL, SortMode.DATE_POSTED, SortDirection.ASCENDING, null);
    }

    public static void openMyListings(Player player, AuctionManager manager, Messages messages, int requestedPage) {
        open(player, manager, messages, requestedPage, ViewFilter.MINE, SortMode.DATE_POSTED, SortDirection.ASCENDING, null);
    }

    public static void openWatchlist(Player player, AuctionManager manager, Messages messages, int requestedPage) {
        open(player, manager, messages, requestedPage, ViewFilter.WATCHLIST, SortMode.DATE_POSTED, SortDirection.ASCENDING, null);
    }

    private static void open(Player player, AuctionManager manager, Messages messages, int requestedPage,
            ViewFilter viewFilter, SortMode sortMode, SortDirection sortDirection, AuctionCurrency currencyFilter) {
        Holder holder = new Holder(Mode.BROWSE, 0, viewFilter, sortMode, sortDirection, currencyFilter);
        String titleKey = switch (viewFilter) {
            case ALL -> "auction.gui-title";
            case MINE -> "auction.gui-title-mine";
            case WATCHLIST -> "auction.gui-title-watchlist";
        };
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.get(player, titleKey));
        holder.inventory = inventory;
        holder.page = clampPage(player, manager, holder, requestedPage);
        renderBrowse(inventory, holder, player, manager, messages);
        player.openInventory(inventory);
    }

    /** Re-renders an already-open Browse view in place -- used for sort/filter/page changes and watch toggles. */
    static void refresh(Player player, AuctionManager manager, Messages messages, Holder holder, int requestedPage) {
        holder.page = clampPage(player, manager, holder, requestedPage);
        renderBrowse(holder.getInventory(), holder, player, manager, messages);
    }

    private static int clampPage(Player player, AuctionManager manager, Holder holder, int requestedPage) {
        int totalPages = Math.max(1, (int) Math.ceil(visibleTo(player, manager, holder).size() / (double) PAGE_SIZE));
        return Math.max(0, Math.min(requestedPage, totalPages - 1));
    }

    private static List<AuctionListing> visibleTo(Player player, AuctionManager manager, Holder holder) {
        List<AuctionListing> base = switch (holder.viewFilter) {
            case ALL -> manager.activeListings();
            case MINE -> manager.activeListings().stream().filter(l -> l.sellerUuid().equals(player.getUniqueId())).toList();
            case WATCHLIST -> manager.watchedListings(player.getUniqueId());
        };
        java.util.stream.Stream<AuctionListing> stream = base.stream();
        if (holder.currencyFilter != null) {
            stream = stream.filter(listing -> listing.currency() == holder.currencyFilter);
        }
        return stream.sorted(listingComparator(holder.sortMode, holder.sortDirection)).toList();
    }

    /**
     * A listing's id is the final stable tie-breaker so pages never shuffle
     * when two listings share a price and millisecond timestamp. Price ties
     * always favour the oldest listing, in either price direction.
     */
    static Comparator<AuctionListing> listingComparator(SortMode mode, SortDirection direction) {
        return switch (mode) {
            case DATE_POSTED -> (direction == SortDirection.ASCENDING
                    ? Comparator.comparingLong(AuctionListing::listedAtMillis)
                    : Comparator.comparingLong(AuctionListing::listedAtMillis).reversed())
                    .thenComparingInt(AuctionListing::id);
            case PRICE -> (direction == SortDirection.DESCENDING
                    ? Comparator.comparingDouble(AuctionListing::price).reversed()
                    : Comparator.comparingDouble(AuctionListing::price))
                    .thenComparingLong(AuctionListing::listedAtMillis)
                    .thenComparingInt(AuctionListing::id);
        };
    }

    private static void renderBrowse(Inventory inventory, Holder holder, Player player, AuctionManager manager, Messages messages) {
        List<AuctionListing> visible = visibleTo(player, manager, holder);
        int totalPages = Math.max(1, (int) Math.ceil(visible.size() / (double) PAGE_SIZE));
        int page = holder.page;

        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, border());
        }

        holder.slotListingIds.clear();
        int start = page * PAGE_SIZE;
        int end = Math.min(visible.size(), start + PAGE_SIZE);
        for (int index = 0; index < PAGE_SIZE; index++) {
            int slot = gridSlot(index);
            int listingIndex = start + index;
            if (listingIndex < end) {
                AuctionListing listing = visible.get(listingIndex);
                inventory.setItem(slot, listingIcon(player, manager, messages, listing));
                holder.slotListingIds.put(slot, listing.id());
            } else {
                inventory.setItem(slot, null);
            }
        }

        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, border());
        }
        inventory.setItem(SLOT_SORT, sortButton(player, messages, holder));
        inventory.setItem(SLOT_CURRENCY, currencyButton(player, messages, holder));
        inventory.setItem(SLOT_HUB, hubButton(player, messages));
        inventory.setItem(SLOT_PREV_PAGE, pageButton(messages, player, "auction.previous-page", Material.RED_DYE, page > 0));
        inventory.setItem(SLOT_NEXT_PAGE, pageButton(messages, player, "auction.next-page", Material.LIME_DYE, page < totalPages - 1));
    }

    public static void openClaim(Player player, AuctionManager manager, Messages messages) {
        manager.loadClaimItemsAsync(player.getUniqueId()).thenAccept(items -> Bukkit.getScheduler().runTask(
                org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(AuctionMenu.class), () -> {
                    if (player.isOnline()) {
                        openClaimLoaded(player, messages, items);
                    }
                }));
    }

    private static void openClaimLoaded(Player player, Messages messages, List<ItemStack> items) {
        Holder holder = new Holder(Mode.CLAIM, 0, ViewFilter.ALL, SortMode.DATE_POSTED, SortDirection.ASCENDING, null);
        holder.setClaimItems(items);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.get(player, "auction.claim-title"));
        holder.inventory = inventory;

        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, border());
        }
        for (int i = 0; i < items.size() && i < 45; i++) {
            inventory.setItem(i, items.get(i));
        }
        inventory.setItem(SLOT_CLAIM_ALL, claimAllButton(player, messages, !items.isEmpty()));

        player.openInventory(inventory);
    }

    private static int gridSlot(int index) {
        int row = index / GRID_COLUMNS;
        int column = index % GRID_COLUMNS;
        return (row + 1) * 9 + column;
    }

    private static ItemStack listingIcon(Player viewer, AuctionManager manager, Messages messages, AuctionListing listing) {
        ItemStack icon = listing.item().clone();
        ItemMeta meta = icon.getItemMeta();
        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.sellerUuid());
        String sellerName = seller.getName() == null ? "?" : seller.getName();

        List<Component> lore = new ArrayList<>();
        if (meta.hasLore()) {
            lore.addAll(meta.lore());
            lore.add(Component.empty());
        }
        lore.add(noItalic(messages.get(viewer, "auction.listing-seller", "player", sellerName)));
        String priceText = switch (listing.currency()) {
            case MONEY -> EconomyHook.format(listing.price());
            case EXP -> (int) Math.ceil(listing.price()) + " levels";
            case GC -> Numbers.formatFull((long) Math.ceil(listing.price())) + " GC";
        };
        lore.add(noItalic(messages.get(viewer, "auction.listing-price", "price", priceText)));
        lore.add(noItalic(messages.get(viewer, "auction.listing-expires", "time", relativeTime(listing.expiresAtMillis()))));
        boolean isSeller = listing.sellerUuid().equals(viewer.getUniqueId());
        if (!isSeller) {
            lore.add(noItalic(messages.get(viewer, "auction.listing-click-buy")));
        }
        if (isSeller || viewer.hasPermission("vertex.auction.remove")) {
            lore.add(noItalic(messages.get(viewer, "auction.listing-shift-click-cancel")));
        }
        boolean watching = manager.isWatching(viewer.getUniqueId(), listing.id());
        lore.add(noItalic(messages.get(viewer, watching ? "auction.listing-right-click-unwatch" : "auction.listing-right-click-watch")));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private static String relativeTime(long expiresAtMillis) {
        long remainingMillis = Math.max(0, expiresAtMillis - System.currentTimeMillis());
        long hours = TimeUnit.MILLISECONDS.toHours(remainingMillis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMillis) % 60;
        return hours + "h " + minutes + "m";
    }

    private static ItemStack sortButton(Player player, Messages messages, Holder holder) {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        String modeKey = switch (holder.sortMode) {
            case DATE_POSTED -> "auction.sort-date-posted";
            case PRICE -> "auction.sort-price";
        };
        String directionKey = holder.sortDirection == SortDirection.ASCENDING ? "auction.sort-ascending" : "auction.sort-descending";
        meta.displayName(noItalic(messages.get(player, "auction.sort-title",
                "mode", messages.getRaw(player, modeKey), "direction", messages.getRaw(player, directionKey))));
        meta.lore(List.of(
                option(player, messages, "auction.sort-date-posted", holder.sortMode == SortMode.DATE_POSTED),
                option(player, messages, "auction.sort-price", holder.sortMode == SortMode.PRICE),
                Component.empty(),
                noItalic(messages.get(player, "auction.sort-click-hint")),
                noItalic(messages.get(player, "auction.sort-shift-click-hint"))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack currencyButton(Player player, Messages messages, Holder holder) {
        ItemStack item = new ItemStack(Material.LECTERN);
        ItemMeta meta = item.getItemMeta();
        String currentKey = switch (holder.currencyFilter) {
            case null -> "auction.currency-all";
            case MONEY -> "auction.currency-money";
            case EXP -> "auction.currency-exp";
            case GC -> "auction.currency-gc-coming-soon";
        };
        meta.displayName(noItalic(messages.get(player, "auction.currency-title", "currency", messages.getRaw(player, currentKey))));
        meta.lore(List.of(
                option(player, messages, "auction.currency-all", holder.currencyFilter == null),
                option(player, messages, "auction.currency-money", holder.currencyFilter == AuctionCurrency.MONEY),
                option(player, messages, "auction.currency-exp", holder.currencyFilter == AuctionCurrency.EXP),
                option(player, messages, "auction.currency-gc-coming-soon", holder.currencyFilter == AuctionCurrency.GC),
                Component.empty(),
                noItalic(messages.get(player, "auction.currency-click-hint"))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack hubButton(Player player, Messages messages) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(player);
        meta.displayName(noItalic(messages.get(player, "auction.hub-title")));
        meta.lore(List.of(
                noItalic(messages.get(player, "auction.hub-lore-1")),
                noItalic(messages.get(player, "auction.hub-lore-2")),
                noItalic(messages.get(player, "auction.hub-lore-3")),
                noItalic(messages.get(player, "auction.hub-lore-4")),
                noItalic(messages.get(player, "auction.hub-lore-5")),
                Component.empty(),
                noItalic(messages.get(player, "auction.hub-click-hint"))));
        head.setItemMeta(meta);
        return head;
    }

    private static ItemStack claimAllButton(Player player, Messages messages, boolean hasItems) {
        ItemStack item = new ItemStack(hasItems ? Material.CHEST : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, "auction.claim-all-button")));
        meta.lore(List.of(noItalic(messages.get(player, hasItems ? "auction.claim-all-lore" : "auction.claim-all-empty-lore"))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack pageButton(Messages messages, Player player, String key, Material material, boolean enabled) {
        ItemStack item = new ItemStack(enabled ? material : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.get(player, key)));
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

    /** Option labels deliberately override locale colours: green means active; gray means inactive. */
    private static Component option(Player player, Messages messages, String key, boolean selected) {
        return Component.text(MessageFormatter.plain(messages.getRaw(player, key)),
                selected ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    public static final class Holder implements InventoryHolder {
        private final Mode mode;
        private int page;
        private ViewFilter viewFilter;
        private SortMode sortMode;
        private SortDirection sortDirection;
        private AuctionCurrency currencyFilter;
        private final Map<Integer, Integer> slotListingIds = new HashMap<>();
        private List<ItemStack> claimItems = List.of();
        private Inventory inventory;

        Holder(Mode mode, int page, ViewFilter viewFilter, SortMode sortMode, SortDirection sortDirection, AuctionCurrency currencyFilter) {
            this.mode = mode;
            this.page = page;
            this.viewFilter = viewFilter;
            this.sortMode = sortMode;
            this.sortDirection = sortDirection;
            this.currencyFilter = currencyFilter;
        }

        public Mode mode() {
            return mode;
        }

        public int page() {
            return page;
        }

        public ViewFilter viewFilter() {
            return viewFilter;
        }

        public SortMode sortMode() {
            return sortMode;
        }

        void setSortMode(SortMode sortMode) {
            this.sortMode = sortMode;
        }

        public SortDirection sortDirection() {
            return sortDirection;
        }

        void setSortDirection(SortDirection sortDirection) {
            this.sortDirection = sortDirection;
        }

        public AuctionCurrency currencyFilter() {
            return currencyFilter;
        }

        void cycleCurrencyFilter() {
            currencyFilter = switch (currencyFilter) {
                case null -> AuctionCurrency.MONEY;
                case MONEY -> AuctionCurrency.EXP;
                case EXP -> AuctionCurrency.GC;
                case GC -> null;
            };
        }

        public Integer listingIdAtSlot(int slot) {
            return slotListingIds.get(slot);
        }

        List<ItemStack> claimItems() {
            return claimItems;
        }

        void setClaimItems(List<ItemStack> items) {
            claimItems = List.copyOf(items);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
