package me.vertex.core.gc;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link GcManager} against a real local (SQLite) database, the
 * same way {@code CoinflipManagerTest}/{@code FactionBankManagerTest} cover
 * their own currency ledgers -- a silently-wrong balance mutation here means
 * an actually lost or duplicated GC balance.
 */
class GcManagerTest {

    @TempDir
    Path dataFolder;

    private PluginMock plugin;
    private Database database;
    private GcStorage storage;
    private GcManager manager;
    private final UUID player = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new GcStorage(database);
        storage.init();
        manager = new GcManager(plugin, storage);
        manager.load();
        manager.loadState();
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.awaitWrites();
        }
        if (database != null) {
            database.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void newPlayerStartsAtZero() {
        assertEquals(0L, manager.balance(player));
        assertFalse(manager.has(player, 1L));
    }

    @Test
    void creditIncreasesBalanceImmediately() {
        manager.credit(player, player, GcAction.DEPOSIT, 500L, null);
        assertEquals(500L, manager.balance(player));
    }

    @Test
    void creditIsANoOpForANonPositiveAmount() {
        assertEquals(0L, manager.balance(player));
        manager.credit(player, player, GcAction.DEPOSIT, 0L, null);
        manager.credit(player, player, GcAction.DEPOSIT, -5L, null);
        assertEquals(0L, manager.balance(player));
    }

    @Test
    void tryDebitSucceedsWithSufficientBalance() {
        manager.credit(player, player, GcAction.DEPOSIT, 100L, null);
        assertTrue(manager.tryDebit(player, player, GcAction.WITHDRAW, 60L, null));
        assertEquals(40L, manager.balance(player));
    }

    @Test
    void tryDebitFailsAndLeavesTheBalanceAloneWhenInsufficient() {
        manager.credit(player, player, GcAction.DEPOSIT, 10L, null);
        assertFalse(manager.tryDebit(player, player, GcAction.WITHDRAW, 11L, null));
        assertEquals(10L, manager.balance(player), "a refused debit must not partially apply");
    }

    @Test
    void tryDebitRejectsNonPositiveAmounts() {
        manager.credit(player, player, GcAction.DEPOSIT, 100L, null);
        assertFalse(manager.tryDebit(player, player, GcAction.WITHDRAW, 0L, null));
        assertFalse(manager.tryDebit(player, player, GcAction.WITHDRAW, -5L, null));
        assertEquals(100L, manager.balance(player));
    }

    @Test
    void setBalanceOverwritesAbsoluteRegardlessOfPriorValue() {
        manager.credit(player, player, GcAction.DEPOSIT, 999L, null);
        manager.setBalance(player, staff, GcAction.STAFF_SET, 42L, "admin set");
        assertEquals(42L, manager.balance(player));
    }

    @Test
    void setBalanceClampsANegativeTargetToZero() {
        manager.setBalance(player, staff, GcAction.STAFF_SET, -10L, null);
        assertEquals(0L, manager.balance(player));
    }

    @Test
    void staffZeroIsExpressedAsSetToZero() {
        manager.credit(player, player, GcAction.DEPOSIT, 250L, null);
        manager.setBalance(player, staff, GcAction.STAFF_ZERO, 0L, null);
        assertEquals(0L, manager.balance(player));
    }

    @Test
    void balanceSurvivesAFreshManagerLoadingFromTheSameDatabase() {
        manager.credit(player, player, GcAction.DEPOSIT, 777L, null);
        manager.awaitWrites();

        GcManager reloaded = new GcManager(plugin, storage);
        reloaded.load();
        reloaded.loadState();
        assertEquals(777L, reloaded.balance(player));
    }

    @Test
    void debitAndCreditRoundTripSurvivesReload() {
        manager.credit(player, player, GcAction.DEPOSIT, 1000L, null);
        manager.tryDebit(player, player, GcAction.WITHDRAW, 300L, null);
        manager.awaitWrites();

        GcManager reloaded = new GcManager(plugin, storage);
        reloaded.load();
        reloaded.loadState();
        assertEquals(700L, reloaded.balance(player));
    }

    /**
     * The guarantee GC's ledger is built on: a debit that can never be made
     * durable must not silently keep its in-memory effect either, or the
     * cache and the database (the actual source of truth after a restart)
     * would permanently disagree.
     */
    @Test
    void aDebitThatCannotBePersistedReversesInMemory() {
        manager.credit(player, player, GcAction.DEPOSIT, 500L, null);
        manager.awaitWrites();
        database.close();

        assertTrue(manager.tryDebit(player, player, GcAction.WITHDRAW, 200L, null),
                "the debit is accepted optimistically against the in-memory cache");
        manager.awaitWrites();
        assertEquals(500L, manager.balance(player), "an unpersisted debit must be reversed, not silently kept");
    }

    @Test
    void aPersistFailureCallbackFiresSoAnExternalCompensationCanRun() {
        manager.credit(player, player, GcAction.DEPOSIT, 500L, null);
        manager.awaitWrites();
        database.close();

        java.util.concurrent.atomic.AtomicBoolean compensated = new java.util.concurrent.atomic.AtomicBoolean(false);
        manager.tryDebit(player, player, GcAction.WITHDRAW, 100L, null, () -> compensated.set(true));
        manager.awaitWrites();
        org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock scheduler =
                (org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock) org.bukkit.Bukkit.getScheduler();
        scheduler.performTicks(1L);
        assertTrue(compensated.get(), "the caller must be told to undo its own external side effect");
    }

    @Test
    void aDurablePayoutOperationKeyCannotCreditTwice() throws Exception {
        assertTrue(manager.creditDurably(player, null, GcAction.COINFLIP_PAYOUT, 125L,
                "coinflip:test:payout").get(5, TimeUnit.SECONDS));
        assertTrue(manager.creditDurably(player, null, GcAction.COINFLIP_PAYOUT, 125L,
                "coinflip:test:payout").get(5, TimeUnit.SECONDS));
        assertEquals(125L, manager.balance(player));
    }

    // ---- Redeem codes ----

    @Test
    void creatingAndRedeemingACodeCreditsTheRedeemer() throws Exception {
        GcManager.CreateCodeOutcome created = manager.createRedeemCode(staff, 250L, 1, null).get(5, TimeUnit.SECONDS);
        assertTrue(created.success());
        assertNotNull(created.code());

        GcManager.RedeemOutcome outcome = manager.redeem(player, created.code()).get(5, TimeUnit.SECONDS);
        assertEquals(GcManager.RedeemResult.OK, outcome.result());
        assertEquals(250L, outcome.amount());
        assertEquals(250L, manager.balance(player));
    }

    @Test
    void aSingleUseCodeCannotBeRedeemedTwice() throws Exception {
        GcManager.CreateCodeOutcome created = manager.createRedeemCode(staff, 100L, 1, null).get(5, TimeUnit.SECONDS);

        assertEquals(GcManager.RedeemResult.OK, manager.redeem(player, created.code()).get(5, TimeUnit.SECONDS).result());
        UUID secondPlayer = UUID.randomUUID();
        GcManager.RedeemOutcome second = manager.redeem(secondPlayer, created.code()).get(5, TimeUnit.SECONDS);
        assertEquals(GcManager.RedeemResult.EXHAUSTED, second.result());
        assertEquals(0L, manager.balance(secondPlayer));
        assertEquals(100L, manager.balance(player));
    }

    @Test
    void aMultiUseCodeCanBeRedeemedUpToItsLimit() throws Exception {
        GcManager.CreateCodeOutcome created = manager.createRedeemCode(staff, 50L, 2, null).get(5, TimeUnit.SECONDS);
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertEquals(GcManager.RedeemResult.OK, manager.redeem(player, created.code()).get(5, TimeUnit.SECONDS).result());
        assertEquals(GcManager.RedeemResult.OK, manager.redeem(second, created.code()).get(5, TimeUnit.SECONDS).result());
        assertEquals(GcManager.RedeemResult.EXHAUSTED, manager.redeem(third, created.code()).get(5, TimeUnit.SECONDS).result());
    }

    @Test
    void redeemingAnUnknownCodeReportsNotFound() throws Exception {
        GcManager.RedeemOutcome outcome = manager.redeem(player, "NOSUCHCODE12").get(5, TimeUnit.SECONDS);
        assertEquals(GcManager.RedeemResult.NOT_FOUND, outcome.result());
        assertEquals(0L, manager.balance(player));
    }

    @Test
    void redeemingAnExpiredCodeReportsExpired() throws Exception {
        GcManager.CreateCodeOutcome created = manager.createRedeemCode(staff, 10L, 1, System.currentTimeMillis() - 1000L)
                .get(5, TimeUnit.SECONDS);
        GcManager.RedeemOutcome outcome = manager.redeem(player, created.code()).get(5, TimeUnit.SECONDS);
        assertEquals(GcManager.RedeemResult.EXPIRED, outcome.result());
        assertEquals(0L, manager.balance(player));
    }

    @Test
    void redeemCodeLookupIsCaseInsensitive() throws Exception {
        GcManager.CreateCodeOutcome created = manager.createRedeemCode(staff, 30L, 1, null).get(5, TimeUnit.SECONDS);
        GcManager.RedeemOutcome outcome = manager.redeem(player, created.code().toLowerCase(java.util.Locale.ROOT))
                .get(5, TimeUnit.SECONDS);
        assertEquals(GcManager.RedeemResult.OK, outcome.result());
    }

    @Test
    void withdrawToCodeDebitsCreatesAnAuditedSingleUseCodeAndSurvivesReload() throws Exception {
        manager.credit(player, player, GcAction.DEPOSIT, 500L, null);
        manager.awaitWrites();

        GcManager.WithdrawCodeOutcome withdrawn = manager.withdrawToCode(player, 200L).get(5, TimeUnit.SECONDS);
        assertEquals(GcManager.WithdrawCodeResult.OK, withdrawn.result());
        assertNotNull(withdrawn.code());
        assertEquals(300L, manager.balance(player));
        assertEquals(300L, withdrawn.balanceAfter());

        List<GcLogEntry> logs = manager.loadLog(player, 10, 0);
        assertTrue(logs.stream().anyMatch(entry -> entry.action() == GcAction.WITHDRAW_CODE
                && entry.amount() == 200L && withdrawn.code().equals(entry.note())));

        UUID redeemer = UUID.randomUUID();
        assertEquals(GcManager.RedeemResult.OK, manager.redeem(redeemer, withdrawn.code())
                .get(5, TimeUnit.SECONDS).result());
        assertEquals(GcManager.RedeemResult.EXHAUSTED, manager.redeem(UUID.randomUUID(), withdrawn.code())
                .get(5, TimeUnit.SECONDS).result());

        GcManager reloaded = new GcManager(plugin, storage);
        reloaded.load();
        reloaded.loadState();
        assertEquals(300L, reloaded.balance(player));
        assertEquals(200L, reloaded.balance(redeemer));
    }

    @Test
    void concurrentWithdrawToCodeRequestsCannotOverdrawTheBalance() throws Exception {
        manager.credit(player, player, GcAction.DEPOSIT, 100L, null);
        manager.awaitWrites();

        var first = manager.withdrawToCode(player, 75L);
        var second = manager.withdrawToCode(player, 75L);
        GcManager.WithdrawCodeOutcome a = first.get(5, TimeUnit.SECONDS);
        GcManager.WithdrawCodeOutcome b = second.get(5, TimeUnit.SECONDS);

        assertEquals(1L, java.util.stream.Stream.of(a, b)
                .filter(outcome -> outcome.result() == GcManager.WithdrawCodeResult.OK).count());
        assertEquals(25L, manager.balance(player));
    }

    // ---- Audit log ----

    @Test
    void loadLogFiltersToOnePlayersOwnHistory() {
        manager.credit(player, player, GcAction.DEPOSIT, 100L, null);
        UUID other = UUID.randomUUID();
        manager.credit(other, other, GcAction.DEPOSIT, 200L, null);
        manager.awaitWrites();

        List<GcLogEntry> ownLog = manager.loadLog(player, 10, 0);
        assertEquals(1, ownLog.size());
        assertEquals(player, ownLog.get(0).targetUuid());
        assertEquals(GcAction.DEPOSIT, ownLog.get(0).action());
        assertEquals(100L, ownLog.get(0).amount());
        assertEquals(100L, ownLog.get(0).balanceAfter());
    }

    @Test
    void loadLogWithNoFilterSeesEveryPlayer() {
        manager.credit(player, player, GcAction.DEPOSIT, 100L, null);
        UUID other = UUID.randomUUID();
        manager.credit(other, other, GcAction.DEPOSIT, 200L, null);
        manager.awaitWrites();

        assertEquals(2, manager.loadLog(null, 10, 0).size());
    }

    @Test
    void logIsNewestFirst() {
        manager.credit(player, player, GcAction.DEPOSIT, 10L, null);
        manager.awaitWrites();
        manager.credit(player, player, GcAction.DEPOSIT, 20L, null);
        manager.awaitWrites();

        List<GcLogEntry> log = manager.loadLog(player, 10, 0);
        assertEquals(2, log.size());
        assertEquals(30L, log.get(0).balanceAfter(), "the most recent mutation's resulting balance should be first");
        assertEquals(10L, log.get(1).balanceAfter());
    }
}
