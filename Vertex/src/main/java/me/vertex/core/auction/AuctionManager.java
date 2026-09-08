package me.vertex.core.auction;

import me.vertex.core.economy.EconomyHook;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Owns every Auction House rule and all of its state. Same shape as
 * {@code CoinflipManager}: in-memory maps are the moment-to-moment source
 * of truth, {@link AuctionStorage} is trailing, eventually-consistent
 * persistence behind them. A listed item leaves the seller's inventory
 * the instant it's listed and is never given to anyone until a real sale
 * (money withdrawn from the buyer first) or a refund (expiry/cancel) --
 * there is no window where it's merely "reserved" without actually having
 * moved.
 */
public final class AuctionManager {

    private final Plugin plugin;
    private final AuctionStorage storage;

    private volatile boolean enabled;
    private volatile double minPrice;
    private volatile double maxPrice;
    private volatile int maxActiveListingsPerPlayer;
    private volatile long listingDurationMillis;
    private volatile double listingFeePercent;
    private volatile double saleTaxPercent;
    private volatile long logRetentionDays;
    private volatile long sweepIntervalTicks;

    private final Map<Integer, AuctionListing> activeListings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingExp = new ConcurrentHashMap<>();
    /** owner uuid -> the listing ids they're watching. */
    private final Map<UUID, java.util.Set<Integer>> watchlists = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();

