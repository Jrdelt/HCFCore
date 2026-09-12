package me.vertex.core.trade;

import me.vertex.core.storage.Database;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for the trade-claim delete-before-delivery rule. */
class TradeStorageTest {

    @TempDir
    Path dataFolder;

    private Database database;
    private TradeStorage storage;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new TradeStorage(database);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void aClaimCanOnlyBeTakenOnce() throws Exception {
        storage.insertClaim(owner, new ItemStack(Material.DIAMOND, 3));

        List<ItemStack> first = storage.takeClaims(owner);
        List<ItemStack> second = storage.takeClaims(owner);

        assertEquals(1, first.size());
        assertEquals(Material.DIAMOND, first.get(0).getType());
        assertEquals(3, first.get(0).getAmount());
        assertEquals(List.of(), second, "a second delivery path must not receive the same claim");
    }

    @Test
    void aLaterClaimIsNotDeletedWithAnEarlierClaimBatch() throws Exception {
        storage.insertClaim(owner, new ItemStack(Material.DIAMOND, 1));
        assertEquals(1, storage.takeClaims(owner).size());

        storage.insertClaim(owner, new ItemStack(Material.EMERALD, 2));
        List<ItemStack> later = storage.takeClaims(owner);

        assertEquals(1, later.size());
        assertEquals(Material.EMERALD, later.get(0).getType());
        assertEquals(2, later.get(0).getAmount());
    }

    @Test
    void completedEscrowIsAtomicallySwappedIntoRecipientClaims() throws Exception {
        UUID requester = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        TradeSnapshot snapshot = new TradeSnapshot(UUID.randomUUID(), requester, target,
                new ItemStack[] { new ItemStack(Material.DIAMOND, 3) },
                new ItemStack[] { new ItemStack(Material.EMERALD, 2) },
                0, 0, 0, 0, 0, 0, 0, 0);
        storage.replaceEscrow(snapshot);

        storage.settleEscrow(snapshot, "Requester", "Target", "COMPLETED", true);

        assertTrue(storage.loadEscrow().isEmpty());
        assertEquals(Material.EMERALD, storage.loadClaims(requester).getFirst().getType());
        assertEquals(Material.DIAMOND, storage.loadClaims(target).getFirst().getType());
        assertEquals(1, storage.loadHistory(null, 10, 0).size());
    }

    @Test
    void legacyPendingCreditsMigrateIntoTheOutboxExactlyOnce() throws Exception {
        UUID expOwner = UUID.randomUUID();
        UUID moneyOwner = UUID.randomUUID();
        insertLegacy("trade_pending_exp", "levels", expOwner, 17);
        insertLegacy("trade_pending_money", "amount", moneyOwner, 125.5);

        storage.init();

        List<TradeStorage.PendingPayout> payouts = storage.loadPendingPayouts();
        assertEquals(2, payouts.size());
        assertTrue(payouts.stream().anyMatch(payout -> payout.key().equals("legacy-exp:" + expOwner)
                && payout.currency() == TradeStorage.PayoutCurrency.EXP && payout.amount() == 17));
        assertTrue(payouts.stream().anyMatch(payout -> payout.key().equals("legacy-money:" + moneyOwner)
                && payout.currency() == TradeStorage.PayoutCurrency.MONEY && payout.amount() == 125.5));
        assertEquals(0, countRows("trade_pending_exp"));
        assertEquals(0, countRows("trade_pending_money"));

        storage.init();
        assertEquals(2, storage.loadPendingPayouts().size(),
                "restarting after migration must not duplicate a payout");
    }

    private void insertLegacy(String table, String amountColumn, UUID uuid, Number amount) throws Exception {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + table + " (uuid," + amountColumn + ") VALUES (?,?)")) {
            statement.setString(1, uuid.toString());
            statement.setObject(2, amount);
            statement.executeUpdate();
        }
    }

    private int countRows(String table) throws Exception {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table); ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
