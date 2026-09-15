package me.vertex.core.faction;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the native Vertex TNT balance against a real
 * local (SQLite) database -- the whole point of the move was that TNT must
 * survive a restart, so the round-trip assertions matter more than the
 * in-memory ones.
 */
class FactionBankManagerTest {

    private static final int FACTION = 7;

    @TempDir
    Path dataFolder;

    private PluginMock plugin;
    private Database database;
    private FactionBankStorage storage;
    private FactionBankManager manager;

    @Test void withdrawalAndInboxEntitlementCommitTogetherAndCannotRepeat() throws Exception {
        var inbox=new me.vertex.core.storage.DeliveryStorage(database);inbox.init();
        var owner=java.util.UUID.randomUUID();
        var items=me.vertex.core.storage.DeliveryStorage.prepare(java.util.List.of(new org.bukkit.inventory.ItemStack(org.bukkit.Material.TNT,10)));
        assertTrue(manager.depositTnt(FACTION,100,1000).get());
        java.util.function.Function<FactionBankStorage.StoredBank,FactionBankStorage.StoredBank> debit=b->
                new FactionBankStorage.StoredBank(b.factionId(),b.money(),b.experience(),b.tnt()-10);
        FactionBankStorage.TransactionEffect payout=c->me.vertex.core.storage.DeliveryStorage.enqueueNew(c,owner,items,"test-bank");
        assertTrue(storage.mutate(FACTION,debit,payout).isPresent());
        org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,()->storage.mutate(FACTION,debit,payout));
        assertEquals(90,storage.loadAll().getFirst().tnt());
        assertEquals(10,inbox.reserve(owner).rows().getFirst().item().getAmount());
    }

    @Test void failedInboxWriteRollsBackBankDebit() throws Exception {
        assertTrue(manager.depositTnt(FACTION,100,1000).get());
        org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,()->storage.mutate(FACTION,
                b->new FactionBankStorage.StoredBank(b.factionId(),b.money(),b.experience(),0),
                c->{throw new java.sql.SQLException("injected delivery failure");}));
        assertEquals(100,storage.loadAll().getFirst().tnt());
    }

    @Test void invalidationFailureMustNotReportCommittedBankWriteAsFailed() throws Exception {
        manager.setMutationPublisher(()->{throw new IllegalStateException("injected publish failure");});
        assertTrue(manager.depositTnt(FACTION,100,1000).get());
        assertEquals(100,storage.loadAll().getFirst().tnt());
    }

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new FactionBankStorage(database);
        storage.init();
        manager = new FactionBankManager(plugin, storage);
        manager.load();
    }

    @AfterEach
    void tearDown() {
        manager.awaitWrites();
        database.close();
        MockBukkit.unmock();
    }

    @Test
    void depositsAndWithdrawsTnt() throws Exception {
        assertTrue(manager.depositTnt(FACTION, 500L, 1_000_000L).get());
        assertEquals(500L, manager.tnt(FACTION));

        assertTrue(manager.withdrawTnt(FACTION, 200L).get());
        assertEquals(300L, manager.tnt(FACTION));
    }

    /**
     * ISS-12: a resolved deposit's journal entry must not linger -- otherwise
     * every ordinary deposit would accumulate a permanent, never-cleared
     * "unresolved" report at the next restart.
     */
    @Test
    void resolvedDepositJournalEntryLeavesNothingBehind() throws Exception {
        var owner = java.util.UUID.randomUUID();
        String operationId = manager.journalDepositIntent(owner, FACTION, 50L);
        assertFalse(new TntDepositWal(plugin.getDataFolder()).load().isEmpty(),
                "the entry must be durable before the caller removes any source item");

        manager.clearDepositIntent(operationId);
        assertTrue(new TntDepositWal(plugin.getDataFolder()).load().isEmpty());
    }

    /**
     * ISS-12: a deposit journaled before the last shutdown, but never
     * resolved (the crash landed before the credit or a refund could be
     * confirmed), must be reported for staff reconciliation on the next
     * load -- and must NOT be auto-credited, since the balance carries no
     * per-operation receipt to tell an already-applied credit apart from
     * one that never happened.
     */
    @Test
    void survivedDepositJournalEntryIsReportedNotAutoCreditedOnReload() throws Exception {
        var owner = java.util.UUID.randomUUID();
        new TntDepositWal(plugin.getDataFolder()).put(new TntDepositWal.Entry(
                java.util.UUID.randomUUID().toString(), owner, FACTION, 75L));

        FactionBankManager restarted = new FactionBankManager(plugin, storage);
        restarted.load();

        assertEquals(0L, restarted.tnt(FACTION), "a survived entry must never be auto-credited");
        assertTrue(new TntDepositWal(plugin.getDataFolder()).load().isEmpty(),
                "the entry must be cleared once reported, so it is not reported again on every future restart");
    }

    @Test
    void refusesDepositBeyondCapacity() throws Exception {
        assertTrue(manager.depositTnt(FACTION, 900L, 1_000L).get());
        assertFalse(manager.depositTnt(FACTION, 200L, 1_000L).get());
        assertEquals(900L, manager.tnt(FACTION), "a refused deposit must not partially apply");
    }

    @Test
    void adminAdjustmentsRespectTheTntCapacityInsideTheSerializedWrite() throws Exception {
        assertFalse(manager.adjustForAdmin(FACTION, FactionBankManager.BankType.TNT,
                FactionBankManager.AdminOperation.SET, 0D, 1_001L, 1_000L).get());
        assertTrue(manager.adjustForAdmin(FACTION, FactionBankManager.BankType.TNT,
                FactionBankManager.AdminOperation.SET, 0D, 900L, 1_000L).get());
        var firstAdd = manager.adjustForAdmin(FACTION, FactionBankManager.BankType.TNT,
                FactionBankManager.AdminOperation.ADD, 0D, 75L, 1_000L);
        var secondAdd = manager.adjustForAdmin(FACTION, FactionBankManager.BankType.TNT,
                FactionBankManager.AdminOperation.ADD, 0D, 75L, 1_000L);
        assertTrue(firstAdd.get());
        assertFalse(secondAdd.get(), "the queued admin add must see the committed first add");
        assertEquals(975L, manager.tnt(FACTION), "concurrent admin adds must not exceed the cap");

        assertTrue(manager.adjustForAdmin(FACTION, FactionBankManager.BankType.TNT,
                FactionBankManager.AdminOperation.TAKE, 0D, 400L, 1_000L).get());
        assertEquals(575L, manager.tnt(FACTION));
    }

    @Test
    void refusesWithdrawalBeyondBalance() throws Exception {
        assertTrue(manager.depositTnt(FACTION, 100L, 1_000L).get());
        assertFalse(manager.withdrawTnt(FACTION, 101L).get());
        assertEquals(100L, manager.tnt(FACTION));
    }

    @Test
    void rejectsNonPositiveAmounts() throws Exception {
        assertFalse(manager.depositTnt(FACTION, 0L, 1_000L).get());
        assertFalse(manager.depositTnt(FACTION, -5L, 1_000L).get());
        assertFalse(manager.withdrawTnt(FACTION, 0L).get());
        assertEquals(0L, manager.tnt(FACTION));
    }

    /** A capacity near Long.MAX_VALUE must not wrap the headroom check negative. */
    @Test
    void handlesEffectivelyUnlimitedCapacityWithoutOverflowing() throws Exception {
        assertTrue(manager.depositTnt(FACTION, 1_000L, Long.MAX_VALUE).get());
        assertEquals(1_000L, manager.tnt(FACTION));
    }

    @Test
    void tntSurvivesAFreshManagerLoadingFromTheSameDatabase() throws Exception {
        assertTrue(manager.depositTnt(FACTION, 4_242L, 1_000_000L).get());
        manager.awaitWrites();

        FactionBankManager reloaded = new FactionBankManager(plugin, storage);
        reloaded.load();
        assertEquals(4_242L, reloaded.tnt(FACTION));
    }

    @Test
    void tntIsIndependentOfMoneyAndExperience() throws Exception {
        assertTrue(manager.depositMoney(FACTION, 250.0).get());
        assertTrue(manager.depositExperience(FACTION, 60L).get());
        assertTrue(manager.depositTnt(FACTION, 12L, 1_000L).get());
        manager.awaitWrites();

        FactionBankManager reloaded = new FactionBankManager(plugin, storage);
        reloaded.load();
        assertEquals(250.0, reloaded.money(FACTION), 0.0001);
        assertEquals(60L, reloaded.experience(FACTION));
        assertEquals(12L, reloaded.tnt(FACTION));
    }

    /**
     * The guarantee the TNT-bank migration is built on: a deposit reports
     * true only once it is durable, so callers never report a deposit that
     * was not actually written to the native faction bank.
     */
    @Test
    void aDepositThatCannotBePersistedReportsFailure() throws Exception {
        database.close();

        assertFalse(manager.depositTnt(FACTION, 500L, 1_000_000L).get(),
                "an unpersisted deposit must not claim success");
    }

    @Test
    void aFailedDepositLeavesTheBalanceAlone() throws Exception {
        assertTrue(manager.depositTnt(FACTION, 100L, 1_000_000L).get());
        database.close();

        assertFalse(manager.depositTnt(FACTION, 900L, 1_000_000L).get());
        assertEquals(100L, manager.tnt(FACTION), "the in-memory value must not move without a durable write");
    }

}
