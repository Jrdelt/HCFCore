package me.vertex.core.trade;

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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class TradeOwnershipTest {
    @TempDir Path directory;
    private Database database;

    @BeforeEach void setup() {
        MockBukkit.mock();
        database = new Database(new YamlConfiguration(), directory.toFile());
    }
    @AfterEach void cleanup() { database.close(); MockBukkit.unmock(); }

    private TradeStorage start(String shard, boolean network) throws Exception {
        TradeStorage storage = new TradeStorage(database, shard, network);
        storage.init(); storage.startOwnership(); return storage;
    }

    private TradeSnapshot snapshot() {
        return new TradeSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new ItemStack[]{new ItemStack(Material.DIAMOND, 3)},
                new ItemStack[]{new ItemStack(Material.EMERALD, 2)}, 0,0,0,0,0,0,0,0);
    }

    @Test void anotherShardCannotRecoverOrSettleAnActiveTrade() throws Exception {
        var a = start("factions", true); var snapshot = snapshot(); a.replaceEscrow(snapshot);
        var b = start("spawn", true);
        assertTrue(b.loadRecoverableEscrow().isEmpty());
        for (TradeEscrow escrow : b.loadEscrow()) b.recoverEscrow(escrow);
        assertEquals(2, a.loadEscrow().size());
        assertTrue(a.loadClaims(snapshot.requester()).isEmpty());
        assertThrows(SQLException.class, () -> b.settleEscrow(snapshot,"a","b","COMPLETED",true));
        assertThrows(SQLException.class, () -> b.replaceEscrow(snapshot));
        a.settleEscrow(snapshot,"a","b","COMPLETED",true);
        assertEquals(1, a.loadClaims(snapshot.requester()).size());
    }

    @Test void duplicateLiveShardIdCannotAcquireOwnership() throws Exception {
        var a = start("factions", true);
        assertThrows(SQLException.class, () -> start("factions", true));
        a.renewOwnership();
        assertTrue(a.ownershipHealthy());
    }

    @Test void ownerRestartRecoversBothSidesOnceAndFencesOldWrites() throws Exception {
        var a = start("factions", true); var snapshot = snapshot(); a.replaceEscrow(snapshot);
        var staleRows = a.loadEscrow();
        a.releaseOwnership();
        var restarted = start("factions", true);
        assertEquals(2, restarted.loadRecoverableEscrow().size());
        for (TradeEscrow row : staleRows) restarted.recoverEscrow(row);
        for (TradeEscrow row : staleRows) restarted.recoverEscrow(row);
        assertEquals(3, restarted.loadClaims(snapshot.requester()).getFirst().getAmount());
        assertEquals(1, restarted.loadClaims(snapshot.requester()).size());
        assertEquals(1, restarted.loadClaims(snapshot.target()).size());
        assertTrue(restarted.loadEscrow().isEmpty());
        assertThrows(SQLException.class, () -> a.replaceEscrow(snapshot));
        assertThrows(SQLException.class, () -> a.settleEscrow(snapshot,"a","b","COMPLETED",true));
        assertThrows(SQLException.class, () -> restarted.replaceEscrow(snapshot));
    }

    @Test void settlementRetryAndStaleSnapshotCannotPayTwiceOrReopen() throws Exception {
        var storage = start("standalone", false); var snapshot = snapshot(); storage.replaceEscrow(snapshot);
        storage.settleEscrow(snapshot,"a","b","COMPLETED",true);
        storage.settleEscrow(snapshot,"a","b","COMPLETED",true);
        assertEquals(1, storage.loadHistory(null,10,0).size());
        assertEquals(1, storage.loadClaims(snapshot.requester()).size());
        assertThrows(SQLException.class, () -> storage.replaceEscrow(snapshot));
        assertThrows(SQLException.class, () -> storage.settleEscrow(snapshot,"a","b","CANCELLED",false));
        assertThrows(SQLException.class, () -> storage.settleEscrow(snapshot(),"a","b","COMPLETED",true));
    }

    @Test void recoveryRereadsSourceRatherThanTrustingCallerItems() throws Exception {
        var a = start("standalone", false); var snapshot = snapshot(); a.replaceEscrow(snapshot);
        a.releaseOwnership(); var restarted = start("standalone", false);
        restarted.recoverEscrow(new TradeEscrow(snapshot.sessionId(), snapshot.requester(),
                new ItemStack[]{new ItemStack(Material.NETHERITE_BLOCK,64)}, 99999,999));
        var actual = restarted.loadClaims(snapshot.requester());
        assertEquals(Material.DIAMOND, actual.getFirst().getType());
        assertEquals(3, actual.getFirst().getAmount());
        assertTrue(restarted.loadPendingPayouts().isEmpty());
    }

    @Test void concurrentRecoveryOfBothSidesEmitsOneRefundPerOwner() throws Exception {
        var a = start("factions", true); var snapshot = snapshot(); a.replaceEscrow(snapshot);
        var rows = a.loadEscrow(); a.releaseOwnership(); var restarted = start("factions", true);
        var jobs = rows.stream().map(row -> CompletableFuture.runAsync(() -> {
            try { restarted.recoverEscrow(row); } catch (Exception error) { throw new RuntimeException(error); }
        })).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(jobs).join();
        assertEquals(1, restarted.loadClaims(snapshot.requester()).size());
        assertEquals(1, restarted.loadClaims(snapshot.target()).size());
    }

    @Test void expiredBootCannotRenewOrWriteAfterReplacement() throws Exception {
        var old = start("factions", true); var snapshot = snapshot(); old.replaceEscrow(snapshot);
        try (var c = database.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE trade_owners SET expires_at=0 WHERE shard_id='factions'");
        }
        var replacement = start("factions", true);
        assertThrows(SQLException.class, old::renewOwnership);
        assertFalse(old.ownershipHealthy());
        assertThrows(SQLException.class, () -> old.replaceEscrow(snapshot));
        replacement.renewOwnership();
        assertTrue(replacement.ownershipHealthy());
    }

    @Test void failedRenewalMarksTheOwnerUnhealthy() throws Exception {
        var storage = start("standalone", false);
        assertTrue(storage.ownershipHealthy());
        try (var lease = database.beginExclusiveMaintenance(1, java.util.concurrent.TimeUnit.SECONDS)) {
            assertThrows(SQLException.class, storage::renewOwnership);
            assertFalse(storage.ownershipHealthy());
        }
        storage.renewOwnership(); // Transient failures before lease expiry can recover.
        assertTrue(storage.ownershipHealthy());
    }

    @Test void expiredLeaseCannotBeRenewedEvenWithoutAReplacement() throws Exception {
        var storage = start("standalone", false);
        try (var c = database.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE trade_owners SET expires_at=0");
        }
        assertThrows(SQLException.class, storage::renewOwnership);
        assertFalse(storage.ownershipHealthy());
    }

    @Test void failedRecoveryRollsBackSourceAndTerminalState() throws Exception {
        var a = start("standalone", false); var snapshot = snapshot(); a.replaceEscrow(snapshot);
        a.releaseOwnership(); var restarted = start("standalone", false);
        var row = restarted.loadRecoverableEscrow().getFirst();
        try (var c = database.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TRIGGER fail_trade_claim BEFORE INSERT ON trade_claims BEGIN SELECT RAISE(ABORT,'injected'); END");
        }
        assertThrows(SQLException.class, () -> restarted.recoverEscrow(row));
        assertEquals(2, restarted.loadRecoverableEscrow().size());
        assertTrue(restarted.loadClaims(snapshot.requester()).isEmpty());
        try (var c = database.getConnection(); var s = c.createStatement()) { s.executeUpdate("DROP TRIGGER fail_trade_claim"); }
        restarted.recoverEscrow(row);
        assertEquals(1, restarted.loadClaims(snapshot.requester()).size());
    }

    private TradeSnapshot insertLegacy(TradeStorage storage) throws Exception {
        var snapshot = snapshot();
        try (var c = database.getConnection(); var s = c.prepareStatement(
                "INSERT INTO trade_escrow(session_id,owner_uuid,items,money,experience) VALUES(?,?,?,0,0)")) {
            s.setString(1,snapshot.sessionId().toString()); s.setString(2,snapshot.requester().toString());
            s.setBytes(3,ItemStack.serializeItemsAsBytes(snapshot.requesterItems())); s.executeUpdate();
        }
        return snapshot;
    }

    @Test void networkPreservesUnownedLegacyEscrowForInspection() throws Exception {
        var a = start("factions", true); var snapshot = insertLegacy(a);
        a.releaseOwnership(); var restarted = start("factions", true);
        assertEquals(1,restarted.unownedEscrowCount());
        assertTrue(restarted.loadRecoverableEscrow().isEmpty());
        restarted.recoverEscrow(restarted.loadEscrow().getFirst());
        assertTrue(restarted.loadClaims(snapshot.requester()).isEmpty());
        assertEquals(1,restarted.loadEscrow().size());
    }

    @Test void singleServerStillRecoversUnownedLegacyEscrow() throws Exception {
        var a = start("standalone", false); var snapshot = insertLegacy(a);
        a.releaseOwnership(); var restarted = start("standalone", false);
        assertEquals(0,restarted.unownedEscrowCount());
        restarted.recoverEscrow(restarted.loadRecoverableEscrow().getFirst());
        assertEquals(3,restarted.loadClaims(snapshot.requester()).getFirst().getAmount());
    }

    @Test void standaloneWillNotAdoptAnotherShardDatabase() throws Exception {
        start("factions", true);
        assertThrows(SQLException.class, () -> start("standalone", false));
    }
}
