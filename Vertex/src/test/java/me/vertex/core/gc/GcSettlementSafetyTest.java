package me.vertex.core.gc;

import me.vertex.core.auction.*;
import me.vertex.core.coinflip.*;
import me.vertex.core.storage.Database;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GcSettlementSafetyTest {
    @TempDir Path folder;
    private Database database;
    private GcStorage gc;
    private AuctionStorage auctions;
    private CoinflipStorage coinflips;
    private final UUID buyer = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        MockBukkit.mock();
        database = new Database(new YamlConfiguration(), folder.toFile());
        gc = new GcStorage(database); gc.init();
        auctions = new AuctionStorage(database); auctions.init();
        coinflips = new CoinflipStorage(database); coinflips.init();
        gc.setAbsolute(buyer, null, GcAction.STAFF_SET, 100, "seed", 1);
    }
    @AfterEach void cleanup() { if (database != null) database.close(); MockBukkit.unmock(); }

    private AuctionListing listing() throws Exception {
        ItemStack item = new ItemStack(Material.DIAMOND, 2);
        int id = auctions.insertListing(seller, item, 100, AuctionCurrency.GC, 1, Long.MAX_VALUE);
        return new AuctionListing(id, seller, item, 100, AuctionCurrency.GC, 1, Long.MAX_VALUE);
    }
    private Coinflip flip() throws Exception {
        int id = coinflips.insertCoinflip(seller, null, CoinflipType.GC, 100, null, 1);
        return new Coinflip(id, seller, null, CoinflipType.GC, 100, null, 1);
    }
    private void rejectLedgerDebit() throws Exception {
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER fail_gc BEFORE INSERT ON gc_log "
                    + "WHEN NEW.action != 'STAFF_SET' BEGIN SELECT RAISE(ABORT, 'injected ledger failure'); END");
        }
    }

    @Test void failedAuctionDebitCannotDeliverItemsOrSellerProceeds() throws Exception {
        var listing = listing(); rejectLedgerDebit();
        assertThrows(SQLException.class, () -> auctions.settleSale(listing, buyer, 95, 2));
        assertEquals(100, gc.loadBalance(buyer));
        assertEquals(1, auctions.loadAllListings().size());
        assertTrue(auctions.loadClaims(buyer).isEmpty());
        assertTrue(auctions.loadPendingPayouts().isEmpty());
    }

    @Test void auctionDebitRewardAndOutboxCommitOnceTogether() throws Exception {
        var listing = listing();
        assertTrue(auctions.settleSale(listing, buyer, 95, 2));
        assertFalse(auctions.settleSale(listing, buyer, 95, 3));
        assertEquals(0, gc.loadBalance(buyer));
        assertEquals(1, auctions.loadClaims(buyer).size());
        assertEquals(1, auctions.loadPendingPayouts().size());
        assertEquals(1, gc.loadLog(buyer, 10, 0).stream().filter(log -> log.action() == GcAction.AUCTION_PURCHASE).count());
    }

    @Test void failedCoinflipJoinDebitCannotCommitWinnerPayoutOrNotifications() throws Exception {
        var flip = flip(); rejectLedgerDebit();
        assertThrows(SQLException.class, () -> coinflips.resolveCoinflip(flip, buyer, buyer, "100 GC", 2, null, 200, null));
        assertEquals(100, gc.loadBalance(buyer));
        assertEquals(1, coinflips.loadAllCoinflips().size());
        assertTrue(coinflips.loadPendingPayouts().isEmpty());
        assertTrue(coinflips.loadResultNotifications(buyer).isEmpty());
        assertTrue(coinflips.loadResultNotifications(seller).isEmpty());
    }

    @Test void competingAuctionAndCoinflipCannotSpendTheSameWallet() throws Exception {
        var listing = listing(); var flip = flip();
        assertTrue(auctions.settleSale(listing, buyer, 95, 2));
        assertThrows(GcStorage.BalanceRejectedException.class,
                () -> coinflips.resolveCoinflip(flip, buyer, buyer, "100 GC", 3, null, 200, null));
        assertEquals(0, gc.loadBalance(buyer));
        assertEquals(1, coinflips.loadAllCoinflips().size());
        assertTrue(coinflips.loadPendingPayouts().isEmpty());
    }

    @Test void coinflipDebitAndResultCommitTogetherAndReplayCannotChargeAgain() throws Exception {
        var flip = flip();
        assertTrue(coinflips.resolveCoinflip(flip, buyer, buyer, "100 GC", 2, null, 200, null));
        assertFalse(coinflips.resolveCoinflip(flip, buyer, seller, "100 GC", 3, null, 200, null));
        assertEquals(0, gc.loadBalance(buyer));
        assertEquals(1, coinflips.loadPendingPayouts().size());
        assertEquals(buyer, coinflips.loadPendingPayouts().getFirst().ownerUuid());
        assertEquals(1, coinflips.loadResultNotifications(buyer).size());
    }
}