    public AuctionManager(Plugin plugin, AuctionStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "ah.yml");
    }

    private final File file;

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("ah.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        minPrice = Math.max(0, config.getDouble("min-price", 1.0));
        maxPrice = Math.max(minPrice, config.getDouble("max-price", 10_000_000.0));
        maxActiveListingsPerPlayer = Math.max(1, config.getInt("max-active-listings-per-player", 10));
        listingDurationMillis = Duration.ofHours(Math.max(1, config.getLong("listing-duration-hours", 48))).toMillis();
        listingFeePercent = Math.max(0, Math.min(100, config.getDouble("listing-fee-percent", 0.0)));
        saleTaxPercent = Math.max(0, Math.min(100, config.getDouble("sale-tax-percent", 0.0)));
        logRetentionDays = Math.max(0, config.getLong("log-retention-days", 0));
        sweepIntervalTicks = Math.max(20, config.getLong("sweep-interval-ticks", 1200));
    }

    public void loadState() {
        try {
            for (AuctionListing listing : storage.loadAllListings()) {
                activeListings.put(listing.id(), listing);
            }
            pendingExp.putAll(storage.loadPendingExp());
            for (Map.Entry<UUID, List<Integer>> entry : storage.loadAllWatches().entrySet()) {
                watchlists.put(entry.getKey(), ConcurrentHashMap.newKeySet());
                watchlists.get(entry.getKey()).addAll(entry.getValue());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Auction House listings from the database.", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double minPrice() {
        return minPrice;
    }

    public double maxPrice() {
        return maxPrice;
    }

    public long sweepIntervalTicks() {
        return sweepIntervalTicks;
    }

    public List<AuctionListing> activeListings() {
        return List.copyOf(activeListings.values());
    }

    public AuctionListing getListing(int id) {
        return activeListings.get(id);
    }

    private long activeListingCount(UUID sellerUuid) {
        long count = 0;
        for (AuctionListing listing : activeListings.values()) {
            if (listing.sellerUuid().equals(sellerUuid)) {
                count++;
            }
        }
        return count;
    }

    // ---- Listing ----

    public enum ListResult {
        OK, DISABLED, OUT_OF_RANGE, TOO_MANY_LISTINGS, NO_ECONOMY, CANNOT_AFFORD_FEE
    }

    public record ListOutcome(ListResult result, AuctionListing listing) {
        static ListOutcome failure(ListResult result) {
            return new ListOutcome(result, null);
        }
    }

    /** {@code item} must already be removed from the seller's real inventory. */
    public ListOutcome list(Player seller, ItemStack item, double price, AuctionCurrency currency) {
        if (!enabled) {
            return ListOutcome.failure(ListResult.DISABLED);
        }
        if (price < minPrice || price > maxPrice) {
            return ListOutcome.failure(ListResult.OUT_OF_RANGE);
        }
        if (activeListingCount(seller.getUniqueId()) >= maxActiveListingsPerPlayer) {
            return ListOutcome.failure(ListResult.TOO_MANY_LISTINGS);
        }
        double fee = price * (listingFeePercent / 100.0);
        Runnable refundFee;
        if (fee > 0) {
            if (currency == AuctionCurrency.MONEY) {
                if (!EconomyHook.isAvailable()) {
                    return ListOutcome.failure(ListResult.NO_ECONOMY);
                }
                EconomyResponse response = EconomyHook.getEconomy().withdrawPlayer(seller, fee);
                if (!response.transactionSuccess()) {
                    return ListOutcome.failure(ListResult.CANNOT_AFFORD_FEE);
                }
                refundFee = () -> EconomyHook.getEconomy().depositPlayer(seller, fee);
            } else {
                int feeLevels = (int) Math.ceil(fee);
                if (seller.getLevel() < feeLevels) {
                    return ListOutcome.failure(ListResult.CANNOT_AFFORD_FEE);
                }
                seller.setLevel(seller.getLevel() - feeLevels);
                refundFee = () -> seller.setLevel(seller.getLevel() + feeLevels);
            }
        } else {
            refundFee = () -> { };
        }

        long listedAt = System.currentTimeMillis();
        long expiresAt = listedAt + listingDurationMillis;
        ItemStack itemCopy = item.clone();
        // Negative placeholder id so the listing renders immediately, same
        // pattern as CoinflipManager -- swapped for the real id once the
        // insert completes.
        int placeholderId = -(int) (listedAt % 1_000_000) - 1;
        while (activeListings.containsKey(placeholderId)) {
            placeholderId--;
        }
        AuctionListing placeholder = new AuctionListing(placeholderId, seller.getUniqueId(), itemCopy, price, currency, listedAt, expiresAt);
        activeListings.put(placeholderId, placeholder);

        CompletableFuture<Integer> insert = CompletableFuture.supplyAsync(() -> {
            try {
                return storage.insertListing(seller.getUniqueId(), itemCopy, price, currency, listedAt, expiresAt);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        track(insert);
        int finalPlaceholderId = placeholderId;
        insert.whenComplete((id, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            activeListings.remove(finalPlaceholderId);
            if (error != null || id == null || id < 0) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist a new Auction House listing -- returning the item.", error);
                if (seller.isOnline()) {
                    giveOrClaim(seller.getUniqueId(), itemCopy);
                    refundFee.run();
                }
                return;
            }
            activeListings.put(id, new AuctionListing(id, seller.getUniqueId(), itemCopy, price, currency, listedAt, expiresAt));
        }));

        return new ListOutcome(ListResult.OK, placeholder);
    }

    // ---- Buying ----

    public enum BuyResult {
        OK, GONE, IS_SELLER, NO_ECONOMY, CANNOT_AFFORD
    }

    public BuyResult buy(int listingId, Player buyer) {
        AuctionListing listing = activeListings.get(listingId);
        // A pending listing is not durable yet: buying it would let the
        // still-running insert re-add the same item for sale afterwards.
        if (listing == null || listing.isPending()) {
            return BuyResult.GONE;
        }
        if (listing.sellerUuid().equals(buyer.getUniqueId())) {
            return BuyResult.IS_SELLER;
        }
        int priceLevels = (int) Math.ceil(listing.price());
        if (listing.currency() == AuctionCurrency.MONEY) {
            if (!EconomyHook.isAvailable()) {
                return BuyResult.NO_ECONOMY;
            }
            if (!EconomyHook.getEconomy().has(buyer, listing.price())) {
                return BuyResult.CANNOT_AFFORD;
            }
        } else if (buyer.getLevel() < priceLevels) {
            return BuyResult.CANNOT_AFFORD;
        }
        if (!activeListings.remove(listingId, listing)) {
            return BuyResult.GONE;
        }

        if (listing.currency() == AuctionCurrency.MONEY) {
            Economy economy = EconomyHook.getEconomy();
            EconomyResponse response = economy.withdrawPlayer(buyer, listing.price());
            if (!response.transactionSuccess()) {
                activeListings.put(listingId, listing);
                return BuyResult.CANNOT_AFFORD;
            }
        } else {
            buyer.setLevel(buyer.getLevel() - priceLevels);
        }

        double tax = listing.price() * (saleTaxPercent / 100.0);
        double proceeds = listing.price() - tax;
        if (listing.currency() == AuctionCurrency.MONEY) {
            EconomyHook.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(listing.sellerUuid()), proceeds);
        } else {
            creditExp(listing.sellerUuid(), (int) Math.round(proceeds));
        }
        for (ItemStack leftover : buyer.getInventory().addItem(listing.item().clone()).values()) {
            buyer.getWorld().dropItemNaturally(buyer.getLocation(), leftover);
        }

        logAndRemove(listing, buyer.getUniqueId(), AuctionLogEntry.Status.SOLD, null);
        return BuyResult.OK;
    }

    /** Experience proceeds credit a live player directly, or wait for their next join if they're offline. */
    private void creditExp(UUID uuid, int levels) {
        if (levels <= 0) {
            return;
        }
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            online.setLevel(online.getLevel() + levels);
            return;
        }
        int total = pendingExp.merge(uuid, levels, Integer::sum);
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.savePendingExp(uuid, total);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist pending Auction House experience for " + uuid, e);
            }
        }));
    }

    /** Applies any experience owed from while this player was offline. Call this on join. */
    public void applyPendingExp(Player player) {
        Integer levels = pendingExp.remove(player.getUniqueId());
        if (levels == null || levels <= 0) {
            return;
        }
        player.setLevel(player.getLevel() + levels);
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deletePendingExp(player.getUniqueId());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clear pending Auction House experience for "
                        + player.getUniqueId(), e);
            }
        }));
    }

    // ---- Cancellation (self or admin) ----

    public boolean canCancel(Player actor, AuctionListing listing) {
        return listing.sellerUuid().equals(actor.getUniqueId()) || actor.hasPermission("vertex.auction.remove");
    }

    /** @return false if the listing was already sold/gone by the time this ran. */
    public boolean cancel(AuctionListing listing, Player actor) {
        if (listing.isPending() || !activeListings.remove(listing.id(), listing)) {
            return false;
        }
        giveOrClaim(listing.sellerUuid(), listing.item());
        boolean adminCancel = !listing.sellerUuid().equals(actor.getUniqueId());
        logAndRemove(listing, null, AuctionLogEntry.Status.CANCELLED, adminCancel ? actor.getUniqueId() : null);
        return true;
    }

    // ---- Expiry sweep ----

    /** Called on a repeating task; returns every past-due listing to its seller's claim stash and logs it. */
    public void sweepExpired() {
        long now = System.currentTimeMillis();
        for (AuctionListing listing : List.copyOf(activeListings.values())) {
            if (listing.isPending() || !listing.isExpired(now)) {
                continue;
            }
            if (!activeListings.remove(listing.id(), listing)) {
                continue;
            }
            queueClaim(listing.sellerUuid(), listing.item());
            logAndRemove(listing, null, AuctionLogEntry.Status.EXPIRED, null);
        }
    }

    private void logAndRemove(AuctionListing listing, UUID buyerUuid, AuctionLogEntry.Status status, UUID cancelledBy) {
        clearWatchesForListing(listing.id());
        String summary = listing.item().getAmount() + "x " + listing.item().getType();
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deleteListing(listing.id());
                storage.insertLogEntry(listing.sellerUuid(), buyerUuid, summary, listing.price(),
                        listing.listedAtMillis(), System.currentTimeMillis(), status, cancelledBy);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist resolved Auction House listing " + listing.id(), e);
            }
        }));
    }

    // ---- Claims (returned items) ----

    public boolean hasClaims(UUID uuid) {
        try {
            return storage.hasClaims(uuid);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check Auction House claims for " + uuid, e);
            return false;
        }
    }

    public List<ItemStack> loadClaimItems(UUID uuid) {
        List<ItemStack> items = new ArrayList<>();
        try {
            for (AuctionClaim claim : storage.loadClaims(uuid)) {
                items.add(claim.item());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load Auction House claims for " + uuid, e);
        }
        return items;
    }

    /** Loads SQL-backed collection-box contents without blocking the server thread. */
    public CompletableFuture<List<ItemStack>> loadClaimItemsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> loadClaimItems(uuid));
    }

    /**
     * Takes everything in the collection box, deleting the rows first and
     * returning only what was actually removed.
     *
     * <p>The delete is what authorises the payout, so a repeated click cannot
     * grant the same items twice and a claim queued while this runs is never
     * silently destroyed. An empty list means someone else already took them.
     */
    public CompletableFuture<List<ItemStack>> takeClaims(UUID uuid) {
        CompletableFuture<List<ItemStack>> taken = CompletableFuture.supplyAsync(() -> {
            try {
                return storage.takeClaims(uuid).stream().map(AuctionClaim::item).toList();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to take Auction House claims for " + uuid, e);
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        track(taken);
        return taken;
    }

    private void queueClaim(UUID ownerUuid, ItemStack item) {
        long createdAt = System.currentTimeMillis();
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.insertClaim(ownerUuid, item, createdAt);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to persist an Auction House item return for " + ownerUuid + " -- item: " + item, e);
            }
        }));
    }

    /** Gives the item directly if the owner is online, otherwise queues it as a claim. */
    private void giveOrClaim(UUID ownerUuid, ItemStack item) {
        Player online = Bukkit.getPlayer(ownerUuid);
        if (online != null) {
            for (ItemStack leftover : online.getInventory().addItem(item.clone()).values()) {
                online.getWorld().dropItemNaturally(online.getLocation(), leftover);
            }
        } else {
            queueClaim(ownerUuid, item);
        }
    }

    // ---- Watchlist ----

    public boolean isWatching(UUID ownerUuid, int listingId) {
        java.util.Set<Integer> watched = watchlists.get(ownerUuid);
        return watched != null && watched.contains(listingId);
    }

    /** @return true if now watching, false if the watch was just removed. */
    public boolean toggleWatch(UUID ownerUuid, int listingId) {
        java.util.Set<Integer> watched = watchlists.computeIfAbsent(ownerUuid, ignored -> ConcurrentHashMap.newKeySet());
        boolean nowWatching = watched.add(listingId);
        if (!nowWatching) {
            watched.remove(listingId);
        }
        track(CompletableFuture.runAsync(() -> {
            try {
                if (nowWatching) {
                    storage.insertWatch(ownerUuid, listingId);
                } else {
                    storage.deleteWatch(ownerUuid, listingId);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist an Auction House watch for " + ownerUuid, e);
            }
        }));
        return nowWatching;
    }

    /** Every listing (still active) a player is watching, newest first. */
    public List<AuctionListing> watchedListings(UUID ownerUuid) {
        java.util.Set<Integer> watched = watchlists.get(ownerUuid);
        if (watched == null || watched.isEmpty()) {
            return List.of();
        }
        return activeListings.values().stream()
                .filter(listing -> watched.contains(listing.id()))
                .sorted((a, b) -> Long.compare(b.listedAtMillis(), a.listedAtMillis()))
                .toList();
    }

    private void clearWatchesForListing(int listingId) {
        for (java.util.Set<Integer> watched : watchlists.values()) {
            watched.remove(listingId);
        }
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deleteWatchesForListing(listingId);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to clear watches for resolved Auction House listing " + listingId, e);
            }
        }));
    }

    // ---- Audit log ----

    public List<AuctionLogEntry> loadLog(UUID playerFilter, int limit, int offset) {
        try {
            return storage.loadLog(playerFilter, limit, offset);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load the Auction House audit log.", e);
            return List.of();
        }
    }

    /** Loads audit rows without blocking a command or inventory click. */
    public CompletableFuture<List<AuctionLogEntry>> loadLogAsync(UUID playerFilter, int limit, int offset) {
        return CompletableFuture.supplyAsync(() -> loadLog(playerFilter, limit, offset));
    }

    public void pruneLog() {
        if (logRetentionDays <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Duration.ofDays(logRetentionDays).toMillis();
        track(CompletableFuture.runAsync(() -> {
            try {
                storage.deleteLogEntriesOlderThan(cutoff);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to prune the Auction House audit log.", e);
            }
        }));
    }

    // ---- Shutdown / migration draining ----

    private void track(CompletableFuture<?> write) {
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending Auction House writes.", e);
        }
    }
}
