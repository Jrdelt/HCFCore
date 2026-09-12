package me.vertex.core.coinflip;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.util.Numbers;
import net.kyori.adventure.text.Component;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The "Active Coinflips" browser: row 0 is controls (claim stash, self-ban,
 * help), rows 1-4 are the paginated coinflip
 * listing, row 5 is pagination. A separate {@link Holder} identifies each
 * of this menu's three modes ({@code BROWSE}/{@code CLAIM}/{@code VIEW_ITEMS})
 * so {@link CoinflipMenuListener} can route clicks correctly.
 */
public final class CoinflipMenu {

    public static final int GRID_ROWS = 4;
    public static final int GRID_COLUMNS = 9;
    public static final int PAGE_SIZE = GRID_ROWS * GRID_COLUMNS;

    public static final int SLOT_CLAIM = 49;
    public static final int SLOT_SELF_BAN = 8;
    public static final int SLOT_HELP = 4;
    public static final int SLOT_PREV_PAGE = 45;
    public static final int SLOT_NEXT_PAGE = 53;
    public static final int SLOT_CLAIM_ALL = 49;

    public enum Mode {
        BROWSE, CLAIM, VIEW_ITEMS
    }

    private CoinflipMenu() {
    }

    public static void openBrowse(Player player, CoinflipManager manager, Messages messages, int requestedPage) {
        manager.hasClaimsAsync(player.getUniqueId()).thenAccept(hasClaims -> Bukkit.getScheduler().runTask(
                org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(CoinflipMenu.class), () -> {
                    if (player.isOnline()) {
                        openBrowseLoaded(player, manager, messages, requestedPage, hasClaims);
                    }
                }));
    }

