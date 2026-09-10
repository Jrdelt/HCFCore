package me.vertex.core.auction;

import me.vertex.core.storage.Database;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link AuctionManager} against a real local (SQLite) database, matching CoinflipManagerTest's approach. */
class AuctionManagerTest {

    @TempDir
    Path dataFolder;

    private ServerMock server;
    private PluginMock plugin;
    private Database database;
    private AuctionStorage storage;
    private AuctionManager manager;
    private FakeEconomy economy;
    private PlayerMock seller;
    private PlayerMock buyer;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        YamlConfiguration dbConfig = new YamlConfiguration();
        database = new Database(dbConfig, dataFolder.toFile());

        storage = new AuctionStorage(database);
        storage.init();

        manager = new AuctionManager(plugin, storage);
        manager.load();
        manager.loadState();

        economy = new FakeEconomy();
        Bukkit.getServicesManager().register(Economy.class, economy, plugin, ServicePriority.Normal);

        seller = server.addPlayer("Seller");
        buyer = server.addPlayer("Buyer");
        economy.set(seller.getUniqueId(), 1000.0);
        economy.set(buyer.getUniqueId(), 1000.0);
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            settle();
        }
        if (database != null) {
            database.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void listingAddsToActiveListingsOnceThePersistCompletes() {
        AuctionManager.ListOutcome outcome = manager.list(seller, new ItemStack(Material.DIAMOND, 3), 100.0, AuctionCurrency.MONEY);

        assertEquals(AuctionManager.ListResult.OK, outcome.result());
        settle();

        List<AuctionListing> active = manager.activeListings();
        assertEquals(1, active.size());
        assertEquals(100.0, active.get(0).price());
        assertEquals(Material.DIAMOND, active.get(0).item().getType());
        assertEquals(3, active.get(0).item().getAmount());
    }

    @Test
    void listingRejectsAPriceOutsideConfiguredBounds() {
        AuctionManager.ListOutcome tooLow = manager.list(seller, new ItemStack(Material.DIAMOND), 0.01, AuctionCurrency.MONEY);
        AuctionManager.ListOutcome tooHigh = manager.list(seller, new ItemStack(Material.DIAMOND), 1_000_000_000.0, AuctionCurrency.MONEY);

        assertEquals(AuctionManager.ListResult.OUT_OF_RANGE, tooLow.result());
        assertEquals(AuctionManager.ListResult.OUT_OF_RANGE, tooHigh.result());
    }

    @Test
    void listingRejectsMoreThanTheConfiguredMaxActivePerPlayer() {
        for (int i = 0; i < 10; i++) {
            AuctionManager.ListOutcome outcome = manager.list(seller, new ItemStack(Material.DIRT), 5.0, AuctionCurrency.MONEY);
            assertEquals(AuctionManager.ListResult.OK, outcome.result());
        }
        settle();

        AuctionManager.ListOutcome eleventh = manager.list(seller, new ItemStack(Material.DIRT), 5.0, AuctionCurrency.MONEY);

        assertEquals(AuctionManager.ListResult.TOO_MANY_LISTINGS, eleventh.result());
    }

    @Test
    void buyingTransfersMoneyAndTheItemAndRemovesTheListing() {
        manager.list(seller, new ItemStack(Material.DIAMOND, 5), 200.0, AuctionCurrency.MONEY);
        settle();
        int listingId = manager.activeListings().get(0).id();

        AuctionManager.BuyResult result = manager.buy(listingId, buyer);

        assertEquals(AuctionManager.BuyResult.OK, result);
        assertEquals(800.0, economy.get(buyer.getUniqueId()), 0.0001);
        assertEquals(1200.0, economy.get(seller.getUniqueId()), 0.0001);
        assertEquals(5, countInInventory(buyer, Material.DIAMOND));
        assertTrue(manager.activeListings().isEmpty());
    }

    @Test
    void buyingFailsAndStaysActiveIfTheBuyerCannotAfford() {
        manager.list(seller, new ItemStack(Material.DIAMOND), 200.0, AuctionCurrency.MONEY);
        settle();
        int listingId = manager.activeListings().get(0).id();
        economy.set(buyer.getUniqueId(), 10.0);

        AuctionManager.BuyResult result = manager.buy(listingId, buyer);

        assertEquals(AuctionManager.BuyResult.CANNOT_AFFORD, result);
        assertEquals(1, manager.activeListings().size());
        assertEquals(10.0, economy.get(buyer.getUniqueId()));
    }

    @Test
    void sellerCannotBuyTheirOwnListing() {
        manager.list(seller, new ItemStack(Material.DIAMOND), 200.0, AuctionCurrency.MONEY);
        settle();
        int listingId = manager.activeListings().get(0).id();

        AuctionManager.BuyResult result = manager.buy(listingId, seller);

        assertEquals(AuctionManager.BuyResult.IS_SELLER, result);
        assertEquals(1, manager.activeListings().size());
    }

    @Test
    void buyingAnAlreadySoldListingReportsGone() {
        manager.list(seller, new ItemStack(Material.DIAMOND), 200.0, AuctionCurrency.MONEY);
        settle();
        int listingId = manager.activeListings().get(0).id();
        manager.buy(listingId, buyer);

        AuctionManager.BuyResult second = manager.buy(listingId, server.addPlayer("Third"));

        assertEquals(AuctionManager.BuyResult.GONE, second);
    }

    @Test
    void cancellingCreatesADurableCollectionClaimEvenForAnOnlineSeller() {
        manager.list(seller, new ItemStack(Material.EMERALD, 4), 200.0, AuctionCurrency.MONEY);
        settle();
        AuctionListing listing = manager.activeListings().get(0);

        boolean cancelled = manager.cancel(listing, seller);

        assertTrue(cancelled);
        assertEquals(0, countInInventory(seller, Material.EMERALD));
        assertTrue(manager.hasClaims(seller.getUniqueId()));
        assertEquals(4, manager.loadClaimItems(seller.getUniqueId()).get(0).getAmount());
        assertTrue(manager.activeListings().isEmpty());
    }

    @Test
    void onlySellerOrPermittedStaffCanCancel() {
        manager.list(seller, new ItemStack(Material.EMERALD), 200.0, AuctionCurrency.MONEY);
        settle();
        AuctionListing listing = manager.activeListings().get(0);
        PlayerMock stranger = server.addPlayer("Stranger");

        assertFalse(manager.canCancel(stranger, listing));
        assertTrue(manager.canCancel(seller, listing));
    }

    @Test
    void sweepExpiredReturnsAnAlreadyExpiredListingToTheSellersClaimStash() throws Exception {
        long past = System.currentTimeMillis() - 1000;
        storage.insertListing(seller.getUniqueId(), new ItemStack(Material.NETHERITE_INGOT, 2), 500.0,
                AuctionCurrency.MONEY, past - 1000, past);
        manager.loadState();
        assertEquals(1, manager.activeListings().size());

        manager.sweepExpired();
        settle();

        assertTrue(manager.activeListings().isEmpty());
        assertTrue(manager.hasClaims(seller.getUniqueId()));
        List<ItemStack> claimed = manager.loadClaimItems(seller.getUniqueId());
        assertEquals(1, claimed.size());
        assertEquals(Material.NETHERITE_INGOT, claimed.get(0).getType());
        assertEquals(2, claimed.get(0).getAmount());
    }

    @Test
    void sweepExpiredLeavesAnUnexpiredListingAlone() {
        manager.list(seller, new ItemStack(Material.DIRT), 5.0, AuctionCurrency.MONEY);
        settle();

        manager.sweepExpired();

        assertEquals(1, manager.activeListings().size());
    }

    @Test
    void marketDataPersistsAcrossAFreshManagerLoadingFromTheSameDatabase() {
        manager.list(seller, new ItemStack(Material.DIAMOND, 7), 321.0, AuctionCurrency.MONEY);
        settle();

        AuctionManager reloaded = new AuctionManager(plugin, storage);
        reloaded.load();
        reloaded.loadState();

        assertEquals(1, reloaded.activeListings().size());
        assertEquals(321.0, reloaded.activeListings().get(0).price());
        assertEquals(7, reloaded.activeListings().get(0).item().getAmount());
        assertEquals(AuctionCurrency.MONEY, reloaded.activeListings().get(0).currency());
    }

    @Test
    void experienceCurrencyListingPaysAndTakesLevelsInsteadOfMoney() {
        seller.setLevel(50);
        buyer.setLevel(50);

        AuctionManager.ListOutcome outcome = manager.list(seller, new ItemStack(Material.DIAMOND), 20.0, AuctionCurrency.EXP);
        assertEquals(AuctionManager.ListResult.OK, outcome.result());
        settle();
        int listingId = manager.activeListings().get(0).id();
        assertEquals(AuctionCurrency.EXP, manager.activeListings().get(0).currency());
        // Listing an EXP-priced item doesn't itself charge the seller anything (only buying does).
        assertEquals(50, seller.getLevel());

        AuctionManager.BuyResult result = manager.buy(listingId, buyer);

        assertEquals(AuctionManager.BuyResult.OK, result);
        assertEquals(30, buyer.getLevel(), "the buyer must pay in levels, not money");
        assertEquals(70, seller.getLevel(), "the seller must be paid in levels, not money");
        assertEquals(1000.0, economy.get(buyer.getUniqueId()), 0.0001, "money must be untouched by an EXP-currency trade");
        assertEquals(1000.0, economy.get(seller.getUniqueId()), 0.0001);
    }

    @Test
    void buyingAnExperienceListingFailsWithoutEnoughLevels() {
        seller.setLevel(50);
        buyer.setLevel(5);
        manager.list(seller, new ItemStack(Material.DIAMOND), 20.0, AuctionCurrency.EXP);
        settle();
        int listingId = manager.activeListings().get(0).id();

        AuctionManager.BuyResult result = manager.buy(listingId, buyer);

        assertEquals(AuctionManager.BuyResult.CANNOT_AFFORD, result);
        assertEquals(5, buyer.getLevel());
        assertEquals(1, manager.activeListings().size());
    }

    @Test
    void watchingAListingAddsItToTheWatchlistAndUnwatchingRemovesIt() {
        manager.list(seller, new ItemStack(Material.DIAMOND), 200.0, AuctionCurrency.MONEY);
        settle();
        int listingId = manager.activeListings().get(0).id();

        assertFalse(manager.isWatching(buyer.getUniqueId(), listingId));
        boolean nowWatching = manager.toggleWatch(buyer.getUniqueId(), listingId);
        assertTrue(nowWatching);
        assertTrue(manager.isWatching(buyer.getUniqueId(), listingId));
        assertEquals(1, manager.watchedListings(buyer.getUniqueId()).size());

        boolean stillWatching = manager.toggleWatch(buyer.getUniqueId(), listingId);
        assertFalse(stillWatching);
        assertFalse(manager.isWatching(buyer.getUniqueId(), listingId));
        assertTrue(manager.watchedListings(buyer.getUniqueId()).isEmpty());
    }

    @Test
    void watchlistPersistsAcrossAFreshManagerLoadingFromTheSameDatabase() {
        manager.list(seller, new ItemStack(Material.DIAMOND), 200.0, AuctionCurrency.MONEY);
        settle();
        int listingId = manager.activeListings().get(0).id();
        manager.toggleWatch(buyer.getUniqueId(), listingId);
        settle();

        AuctionManager reloaded = new AuctionManager(plugin, storage);
        reloaded.load();
        reloaded.loadState();

        assertTrue(reloaded.isWatching(buyer.getUniqueId(), listingId));
    }

    @Test
    void aResolvedListingIsRemovedFromEveryonesWatchlist() {
        manager.list(seller, new ItemStack(Material.DIAMOND), 200.0, AuctionCurrency.MONEY);
        settle();
        int listingId = manager.activeListings().get(0).id();
        manager.toggleWatch(buyer.getUniqueId(), listingId);
        assertTrue(manager.isWatching(buyer.getUniqueId(), listingId));

        manager.buy(listingId, buyer);

        assertFalse(manager.isWatching(buyer.getUniqueId(), listingId), "a sold listing must not linger on anyone's watchlist");
    }

    @Test
    void priceSortIsHighestFirstWithOldestAndThenIdAsDeterministicTies() {
        UUID sellerId = seller.getUniqueId();
        List<AuctionListing> listings = List.of(
                new AuctionListing(3, sellerId, new ItemStack(Material.DIAMOND), 100, AuctionCurrency.MONEY, 30, 500),
                new AuctionListing(2, sellerId, new ItemStack(Material.EMERALD), 100, AuctionCurrency.MONEY, 20, 500),
                new AuctionListing(1, sellerId, new ItemStack(Material.GOLD_INGOT), 200, AuctionCurrency.MONEY, 40, 500),
                new AuctionListing(4, sellerId, new ItemStack(Material.IRON_INGOT), 100, AuctionCurrency.MONEY, 20, 500));

        List<Integer> ids = listings.stream()
                .sorted(AuctionMenu.listingComparator(AuctionMenu.SortMode.PRICE, AuctionMenu.SortDirection.DESCENDING))
                .map(AuctionListing::id)
                .toList();

        assertEquals(List.of(1, 2, 4, 3), ids);
    }

    @Test
    void datePostedSortIsOldestFirstWithIdAsDeterministicTieBreaker() {
        UUID sellerId = seller.getUniqueId();
        List<AuctionListing> listings = List.of(
                new AuctionListing(3, sellerId, new ItemStack(Material.DIAMOND), 100, AuctionCurrency.MONEY, 20, 500),
                new AuctionListing(1, sellerId, new ItemStack(Material.EMERALD), 100, AuctionCurrency.MONEY, 10, 500),
                new AuctionListing(2, sellerId, new ItemStack(Material.GOLD_INGOT), 100, AuctionCurrency.MONEY, 10, 500));

        List<Integer> ids = listings.stream()
                .sorted(AuctionMenu.listingComparator(AuctionMenu.SortMode.DATE_POSTED, AuctionMenu.SortDirection.ASCENDING))
                .map(AuctionListing::id)
                .toList();

        assertEquals(List.of(1, 2, 3), ids);
    }

    private int countInInventory(PlayerMock inventoryOwner, Material material) {
        int count = 0;
        for (ItemStack item : inventoryOwner.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private static final class FakeEconomy implements Economy {
        private final Map<UUID, Double> balances = new ConcurrentHashMap<>();

        void set(UUID uuid, double amount) {
            balances.put(uuid, amount);
        }

        double get(UUID uuid) {
            return balances.getOrDefault(uuid, 0.0);
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return get(player.getUniqueId()) >= amount;
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            if (get(player.getUniqueId()) < amount) {
                return new EconomyResponse(0, get(player.getUniqueId()), EconomyResponse.ResponseType.FAILURE, "insufficient funds");
            }
            balances.merge(player.getUniqueId(), -amount, Double::sum);
            return new EconomyResponse(amount, get(player.getUniqueId()), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            balances.merge(player.getUniqueId(), amount, Double::sum);
            return new EconomyResponse(amount, get(player.getUniqueId()), EconomyResponse.ResponseType.SUCCESS, null);
        }

        @Override
        public String format(double amount) {
            return "$" + amount;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getName() {
            return "FakeEconomy";
        }

        @Override
        public boolean hasBankSupport() {
            return false;
        }

        @Override
        public int fractionalDigits() {
            return 2;
        }

        @Override
        public String currencyNamePlural() {
            return "Dollars";
        }

        @Override
        public String currencyNameSingular() {
            return "Dollar";
        }

        @Override
        public boolean hasAccount(String playerName) {
            return true;
        }

        @Override
        public boolean hasAccount(OfflinePlayer player) {
            return true;
        }

        @Override
        public boolean hasAccount(String playerName, String worldName) {
            return true;
        }

        @Override
        public boolean hasAccount(OfflinePlayer player, String worldName) {
            return true;
        }

        @Override
        public double getBalance(String playerName) {
            return 0;
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            return get(player.getUniqueId());
        }

        @Override
        public double getBalance(String playerName, String world) {
            return 0;
        }

        @Override
        public double getBalance(OfflinePlayer player, String world) {
            return get(player.getUniqueId());
        }

        @Override
        public boolean has(String playerName, double amount) {
            return false;
        }

        @Override
        public boolean has(String playerName, String worldName, double amount) {
            return false;
        }

        @Override
        public boolean has(OfflinePlayer player, String worldName, double amount) {
            return has(player, amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
            return withdrawPlayer(player, amount);
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
            return depositPlayer(player, amount);
        }

        @Override
        public EconomyResponse createBank(String name, String player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse createBank(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse deleteBank(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankBalance(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankHas(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankWithdraw(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse bankDeposit(String name, double amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankOwner(String name, String playerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankMember(String name, String playerName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EconomyResponse isBankMember(String name, OfflinePlayer player) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getBanks() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean createPlayerAccount(String playerName) {
            return true;
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player) {
            return true;
        }

        @Override
        public boolean createPlayerAccount(String playerName, String worldName) {
            return true;
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
            return true;
        }
    }

    /**
     * Waits for the insert AND runs the main-thread task that swaps the
     * in-memory placeholder for its real database id. Until that task runs the
     * entry is still pending, and pending entries deliberately refuse every
     * action -- exactly as a player would find them.
     */
    private void settle() {
        manager.awaitWrites();
        BukkitSchedulerMock scheduler = (BukkitSchedulerMock) server.getScheduler();
        // awaitWrites returns once the insert itself completes, which can be
        // a moment before its callback has even been queued onto the main
        // thread -- so wait for the placeholder to actually be gone rather
        // than assuming a fixed number of ticks is enough.
        for (int attempt = 0; attempt < 100 && manager.activeListings().stream().anyMatch(AuctionListing::isPending); attempt++) {
            scheduler.performTicks(1L);
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        scheduler.performTicks(1L);
    }


    /**
     * The dupe this guard exists for: buying a listing before its insert
     * lands removes it, and the insert callback then re-adds it under its
     * real id -- putting the same item back up for sale after it was sold.
     */
    @Test
    void aListingCannotBeBoughtBeforeItIsDurable() {
        manager.list(seller, new ItemStack(Material.DIAMOND, 5), 200.0, AuctionCurrency.MONEY);

        AuctionListing pending = manager.activeListings().get(0);
        assertTrue(pending.isPending(), "a freshly listed item has no database id yet");
        assertEquals(AuctionManager.BuyResult.GONE, manager.buy(pending.id(), buyer));
        assertEquals(1000.0, economy.get(buyer.getUniqueId()), 0.0001, "the buyer must not have paid");

        settle();
        assertEquals(1, manager.activeListings().size(), "the listing should survive to become durable");
        assertFalse(manager.activeListings().get(0).isPending());
        assertEquals(AuctionManager.BuyResult.OK, manager.buy(manager.activeListings().get(0).id(), buyer));
    }

    @Test
    void aPendingListingCannotBeCancelledOrSwept() {
        manager.list(seller, new ItemStack(Material.DIAMOND, 5), 200.0, AuctionCurrency.MONEY);
        AuctionListing pending = manager.activeListings().get(0);

        assertFalse(manager.cancel(pending, seller), "a pending listing must not be cancellable");
        manager.sweepExpired();
        assertEquals(1, manager.activeListings().size(), "the sweep must leave a pending listing alone");
    }

    /**
     * Claiming is authorised by the delete, not by the menu's snapshot, so a
     * repeated claim cannot hand the same items out twice.
     */
    @Test
    void collectionBoxPaysOutExactlyOnce() throws Exception {
        storage.insertClaim(seller.getUniqueId(), new ItemStack(Material.DIAMOND, 3), System.currentTimeMillis());

        List<ItemStack> first = manager.takeClaims(seller.getUniqueId()).get();
        List<ItemStack> second = manager.takeClaims(seller.getUniqueId()).get();

        assertEquals(1, first.size(), "the first claim gets the items");
        assertEquals(List.of(), second, "a second claim must get nothing");
    }

    @Test
    void aClaimQueuedAfterTheMenuOpenedIsNotDestroyed() throws Exception {
        storage.insertClaim(seller.getUniqueId(), new ItemStack(Material.DIAMOND, 3), System.currentTimeMillis());
        assertEquals(1, manager.takeClaims(seller.getUniqueId()).get().size());

        storage.insertClaim(seller.getUniqueId(), new ItemStack(Material.EMERALD, 1), System.currentTimeMillis());
        assertEquals(1, manager.takeClaims(seller.getUniqueId()).get().size(),
                "a claim queued later must still be collectable");
    }


    /**
     * Settlement is committed before anything is delivered, so a persistence
     * failure must leave the world exactly as it was -- buyer refunded,
     * listing still for sale, item undelivered. The old order paid and
     * delivered first, which let a crash resurrect a sold listing.
     */
    @Test
    void aFailedSettlementDeliversNothingAndRefundsTheBuyer() {
        manager.list(seller, new ItemStack(Material.DIAMOND, 5), 200.0, AuctionCurrency.MONEY);
        settle();
        int listingId = manager.activeListings().get(0).id();

        database.close();
        AuctionManager.BuyResult result = manager.buy(listingId, buyer);

        assertEquals(AuctionManager.BuyResult.GONE, result);
        assertEquals(1000.0, economy.get(buyer.getUniqueId()), 0.0001, "the buyer must have been refunded");
        assertEquals(1000.0, economy.get(seller.getUniqueId()), 0.0001, "the seller must not have been paid");
        assertEquals(0, countInInventory(buyer, Material.DIAMOND), "the item must not have been delivered");
        assertEquals(1, manager.activeListings().size(), "the listing must still be for sale");
    }

    @Test
    void aFailedSettlementLeavesACancelledListingInPlace() {
        manager.list(seller, new ItemStack(Material.DIAMOND, 5), 200.0, AuctionCurrency.MONEY);
        settle();
        AuctionListing listing = manager.activeListings().get(0);

        database.close();

        assertFalse(manager.cancel(listing, seller), "a cancel that cannot be recorded must not succeed");
        assertEquals(1, manager.activeListings().size(), "the listing must still be for sale");
    }

}
