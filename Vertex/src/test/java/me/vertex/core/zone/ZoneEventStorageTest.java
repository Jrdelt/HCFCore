package me.vertex.core.zone;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ZoneEventStorageTest {
    @TempDir Path directory;
    Database database;
    ZoneEventStorage a, b;
    AtomicLong clock = new AtomicLong(120_000);
    final ZoneEventStorage.Settings settings = new ZoneEventStorage.Settings(120_000, 10_000, 5_000, 5, 3, 1);
    UUID player = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        database = new Database(new YamlConfiguration(), directory.toFile());
        new ZoneStorage(database).init();
        a = new ZoneEventStorage(database, clock::get);
        b = new ZoneEventStorage(database, clock::get);
        a.refresh(settings);
    }
    @AfterEach void close() { database.close(); }

    @Test void sharedKillsAreAdditiveAndRetriesDoNotCreditTwice() throws Exception {
        UUID operation = UUID.randomUUID();
        assertTrue(a.addScore(operation, 120_000, player, "FirstShard", 1, 120_001));
        assertTrue(b.addScore(UUID.randomUUID(), 120_000, player, "SecondShard", 1.5, 120_002));
        assertFalse(b.addScore(operation, 120_000, player, "FirstShard", 1, 120_001));
        assertEquals(2.5, a.refresh(settings).scores().getFirst().score());
        assertEquals(a.refresh(settings).scores(), b.refresh(settings).scores());
    }

    @Test void operationReuseWithDifferentPlayerIsRejected() throws Exception {
        UUID operation = UUID.randomUUID();
        a.addScore(operation, 120_000, player, "Player", 1, 120_001);
        assertThrows(SQLException.class, () -> b.addScore(operation, 120_000, UUID.randomUUID(), "Other", 1, 120_001));
        assertEquals(1, a.refresh(settings).scores().size());
    }

    @Test void emptyShardCannotReplaceGlobalWinnersAndStalePlayerSavesCannotRevokeBoost() throws Exception {
        a.addScore(UUID.randomUUID(), 120_000, player, "Winner", 10, 120_001);
        clock.set(135_000);
        var first = b.refresh(settings);
        assertEquals(1, first.winners().size());
        assertEquals(5, first.winners().getFirst().boost());
        new ZoneStorage(database).upsertPlayer(new ZoneStorage.PlayerRow(player, "Winner", 1, 0, 0, 0, null, 0, 0));
        assertEquals(first.winners(), a.refresh(settings).winners());
        assertEquals(1, count("zone_event_winners"));
    }

    @Test void restartKeepsScoresFinalizedWinnersAndOperationReceipts() throws Exception {
        UUID operation = UUID.randomUUID();
        a.addScore(operation, 120_000, player, "Player", 3, 120_001);
        database.close();
        database = new Database(new YamlConfiguration(), directory.toFile());
        new ZoneStorage(database).init();
        a = new ZoneEventStorage(database, clock::get);
        assertEquals(3, a.refresh(settings).scores().getFirst().score());
        clock.set(135_000);
        assertEquals(player, a.refresh(settings).winners().getFirst().uuid());
        assertFalse(a.addScore(operation, 120_000, player, "Player", 3, 120_001));
    }

    @Test void inFlightScoreIsAcceptedOnlyWithinSettlementWindow() throws Exception {
        clock.set(134_999);
        a.addScore(UUID.randomUUID(), 120_000, player, "Player", 1, 129_999);
        assertEquals(Long.MIN_VALUE, b.refresh(settings).winnerCycle());
        clock.set(135_000);
        assertThrows(ZoneEventStorage.ClosedEventException.class,
                () -> a.addScore(UUID.randomUUID(), 120_000, player, "Player", 1, 129_999));
        assertEquals(1, b.refresh(settings).winners().size());
        assertThrows(ZoneEventStorage.ClosedEventException.class,
                () -> a.addScore(UUID.randomUUID(), 120_000, player, "Player", 1, 130_000));
    }

    @Test void scoreAndReceiptRollbackTogetherThenRetryWorks() throws Exception {
        execute("CREATE TRIGGER fail_receipt BEFORE INSERT ON zone_event_score_ops BEGIN SELECT RAISE(ABORT,'test'); END");
        UUID operation = UUID.randomUUID();
        assertThrows(SQLException.class, () -> a.addScore(operation, 120_000, player, "Player", 1, 120_001));
        assertTrue(a.refresh(settings).scores().isEmpty());
        assertEquals(0, count("zone_event_score_ops"));
        execute("DROP TRIGGER fail_receipt");
        assertTrue(a.addScore(operation, 120_000, player, "Player", 1, 120_001));
    }

    @Test void finalizationFailureDoesNotPublishPartialRewards() throws Exception {
        a.addScore(UUID.randomUUID(), 120_000, player, "Player", 1, 120_001);
        execute("CREATE TRIGGER fail_finish BEFORE UPDATE OF finalized_at ON zone_event_runs BEGIN SELECT RAISE(ABORT,'test'); END");
        clock.set(135_000);
        assertThrows(SQLException.class, () -> b.refresh(settings));
        assertEquals(0, count("zone_event_winners"));
        execute("DROP TRIGGER fail_finish");
        assertEquals(1, a.refresh(settings).winners().size());
    }

    @Test void simultaneousShardFinalizersCommitOneSetOfRewards() throws Exception {
        a.addScore(UUID.randomUUID(), 120_000, player, "Player", 1, 120_001);
        clock.set(135_000);
        List<CompletableFuture<Void>> futures = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try { (i % 2 == 0 ? a : b).refresh(settings); }
                    catch (SQLException e) { throw new RuntimeException(e); }
                })).toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        assertEquals(1, count("zone_event_winners"));
    }

    @Test void tiesUseReachedTimeThenUuidAndKeepOnlyThreeWinners() throws Exception {
        UUID early = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID tied = UUID.fromString("00000000-0000-0000-0000-000000000003");
        a.addScore(UUID.randomUUID(), 120_000, tied, "Tied", 2, 120_002);
        a.addScore(UUID.randomUUID(), 120_000, early, "Early", 2, 120_002);
        a.addScore(UUID.randomUUID(), 120_000, player, "Latest", 2, 120_003);
        a.addScore(UUID.randomUUID(), 120_000, UUID.randomUUID(), "Fourth", 1, 120_004);
        clock.set(135_000);
        var winners = b.refresh(settings).winners();
        assertEquals(List.of(early, tied, player), winners.stream().map(ZoneEventStorage.Winner::uuid).toList());
    }

    @Test void adminStartStopAreSharedAndStopKeepsScores() throws Exception {
        clock.set(121_000);
        a.start(clock.get(), settings);
        assertEquals(121_000, b.refresh(settings).start());
        b.addScore(UUID.randomUUID(), 121_000, player, "Player", 1, 121_001);
        clock.set(122_000);
        a.stop(121_000, clock.get());
        assertEquals(122_000, b.refresh(settings).end());
        clock.set(127_000);
        assertEquals(player, b.refresh(settings).winners().getFirst().uuid());
        a.start(121_000, settings); // Retry must not resurrect the stopped event.
        assertEquals(122_000, b.refresh(settings).end());
    }

    @Test void emptyNewCycleClearsPriorRewardWithoutDeletingHistory() throws Exception {
        a.addScore(UUID.randomUUID(), 120_000, player, "Player", 1, 120_001);
        clock.set(135_000);
        assertEquals(1, a.refresh(settings).winners().size());
        clock.set(255_000);
        assertTrue(b.refresh(settings).winners().isEmpty());
        assertEquals(240_000, b.refresh(settings).winnerCycle());
        assertEquals(1, count("zone_event_winners"));
    }

    @Test void nonFiniteAndNonPositiveScoresAreRejectedWithoutReceipt() {
        for (double points : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, -1})
            assertThrows(IllegalArgumentException.class, () -> a.addScore(UUID.randomUUID(), 120_000, player, "Player", points, 120_001));
    }

    @Test void firstKillOfNewCycleDoesNotNeedToWaitForScheduledRefresh() throws Exception {
        clock.set(240_001);
        assertTrue(b.addScore(UUID.randomUUID(), 240_000, player, "Player", 1.5, 240_001, settings));
        assertEquals(1.5, a.refresh(settings).scores().getFirst().score());
    }

    private int count(String table) throws SQLException {
        try (var c = database.getConnection(); var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { r.next(); return r.getInt(1); }
    }
    private void execute(String sql) throws SQLException {
        try (var c = database.getConnection(); var s = c.createStatement()) { s.executeUpdate(sql); }
    }
}