    private static void openBrowseLoaded(Player player, CoinflipManager manager, Messages messages, int requestedPage,
            boolean hasClaims) {
        Holder holder = new Holder(Mode.BROWSE, clampPage(player, manager, requestedPage), null);
        holder.hasClaims = hasClaims;
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "coinflip.gui-title"));
        holder.inventory = inventory;
        for (int slot = 0; slot < 9; slot++) {
            inventory.setItem(slot, border());
        }
        renderBrowse(inventory, holder, player, manager, messages);
        player.openInventory(inventory);
    }

    /**
     * Re-renders only when a browser-visible state changed, or when a
     * multi-item wager reaches its next icon-cycle boundary.
     */
    public static void refreshOpenBrowse(Player player, CoinflipManager manager, Messages messages) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder) || holder.mode() != Mode.BROWSE) {
            return;
        }
        long iconBucket = System.currentTimeMillis() / Math.max(50L, manager.itemIconCycleTicks() * 50L);
        if (holder.renderedVersion == manager.browserVersion() && holder.renderedIconBucket == iconBucket) {
            return;
        }
        renderBrowse(holder.getInventory(), holder, player, manager, messages);
    }

    private static int clampPage(Player player, CoinflipManager manager, int requestedPage) {
        int totalPages = Math.max(1, (int) Math.ceil(visibleTo(player, manager).size() / (double) PAGE_SIZE));
        return Math.max(0, Math.min(requestedPage, totalPages - 1));
    }

    private static List<Coinflip> visibleTo(Player player, CoinflipManager manager) {
        return manager.activeCoinflips().stream()
                .filter(coinflip -> coinflip.isOpenTo(player.getUniqueId()) || coinflip.hostUuid().equals(player.getUniqueId()))
                .sorted((a, b) -> Long.compare(b.createdAtMillis(), a.createdAtMillis()))
                .toList();
    }

    private static void renderBrowse(Inventory inventory, Holder holder, Player player, CoinflipManager manager, Messages messages) {
        List<Coinflip> visible = visibleTo(player, manager);
        int totalPages = Math.max(1, (int) Math.ceil(visible.size() / (double) PAGE_SIZE));
        int page = holder.page();

        holder.slotCoinflipIds.clear();
        int start = page * PAGE_SIZE;
        int end = Math.min(visible.size(), start + PAGE_SIZE);
        for (int index = 0; index < PAGE_SIZE; index++) {
            int slot = gridSlot(index);
            int listingIndex = start + index;
            if (listingIndex < end) {
                Coinflip coinflip = visible.get(listingIndex);
                inventory.setItem(slot, listingIcon(player, manager, messages, coinflip));
                holder.slotCoinflipIds.put(slot, coinflip.id());
            } else {
                // A listing that used to be here (played/cancelled/expired)
                // must not leave a stale icon behind on the next refresh.
                inventory.setItem(slot, null);
            }
        }

        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, border());
        }
        inventory.setItem(SLOT_CLAIM, claimButton(player, messages, holder.hasClaims));
        inventory.setItem(SLOT_SELF_BAN, selfBanButton(player, manager, messages));
        inventory.setItem(SLOT_HELP, helpButton(player, messages));
        inventory.setItem(SLOT_PREV_PAGE, pageButton(messages, player, "coinflip.previous-page", Material.RED_DYE, page > 0));
        inventory.setItem(SLOT_NEXT_PAGE, pageButton(messages, player, "coinflip.next-page", Material.LIME_DYE, page < totalPages - 1));
        holder.renderedVersion = manager.browserVersion();
        holder.renderedIconBucket = System.currentTimeMillis() / Math.max(50L, manager.itemIconCycleTicks() * 50L);
    }

    public static void openClaim(Player player, CoinflipManager manager, Messages messages) {
        manager.loadClaimItemsAsync(player.getUniqueId()).thenAccept(items -> Bukkit.getScheduler().runTask(
                org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(CoinflipMenu.class), () -> {
                    if (player.isOnline()) {
                        openClaimLoaded(player, messages, items);
                    }
                }));
    }

    private static void openClaimLoaded(Player player, Messages messages, List<ItemStack> items) {
        Holder holder = new Holder(Mode.CLAIM, 0, null);
        holder.claimItems = List.copyOf(items);
        Inventory inventory = Bukkit.createInventory(holder, 54, messages.getGui(player, "coinflip.claim-title"));
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

    public static void openViewItems(Player player, Messages messages, Coinflip coinflip) {
        Holder holder = new Holder(Mode.VIEW_ITEMS, 0, coinflip.id());
        Inventory inventory = Bukkit.createInventory(holder, 27, messages.getGui(player, "coinflip.view-items-title"));
        holder.inventory = inventory;
        ItemStack[] items = coinflip.items();
        for (int i = 0; i < items.length && i < 27; i++) {
            inventory.setItem(i, items[i]);
        }
        player.openInventory(inventory);
    }

    private static int gridSlot(int index) {
        int row = index / GRID_COLUMNS;
        int column = index % GRID_COLUMNS;
        return (row + 1) * 9 + column;
    }

    private static ItemStack listingIcon(Player viewer, CoinflipManager manager, Messages messages, Coinflip coinflip) {
        OfflinePlayer host = Bukkit.getOfflinePlayer(coinflip.hostUuid());
        ItemStack icon = switch (coinflip.type()) {
            case MONEY, EXP, GC -> headOf(host);
            case ITEMS -> currentCycledItem(coinflip, manager).clone();
        };
        ItemMeta meta = icon.getItemMeta();
        String hostName = host.getName() == null ? "?" : host.getName();
        meta.displayName(noItalic(messages.getGui(viewer, "coinflip.listing-title", "player", hostName)));

        List<Component> lore = new ArrayList<>();
        lore.add(noItalic(messages.getGui(viewer, "coinflip.listing-wager", "wager", wagerText(coinflip))));
        if (coinflip.type() == CoinflipType.MONEY) {
            lore.add(noItalic(messages.getGui(viewer, "coinflip.listing-your-balance",
                    "balance", EconomyHook.getBalance(viewer))));
        } else if (coinflip.type() == CoinflipType.EXP) {
            lore.add(noItalic(messages.getGui(viewer, "coinflip.listing-your-levels", "levels", String.valueOf(viewer.getLevel()))));
        }
        lore.add(noItalic(messages.getGui(viewer, "coinflip.listing-created", "time", relativeTime(coinflip.createdAtMillis()))));
        boolean isHost = coinflip.hostUuid().equals(viewer.getUniqueId());
        boolean pendingMatch = coinflip.type() == CoinflipType.ITEMS && manager.hasPendingItemMatch(coinflip.id());
        if (isHost && pendingMatch) {
            lore.add(noItalic(messages.getGui(viewer, "coinflip.listing-pending-match-host", "id", String.valueOf(coinflip.id()))));
        } else if (!isHost && pendingMatch) {
            lore.add(noItalic(messages.getGui(viewer, "coinflip.listing-pending-match-other")));
        } else if (!isHost) {
            lore.add(noItalic(messages.getGui(viewer, "coinflip.listing-click-play")));
        }
        if (coinflip.type() == CoinflipType.ITEMS && coinflip.items().length > 1) {
            lore.add(noItalic(messages.getGui(viewer, "coinflip.listing-right-click-view")));
        }
        if (isHost || viewer.hasPermission("vertex.coinflip.remove")) {
            lore.add(noItalic(messages.getGui(viewer, "coinflip.listing-shift-click-cancel")));
        }
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    /** Cycles a multi-item wager's displayed icon over time, per {@code item-icon-cycle-ticks}. */
    private static ItemStack currentCycledItem(Coinflip coinflip, CoinflipManager manager) {
        ItemStack[] items = coinflip.items();
        if (items.length == 0) {
            return new ItemStack(Material.BARRIER);
        }
        long cycleTicks = Math.max(1, manager.itemIconCycleTicks());
        long elapsedTicks = (System.currentTimeMillis() - coinflip.createdAtMillis()) / 50L;
        int index = (int) ((elapsedTicks / cycleTicks) % items.length);
        return items[index];
    }

    private static String wagerText(Coinflip coinflip) {
        return switch (coinflip.type()) {
            case MONEY -> EconomyHook.format(coinflip.amount());
            case EXP -> (int) coinflip.amount() + " levels";
            case ITEMS -> coinflip.items().length + (coinflip.items().length == 1 ? " item" : " items");
            case GC -> Numbers.formatFull((long) coinflip.amount()) + " GC";
        };
    }

    private static String relativeTime(long epochMillis) {
        long elapsedMillis = Math.max(0, System.currentTimeMillis() - epochMillis);
        long days = TimeUnit.MILLISECONDS.toDays(elapsedMillis);
        if (days > 0) {
            return days + "d " + (TimeUnit.MILLISECONDS.toHours(elapsedMillis) % 24) + "h ago";
        }
        long hours = TimeUnit.MILLISECONDS.toHours(elapsedMillis);
        if (hours > 0) {
            return hours + " hours " + (TimeUnit.MILLISECONDS.toMinutes(elapsedMillis) % 60) + " minutes ago";
        }
        long minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMillis);
        if (minutes > 0) {
            return minutes + " minutes " + (TimeUnit.MILLISECONDS.toSeconds(elapsedMillis) % 60) + " seconds ago";
        }
        return TimeUnit.MILLISECONDS.toSeconds(elapsedMillis) + " seconds ago";
    }

    private static ItemStack claimButton(Player player, Messages messages, boolean hasClaims) {
        ItemStack item = new ItemStack(hasClaims ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, hasClaims ? "coinflip.claim-button-has-items" : "coinflip.claim-button-empty")));
        meta.lore(List.of(noItalic(messages.getGui(player, hasClaims ? "coinflip.claim-button-has-items-lore" : "coinflip.claim-button-empty-lore"))));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack selfBanButton(Player player, CoinflipManager manager, Messages messages) {
        boolean banned = manager.isBanned(player.getUniqueId());
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, banned ? "coinflip.self-ban-title-active" : "coinflip.self-ban-title")));
        List<Component> lore = new ArrayList<>();
        if (banned) {
            lore.add(noItalic(messages.getGui(player, "coinflip.self-ban-active")));
            lore.add(noItalic(messages.getGui(player, "coinflip.self-ban-remaining", "time", formatDuration(manager.banRemainingMillis(player.getUniqueId())))));
        } else {
            lore.add(noItalic(messages.getGui(player, "coinflip.self-ban-lore")));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String formatDuration(long millis) {
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        return days + "d " + hours + "h";
    }

    private static ItemStack helpButton(Player player, Messages messages) {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, "coinflip.help-title")));
        meta.lore(messages.getGuiList(player, "coinflip.help-lore").stream().map(CoinflipMenu::noItalic).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack claimAllButton(Player player, Messages messages, boolean hasItems) {
        ItemStack item = new ItemStack(hasItems ? Material.CHEST : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(messages.getGui(player, "coinflip.claim-all-button")));
        meta.lore(List.of(noItalic(messages.getGui(player, hasItems ? "coinflip.claim-all-lore" : "coinflip.claim-all-empty-lore"))));
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

    private static ItemStack headOf(OfflinePlayer player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(player);
        head.setItemMeta(meta);
        return head;
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
        private final Mode mode;
        private final int page;
        private final Integer coinflipId;
        /** Grid slot -> the coinflip id rendered there, as of when this page was built. */
        private final java.util.Map<Integer, Integer> slotCoinflipIds = new java.util.HashMap<>();
        private Inventory inventory;
        private List<ItemStack> claimItems = List.of();
        private boolean hasClaims;
        private long renderedVersion = Long.MIN_VALUE;
        private long renderedIconBucket = Long.MIN_VALUE;

        Holder(Mode mode, int page, Integer coinflipId) {
            this.mode = mode;
            this.page = page;
            this.coinflipId = coinflipId;
        }

        public Mode mode() {
            return mode;
        }

        public int page() {
            return page;
        }

        public Integer coinflipId() {
            return coinflipId;
        }

        /** Null if that slot wasn't rendering a coinflip listing. */
        public Integer coinflipIdAtSlot(int slot) {
            return slotCoinflipIds.get(slot);
        }

        List<ItemStack> claimItems() {
            return claimItems;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
