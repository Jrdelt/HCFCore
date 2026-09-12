package me.vertex.core.auction;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.gc.GcAction;
import me.vertex.core.gc.GcManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.storage.ClaimDelivery;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final Messages messages;
    /** Set post-construction, mirroring {@code captureEventManager.setPvpTopManager}; null means GC listings refuse cleanly. */
    private volatile GcManager gcManager;

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
    private final java.util.Set<String> pendingPayoutsInProgress = ConcurrentHashMap.newKeySet();
    /** Manager-level guard as callers other than the GUI may also request collection. */
    private final java.util.Set<UUID> claimsInProgress = ConcurrentHashMap.newKeySet();
    /** owner uuid -> the listing ids they're watching. */
    private final Map<UUID, java.util.Set<Integer>> watchlists = new ConcurrentHashMap<>();
    private final ExecutorService payoutExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "vertex-auction-payout");
        thread.setDaemon(true);
        return thread;
    });
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public AuctionManager(Plugin plugin, AuctionStorage storage) {
        this(plugin,storage,null);
    }

    public AuctionManager(Plugin plugin,AuctionStorage storage,Messages messages){
        this.plugin = plugin;
        this.storage = storage;
        this.messages=messages;
        this.file = new File(plugin.getDataFolder(), "ah.yml");
    }

    private final File file;

    /** Wired in after construction, once {@code GcManager} exists -- same pattern as {@code setPvpTopManager}. */
    public void setGcManager(GcManager gcManager) {
        this.gcManager = gcManager;
        processPendingPayouts(null);
    }

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
            for (AuctionStorage.CreationIntent intent : storage.loadCreationIntents()) {
                if ("PREPARED".equals(intent.state())) {
                    storage.deleteCreationIntent(intent.key());
                    continue;
                }
                if ("DEBITING".equals(intent.state())) {
                    plugin.getLogger().severe("Auction creation intent " + intent.key()
                            + " is awaiting staff reconciliation and was not replayed.");
                    continue;
                }
                int id = storage.activateCreationIntent(intent);
                if (id >= 0) activeListings.put(id, listingFrom(intent, id));
            }
            for (AuctionListing listing : storage.loadAllListings()) {
                activeListings.put(listing.id(), listing);
            }
            for (Map.Entry<UUID, List<Integer>> entry : storage.loadAllWatches().entrySet()) {
                watchlists.put(entry.getKey(), ConcurrentHashMap.newKeySet());
                watchlists.get(entry.getKey()).addAll(entry.getValue());
            }
            reportUncertainPayouts();
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
        OK, DISABLED, OUT_OF_RANGE, TOO_MANY_LISTINGS, NO_ECONOMY, CANNOT_AFFORD_FEE, NO_GC, PERSIST_FAILED,
        RECOVERY_REQUIRED
    }

    public record ListOutcome(ListResult result, AuctionListing listing) {
        static ListOutcome failure(ListResult result) {
            return new ListOutcome(result, null);
        }
    }

    /** {@code item} must already be removed from the seller's real inventory. */
    public ListOutcome list(Player seller, ItemStack item, double price, AuctionCurrency currency) {
        return list(seller, item, price, currency, false);
    }

    /** Production command path: durable intent is written before the held item leaves the inventory. */
    public ListOutcome listHeld(Player seller, double price, AuctionCurrency currency) {
        ItemStack held = seller.getInventory().getItemInMainHand();
        if (held == null || held.isEmpty()) return ListOutcome.failure(ListResult.PERSIST_FAILED);
        return list(seller, held.clone(), price, currency, true);
    }

    private ListOutcome list(Player seller, ItemStack item, double price, AuctionCurrency currency,
            boolean removeHeldItem) {
        if (!enabled) {
            return ListOutcome.failure(ListResult.DISABLED);
        }
        if (price < minPrice || price > maxPrice) {
            return ListOutcome.failure(ListResult.OUT_OF_RANGE);
        }
        if (activeListingCount(seller.getUniqueId()) >= maxActiveListingsPerPlayer) {
            return ListOutcome.failure(ListResult.TOO_MANY_LISTINGS);
        }
        long listedAt = System.currentTimeMillis();
        long expiresAt = listedAt + listingDurationMillis;
        ItemStack itemCopy = item.clone();
        AuctionStorage.CreationIntent intent;
        try {
            intent = storage.insertCreationIntent(seller.getUniqueId(), itemCopy, price, currency, listedAt,
                    expiresAt, removeHeldItem ? "PREPARED" : "DEBITING");
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not persist Auction listing intent; inventory was untouched.",
                    error);
            return ListOutcome.failure(ListResult.PERSIST_FAILED);
        }
        if (removeHeldItem) {
            try {
                if (!storage.setCreationIntentState(intent.key(), "PREPARED", "DEBITING")) {
                    deleteUnusedIntent(intent);
                    return ListOutcome.failure(ListResult.PERSIST_FAILED);
                }
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not reserve Auction listing intent " + intent.key() + "; inventory was untouched.",
                        error);
                deleteUnusedIntent(intent);
                return ListOutcome.failure(ListResult.PERSIST_FAILED);
            }
            ItemStack current = seller.getInventory().getItemInMainHand();
            if (current == null || current.isEmpty() || !current.isSimilar(itemCopy)
                    || current.getAmount() != itemCopy.getAmount()) {
                deleteUnusedIntent(intent);
                return ListOutcome.failure(ListResult.PERSIST_FAILED);
            }
            seller.getInventory().setItemInMainHand(null);
        }
        double fee = price * (listingFeePercent / 100.0);
        CompletableFuture<Boolean> debitCommitted=CompletableFuture.completedFuture(true);
        if (fee > 0) {
            if (currency == AuctionCurrency.MONEY) {
                if (!EconomyHook.isAvailable()) {
                    if (!abortCreation(intent, seller)) return recoveryIntent(intent);
                    return ListOutcome.failure(ListResult.NO_ECONOMY);
                }
                EconomyResponse response = EconomyHook.getEconomy().withdrawPlayer(seller, fee);
                if (!response.transactionSuccess()) {
                    if (!abortCreation(intent, seller)) return recoveryIntent(intent);
                    return ListOutcome.failure(ListResult.CANNOT_AFFORD_FEE);
                }
            } else if (currency == AuctionCurrency.GC) {
                GcManager gc = gcManager;
                if (gc == null) {
                    if (!abortCreation(intent, seller)) return recoveryIntent(intent);
                    return ListOutcome.failure(ListResult.NO_GC);
                }
                long feeGc = (long) Math.ceil(fee);
                UUID sellerUuid = seller.getUniqueId();
                GcManager.DurableDebit debit=gc.tryDebitDurably(sellerUuid,sellerUuid,GcAction.AUCTION_FEE,
                        feeGc,intent.key(),"auction-create:"+intent.key());
                if (!debit.accepted()) {
                    if (!abortCreation(intent, seller)) return recoveryIntent(intent);
                    return ListOutcome.failure(ListResult.CANNOT_AFFORD_FEE);
                }
                debitCommitted=debit.persisted();
            } else {
                int feeLevels = (int) Math.ceil(fee);
                if (seller.getLevel() < feeLevels) {
                    if (!abortCreation(intent, seller)) return recoveryIntent(intent);
                    return ListOutcome.failure(ListResult.CANNOT_AFFORD_FEE);
                }
                seller.setLevel(seller.getLevel() - feeLevels);
            }
        }
        return activateAfterDebit(seller,intent,itemCopy,price,currency,listedAt,expiresAt,debitCommitted);
    }

    private ListOutcome activateAfterDebit(Player seller,AuctionStorage.CreationIntent intent,ItemStack itemCopy,
            double price,AuctionCurrency currency,long listedAt,long expiresAt,
            CompletableFuture<Boolean> debitCommitted){
        // Negative placeholder id so the listing renders immediately, same
        // pattern as CoinflipManager -- swapped for the real id once the
        // insert completes.
        int placeholderId = -(int) (listedAt % 1_000_000) - 1;
        while (activeListings.containsKey(placeholderId)) {
            placeholderId--;
        }
        AuctionListing placeholder = new AuctionListing(placeholderId, seller.getUniqueId(), itemCopy, price, currency, listedAt, expiresAt);
        activeListings.put(placeholderId, placeholder);

        CompletableFuture<Activation> insert = debitCommitted.thenApplyAsync(committed -> {
            if(!committed)return new Activation(-1,true);
            try {
                if(!storage.setCreationIntentState(intent.key(),"DEBITING","ESCROWED"))return new Activation(-1,true);
                return new Activation(storage.activateCreationIntent(intent),false);
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
        track(insert);
        int finalPlaceholderId = placeholderId;
        insert.whenComplete((activation, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            activeListings.remove(finalPlaceholderId);
            if(activation!=null&&activation.recoveryRequired()){
                plugin.getLogger().severe("Auction creation intent "+intent.key()+" requires staff reconciliation.");
                if(messages!=null&&seller.isOnline())seller.sendMessage(messages.get(seller,
                        "auction.create-recovery-required"));
                return;
            }
            if (error != null || activation == null || activation.id() < 0) {
                // The durable intent still owns the item/wager. It will be
                // promoted on the next startup instead of running unrelated
                // best-effort refund writes that can lose or duplicate it.
                plugin.getLogger().log(Level.SEVERE, "Failed to activate Auction listing intent " + intent.key()
                        + "; keeping it as durable escrow for startup recovery.", error);
                activeListings.put(finalPlaceholderId, placeholder);
                return;
            }
            int id=activation.id();activeListings.put(id, new AuctionListing(id, seller.getUniqueId(), itemCopy, price, currency, listedAt, expiresAt));
        }));

        return new ListOutcome(ListResult.OK, placeholder);
    }

    private record Activation(int id,boolean recoveryRequired){}

    private AuctionListing listingFrom(AuctionStorage.CreationIntent intent, int id) {
        return new AuctionListing(id, intent.sellerUuid(), intent.item(), intent.price(), intent.currency(),
                intent.listedAt(), intent.expiresAt());
    }

    private ListOutcome recoveryIntent(AuctionStorage.CreationIntent intent) {
        plugin.getLogger().severe("Auction creation intent " + intent.key()
                + " is in DEBITING state and requires staff reconciliation.");
        return ListOutcome.failure(ListResult.RECOVERY_REQUIRED);
    }

    private boolean abortCreation(AuctionStorage.CreationIntent intent, Player seller) {
        try {
            if (!storage.refundCreationIntent(intent.key(), System.currentTimeMillis())) return false;
            // The collection row now owns the item. Attempt immediate delivery,
            // while leaving it durable if the inventory is full or the player disconnects.
            deliverClaims(seller);
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not cancel Auction creation intent " + intent.key()
                    + "; retaining durable escrow instead of duplicating its item.", error);
            return false;
        }
    }

    private void deleteUnusedIntent(AuctionStorage.CreationIntent intent) {
        try { storage.deleteCreationIntent(intent.key()); }
        catch (Exception error) { plugin.getLogger().log(Level.SEVERE,
                "Could not remove unused Auction creation intent " + intent.key(), error); }
    }

    // ---- Buying ----

    public enum BuyResult {
        OK, GONE, IS_SELLER, NO_ECONOMY, CANNOT_AFFORD, NO_GC
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
        GcManager gc = gcManager;
        if (listing.currency() == AuctionCurrency.GC && gc == null) {
            return BuyResult.NO_GC;
        }
        int priceLevels = (int) Math.ceil(listing.price());
        long priceGc = (long) Math.ceil(listing.price());
        if (listing.currency() == AuctionCurrency.MONEY) {
            if (!EconomyHook.isAvailable()) {
                return BuyResult.NO_ECONOMY;
            }
            if (!EconomyHook.getEconomy().has(buyer, listing.price())) {
                return BuyResult.CANNOT_AFFORD;
            }
        } else if (listing.currency() == AuctionCurrency.GC) {
            if (!gc.has(buyer.getUniqueId(), priceGc)) {
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
        } else if (listing.currency() == AuctionCurrency.GC) {
            if (!gc.tryDebit(buyer.getUniqueId(), buyer.getUniqueId(), GcAction.AUCTION_PURCHASE, priceGc, null)) {
                activeListings.put(listingId, listing);
                return BuyResult.CANNOT_AFFORD;
            }
        } else {
            buyer.setLevel(buyer.getLevel() - priceLevels);
        }

        double tax = listing.price() * (saleTaxPercent / 100.0);
        double proceeds = listing.price() - tax;
        // The listing, buyer item claim, and seller payout outbox are one
        // commit. Neither side depends on a later best-effort write.
        boolean settled;
        try {
            settled = storage.settleSale(listing, buyer.getUniqueId(), proceeds, System.currentTimeMillis());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to settle Auction House listing " + listing.id() + " -- nothing was delivered.", error);
            settled = false;
        }
        if (!settled) {
            if (listing.currency() == AuctionCurrency.MONEY) {
                EconomyHook.getEconomy().depositPlayer(buyer, listing.price());
            } else if (listing.currency() == AuctionCurrency.GC) {
                gc.credit(buyer.getUniqueId(), buyer.getUniqueId(), GcAction.AUCTION_REFUND, priceGc, null);
            } else {
                buyer.setLevel(buyer.getLevel() + priceLevels);
            }
            activeListings.put(listingId, listing);
            return BuyResult.GONE;
        }
        clearWatchesForListing(listing.id());
        processPendingPayouts(listing.sellerUuid());
        return BuyResult.OK;
    }

    /** Retries durable seller proceeds, optionally only for one seller. */
    public void processPendingPayouts(UUID ownerFilter) {
        if (shuttingDown.get()) return;
        CompletableFuture<List<AuctionStorage.PendingPayout>> reserve = CompletableFuture.supplyAsync(() -> {
            List<AuctionStorage.PendingPayout> reserved = new ArrayList<>();
            try {
                for (AuctionStorage.PendingPayout payout : storage.loadPendingPayouts()) {
                    if (ownerFilter != null && !ownerFilter.equals(payout.ownerUuid())) continue;
                    if (!pendingPayoutsInProgress.add(payout.key())) continue;
                    try {
                        if (storage.reservePendingPayout(payout.key(), System.currentTimeMillis())) {
                            reserved.add(payout);
                        } else {
                            pendingPayoutsInProgress.remove(payout.key());
                        }
                    } catch (Exception error) {
                        pendingPayoutsInProgress.remove(payout.key());
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not reserve Auction payout " + payout.key(), error);
                    }
                }
                return reserved;
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        }, payoutExecutor);
        track(reserve);
        reserve.whenComplete((reserved, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load Auction House payout outbox", error);
                return;
            }
            if (reserved.isEmpty()) return;
            if (shuttingDown.get() || !plugin.isEnabled()) {
                for (AuctionStorage.PendingPayout payout : reserved) finishPayout(payout.key(), false);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (shuttingDown.get()) {
                    reserved.forEach(payout -> finishPayout(payout.key(), false));
                } else {
                    reserved.forEach(this::deliverPayout);
                }
            });
        });
    }

    /** Performs only Bukkit/Vault work; every SQL transition stays off the primary thread. */
    private void deliverPayout(AuctionStorage.PendingPayout payout) {
        if (shuttingDown.get()) {
            finishPayout(payout.key(), false);
            return;
        }
        if (payout.currency() == AuctionCurrency.GC) {
            GcManager gc = gcManager;
            if (gc == null) {
                finishPayout(payout.key(), false);
                return;
            }
            gc.creditDurably(payout.ownerUuid(), null, GcAction.AUCTION_SALE,
                    Math.round(payout.amount()), payout.key()).whenComplete((success, error) -> {
                if (error != null) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Auction GC payout " + payout.key() + " could not be resolved", error);
                    pendingPayoutsInProgress.remove(payout.key());
                } else {
                    finishPayout(payout.key(), Boolean.TRUE.equals(success));
                }
            });
            return;
        }

        boolean delivered = false;
        boolean definitelyNotDelivered = false;
        try {
            if (payout.currency() == AuctionCurrency.MONEY && EconomyHook.isAvailable()) {
                delivered = EconomyHook.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(payout.ownerUuid()),
                        payout.amount()).transactionSuccess();
                definitelyNotDelivered = !delivered;
            } else if (payout.currency() == AuctionCurrency.EXP) {
                Player online = Bukkit.getPlayer(payout.ownerUuid());
                if (online != null) {
                    online.setLevel(online.getLevel() + (int) Math.round(payout.amount()));
                    delivered = true;
                } else {
                    definitelyNotDelivered = true;
                }
            } else {
                definitelyNotDelivered = true;
            }
        } catch (RuntimeException uncertain) {
            plugin.getLogger().log(Level.SEVERE, "Auction payout " + payout.key()
                    + " has an uncertain external result and will not be replayed automatically.", uncertain);
        }
        if (delivered) finishPayout(payout.key(), true);
        else if (definitelyNotDelivered) finishPayout(payout.key(), false);
        else pendingPayoutsInProgress.remove(payout.key());
    }

    private void finishPayout(String key, boolean delivered) {
        CompletableFuture<Void> finish = CompletableFuture.runAsync(() -> {
            try {
                if (delivered) acknowledgePayout(key);
                else releasePayout(key);
            } finally {
                pendingPayoutsInProgress.remove(key);
            }
        }, payoutExecutor);
        track(finish);
    }

    private void acknowledgePayout(String key) {
        try {
            storage.deletePendingPayout(key);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Auction payout " + key + " was delivered but its outbox row could not be cleared", error);
        }
    }

    private void releasePayout(String key) {
        try {
            storage.releasePendingPayout(key);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not release failed Auction payout " + key, error);
        }
    }

    public List<AuctionStorage.PendingPayout> uncertainPayouts() {
        try { return storage.loadUncertainPayouts(); }
        catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not inspect uncertain Auction payouts", error);
            return List.of();
        }
    }

    public boolean resolveUncertainPayout(String key, boolean paid) {
        try {
            if (paid) {
                if (!storage.acknowledgeUncertainPayout(key)) return false;
            } else if (!storage.releasePendingPayout(key)) return false;
            if (!paid) processPendingPayouts(null);
            plugin.getLogger().warning("Auction payout " + key + " was manually marked "
                    + (paid ? "PAID" : "RETRY") + ".");
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not reconcile Auction payout " + key, error);
            return false;
        }
    }

    private void reportUncertainPayouts() {
        List<AuctionStorage.PendingPayout> uncertain = uncertainPayouts();
        if (!uncertain.isEmpty()) plugin.getLogger().severe("Auction House has " + uncertain.size()
                + " uncertain external payout(s). Inspect with /ah payouts before retrying them.");
    }

    /** Currency-listing creations whose external fee result needs a human decision. */
    public List<AuctionStorage.CreationIntent> unresolvedCreationIntents() {
        try {
            return storage.loadCreationIntents().stream()
                    .filter(intent -> "DEBITING".equals(intent.state()))
                    .toList();
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not inspect Auction creation intents", error);
            return List.of();
        }
    }

    public Optional<AuctionStorage.CreationIntent> creationIntent(String key) {
        try {
            return storage.loadCreationIntent(key).filter(intent -> "DEBITING".equals(intent.state()));
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not inspect Auction creation intent " + key, error);
            return Optional.empty();
        }
    }

    /** Applies one irreversible, permanently audited staff decision. */
    public AuctionStorage.IntentResolution resolveCreationIntent(String key, boolean debited, Player actor) {
        return resolveCreationIntent(key, debited ? AuctionStorage.IntentResolutionDecision.DEBITED
                : AuctionStorage.IntentResolutionDecision.NOT_DEBITED, actor);
    }

    public AuctionStorage.IntentResolution resolveCreationIntent(String key,
            AuctionStorage.IntentResolutionDecision decision, Player actor) {
        try {
            AuctionStorage.IntentResolution resolution = storage.resolveCreationIntent(key, decision,
                    actor.getUniqueId(), actor.getName(), System.currentTimeMillis());
            if (resolution.status() == AuctionStorage.IntentResolutionStatus.ACTIVATED) {
                activeListings.put(resolution.listingId(), listingFrom(resolution.intent(), resolution.listingId()));
            } else if (resolution.status() == AuctionStorage.IntentResolutionStatus.REFUNDED) {
                Player seller = Bukkit.getPlayer(resolution.intent().sellerUuid());
                if (seller != null) deliverClaims(seller);
            }
            if (resolution.status() == AuctionStorage.IntentResolutionStatus.ACTIVATED
                    || resolution.status() == AuctionStorage.IntentResolutionStatus.REFUNDED
                    || resolution.status() == AuctionStorage.IntentResolutionStatus.DISCARDED) {
                plugin.getLogger().warning(actor.getName() + " resolved Auction creation intent " + key + " as "
                        + decision + ".");
            }
            return resolution;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Could not reconcile Auction creation intent " + key, error);
            return new AuctionStorage.IntentResolution(AuctionStorage.IntentResolutionStatus.STORAGE_ERROR,
                    null, -1, null);
        }
    }

    /** Delivers restart-safe proceeds owed while this player was offline. */
    public void applyPendingExp(Player player) {
        processPendingPayouts(player.getUniqueId());
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
        boolean adminCancel = !listing.sellerUuid().equals(actor.getUniqueId());
        if (!settle(listing, null, AuctionLogEntry.Status.CANCELLED, adminCancel ? actor.getUniqueId() : null,
                listing.sellerUuid(), listing.item())) {
            activeListings.put(listing.id(), listing);
            return false;
        }
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
            if (!settle(listing, null, AuctionLogEntry.Status.EXPIRED, null, listing.sellerUuid(), listing.item())) {
                // Left active so the next sweep retries it; returning the item
                // now could hand it back twice.
                activeListings.put(listing.id(), listing);
                continue;
            }
        }
    }

    /**
     * Commits the settlement before anything is handed over.
     *
     * <p>Deliberately synchronous. The old order moved money and items first
     * and persisted afterwards, so a crash in between could resurrect a sold
     * listing on restart while the buyer already had the item. Nothing is
     * delivered now until this has committed, which costs one indexed
     * transaction on the calling thread and removes that entire class of
     * duplication.
     *
     * @return false when the settlement did not commit, meaning the caller
     *         must not deliver anything and should put the listing back
     */
    private boolean settle(AuctionListing listing, UUID buyerUuid, AuctionLogEntry.Status status, UUID cancelledBy) {
        return settle(listing, buyerUuid, status, cancelledBy, null, null);
    }

    private boolean settle(AuctionListing listing, UUID buyerUuid, AuctionLogEntry.Status status, UUID cancelledBy,
            UUID returnClaimOwner, ItemStack returnClaimItem) {
        String summary = listing.item().getAmount() + "x " + listing.item().getType();
        try {
            if (!storage.settleListing(listing.id(), listing.sellerUuid(), buyerUuid, summary, listing.price(),
                    listing.listedAtMillis(), System.currentTimeMillis(), status, cancelledBy,
                    returnClaimOwner, returnClaimItem)) {
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to settle Auction House listing " + listing.id() + " -- nothing was delivered.", e);
            return false;
        }
        clearWatchesForListing(listing.id());
        return true;
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

    /** Compatibility API retained for tests and non-Bukkit storage consumers. */
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

    public enum ClaimResult { CLAIMED, EMPTY, FULL, OFFLINE, FAILED }

    /**
     * Reserves rows, reconciles any interrupted reservation, and acknowledges
     * only after tagged items are present in the player's inventory.
     */
    public CompletableFuture<ClaimResult> deliverClaims(Player player) {
        UUID owner = player.getUniqueId();
        if (!claimsInProgress.add(owner)) return CompletableFuture.completedFuture(ClaimResult.FAILED);
        CompletableFuture<ClaimDeliveryBatch> load = CompletableFuture.supplyAsync(() -> {
            try {
                List<AuctionStorage.ClaimReservation> reservations = new ArrayList<>(
                        storage.loadDeliveringClaims(owner));
                AuctionStorage.ClaimReservation fresh = storage.reserveClaims(owner);
                if (!fresh.claims().isEmpty()) reservations.add(fresh);
                return new ClaimDeliveryBatch(reservations,
                        fresh.claims().isEmpty() ? null : fresh.token());
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
        track(load);
        CompletableFuture<ClaimResult> result = load.thenCompose(batch -> {
            CompletableFuture<ClaimResult> delivered = new CompletableFuture<>();
            Bukkit.getScheduler().runTask(plugin, () -> deliverReservations(player, batch, delivered));
            return delivered;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Failed to reserve Auction House claims for " + owner, error);
            return ClaimResult.FAILED;
        });
        result.whenComplete((ignored, error) -> claimsInProgress.remove(owner));
        return result;
    }

    private record ClaimDeliveryBatch(List<AuctionStorage.ClaimReservation> reservations, String freshToken) { }

    private void deliverReservations(Player player, ClaimDeliveryBatch batch,
            CompletableFuture<ClaimResult> result) {
        List<AuctionStorage.ClaimReservation> reservations = batch.reservations();
        if (!player.isOnline()) {
            releaseFreshReservation(player.getUniqueId(), batch, result, ClaimResult.OFFLINE);
            return;
        }
        if (reservations.isEmpty()) {
            ClaimDelivery.clearSourceMarkers(player, plugin, "auction");
            result.complete(ClaimResult.EMPTY);
            return;
        }
        deliverReservationAt(player, batch, 0, false, result);
    }

    private void deliverReservationAt(Player player, ClaimDeliveryBatch batch, int index,
            boolean deliveredAny, CompletableFuture<ClaimResult> result) {
        List<AuctionStorage.ClaimReservation> reservations = batch.reservations();
        if (index >= reservations.size()) {
            result.complete(deliveredAny ? ClaimResult.CLAIMED : ClaimResult.EMPTY);
            return;
        }
        AuctionStorage.ClaimReservation reservation = reservations.get(index);
        List<ClaimDelivery.TaggedItem> expected = reservation.claims().stream()
                .map(claim -> ClaimDelivery.tagged(plugin, "auction", reservation.token(), claim.id(), 0,
                        claim.item()))
                .toList();
        List<ClaimDelivery.TaggedItem> missing = ClaimDelivery.missing(player, plugin, expected);
        if (!ClaimDelivery.canFit(player, missing) || !ClaimDelivery.add(player, missing)) {
            releaseFreshReservation(player.getUniqueId(), batch, result, ClaimResult.FULL);
            return;
        }
        CompletableFuture<Integer> complete = CompletableFuture.supplyAsync(() -> {
            try {
                return storage.completeReservation(player.getUniqueId(), reservation.token());
            } catch (Exception error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
        track(complete);
        complete.whenComplete((changed, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || changed == null || changed != reservation.claims().size()) {
                plugin.getLogger().log(Level.SEVERE, "Could not acknowledge Auction claim reservation "
                        + reservation.token() + "; tagged items remain reconcilable.", error);
                result.complete(ClaimResult.FAILED);
                return;
            }
            ClaimDelivery.clearMarkers(player, plugin, "auction", reservation.token());
            deliverReservationAt(player, batch, index + 1, true, result);
        }));
    }

    private void releaseFreshReservation(UUID owner, ClaimDeliveryBatch batch,
            CompletableFuture<ClaimResult> result, ClaimResult releasedResult) {
        if (batch.freshToken() == null) {
            result.complete(releasedResult);
            return;
        }
        List<AuctionStorage.ClaimReservation> fresh = batch.reservations().stream()
                .filter(reservation -> reservation.token().equals(batch.freshToken())).toList();
        releaseReservations(owner, fresh, result, releasedResult);
    }

    private void releaseReservations(UUID owner, List<AuctionStorage.ClaimReservation> reservations,
            CompletableFuture<ClaimResult> result, ClaimResult releasedResult) {
        CompletableFuture<Void> release = CompletableFuture.runAsync(() -> {
            for (AuctionStorage.ClaimReservation reservation : reservations) {
                try {
                    storage.releaseReservation(owner, reservation.token());
                } catch (Exception error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }
        });
        track(release);
        release.whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Could not release Auction claim reservation for " + owner,
                        error);
                result.complete(ClaimResult.FAILED);
            } else {
                result.complete(releasedResult);
            }
        });
    }

    /** Restores a claim batch that could not be handed over (disconnect/full inventory). */
    public void restoreClaims(UUID ownerUuid, List<ItemStack> items) {
        if (items == null) return;
        for (ItemStack item : items) {
            if (item != null && !item.isEmpty()) queueClaim(ownerUuid, item.clone());
        }
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
            online.getInventory().addItem(item.clone()).values().forEach(leftover -> queueClaim(ownerUuid, leftover));
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

    public void shutdown() {
        shuttingDown.set(true);
        // A reserve callback may already have queued its main-thread payout
        // task. onDisable cannot run that task while it is blocking here, so
        // release every still-reserved row off the bounded payout executor before
        // the pool closes.
        for (String key : List.copyOf(pendingPayoutsInProgress)) {
            CompletableFuture<Void> clear = CompletableFuture.runAsync(() -> {
                try {
                    releasePayout(key);
                } finally {
                    pendingPayoutsInProgress.remove(key);
                }
            }, payoutExecutor);
            track(clear);
        }
        awaitWrites();
        payoutExecutor.shutdown();
        try {
            if (!payoutExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                payoutExecutor.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            payoutExecutor.shutdownNow();
        }
    }

    public void awaitWrites() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int emptyRounds = 0;
        try {
            while (System.nanoTime() < deadline) {
                CompletableFuture<?>[] snapshot = pendingWrites.toArray(new CompletableFuture[0]);
                if (snapshot.length == 0) {
                    if (++emptyRounds >= 3) return;
                    TimeUnit.MILLISECONDS.sleep(2L);
                    continue;
                }
                emptyRounds = 0;
                long remaining = Math.max(1L, deadline - System.nanoTime());
                CompletableFuture.allOf(snapshot).get(remaining, TimeUnit.NANOSECONDS);
            }
            plugin.getLogger().warning("Timed out waiting for pending Auction House writes.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, "Interrupted while waiting for Auction House writes.", e);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for pending Auction House writes.", e);
        }
    }
}
