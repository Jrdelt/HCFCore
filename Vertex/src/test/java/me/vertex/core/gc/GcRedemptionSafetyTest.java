package me.vertex.core.gc;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static me.vertex.core.gc.GcManager.RedeemResult.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated SQLite ledger tests; no live wallet data or wall-clock sleeps. */
class GcRedemptionSafetyTest {
    @TempDir Path dataFolder;
    private PluginMock plugin;
    private Database database;
    private GcStorage storage;
    private GcManager manager;
    private final AtomicLong clock = new AtomicLong(10_000L);
    private final UUID player = UUID.randomUUID();
    private final UUID staff = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new GcStorage(database);
        storage.init();
        manager = newManager();
    }

    private GcManager newManager() {
        GcManager result = new GcManager(plugin, storage, clock::get);
        result.load();
        result.loadState();
        return result;
    }

    @AfterEach
    void tearDown() {
        if (manager != null) manager.awaitWrites();
        if (database != null) database.close();
        MockBukkit.unmock();
    }

    private String code(long amount, int uses) throws Exception {
        var created = manager.createRedeemCode(staff, amount, uses, null).get(5, TimeUnit.SECONDS);
        assertTrue(created.success());
        return created.code();
    }

    private GcManager.RedeemOutcome claim(UUID uuid, String code) throws Exception {
        return manager.redeem(uuid, code).get(5, TimeUnit.SECONDS);
    }

    private void configure(String key, Object value) throws Exception {
        File file = new File(plugin.getDataFolder(), "gc.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        config.set(key, value);
        config.save(file);
        manager.load();
    }

    @Test
    void invalidAttemptsAreLimitedWithoutExtendingTheOriginalDeadline() throws Exception {
        assertEquals(NOT_FOUND, claim(player, "UNKNOWN").result());
        assertEquals(3000L, claim(player, "ANOTHER").retryAfterMillis());
        clock.addAndGet(2000L);
        var blocked = claim(player, "UNKNOWN");
        assertEquals(COOLDOWN, blocked.result());
        assertEquals(1000L, blocked.retryAfterMillis());
        clock.addAndGet(1000L);
        assertEquals(NOT_FOUND, claim(player, "UNKNOWN").result());
        assertTrue(storage.loadLog(player, 10, 0).isEmpty());
    }

    @Test
    void successfulClaimsAlsoStartTheCooldownAndDoNotConsumeTheNextCode() throws Exception {
        String first = code(10L, 1);
        String second = code(20L, 1);
        assertEquals(OK, claim(player, first).result());
        assertEquals(COOLDOWN, claim(player, second).result());
        assertEquals(10L, manager.balance(player));
        clock.addAndGet(3000L);
        assertEquals(OK, claim(player, second).result());
        assertEquals(30L, storage.loadAllBalances().get(player));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "ABCDEFGHIJKLMNOPQRSTUVWXYZ123456789"})
    void malformedAttemptsStillStartTheCooldown(String raw) throws Exception {
        assertEquals(NOT_FOUND, claim(player, raw).result());
        assertEquals(COOLDOWN, claim(player, "UNKNOWN").result());
        assertEquals(0L, manager.balance(player));
    }

    @Test
    void inFlightGuardRejectsSimultaneousSamePlayerClaimsEvenWithCooldownDisabled() throws Exception {
        configure("redeem-cooldown-seconds", 0);
        String first = code(10L, 10);
        List<CompletableFuture<GcManager.RedeemOutcome>> requests = new ArrayList<>();
        try (var held = database.getConnection()) {
            List<CompletableFuture<CompletableFuture<GcManager.RedeemOutcome>>> admissions = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                admissions.add(CompletableFuture.supplyAsync(() -> manager.redeem(player, first)));
            }
            CompletableFuture.allOf(admissions.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            admissions.forEach(admission -> requests.add(admission.join()));
            assertEquals(23L, requests.stream().filter(CompletableFuture::isDone).count());
            clock.addAndGet(120_000L);
            assertEquals(IN_PROGRESS, claim(player, first).result(), "database wait must not expire the guard");
        }
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        assertEquals(1L, requests.stream().filter(request -> request.join().result() == OK).count());
        assertEquals(23L, requests.stream().filter(request -> request.join().result() == IN_PROGRESS).count());
        assertEquals(10L, storage.loadAllBalances().get(player));
        assertEquals(OK, claim(player, first).result(), "completion must release the in-flight guard");
    }

    @Test
    void simultaneousPlayersAndIndependentManagersCannotRedeemOneUseTwice() throws Exception {
        assertConcurrentCodeUses(1);
    }

    @Test
    void simultaneousPlayersCannotExceedAMultiUseCodesLimit() throws Exception {
        assertConcurrentCodeUses(3);
    }

    private void assertConcurrentCodeUses(int uses) throws Exception {
        String code = code(17L, uses);
        GcManager secondManager = newManager();
        List<CompletableFuture<GcManager.RedeemOutcome>> requests = new ArrayList<>();
        try {
            try (var held = database.getConnection()) {
                for (int index = 0; index < 32; index++) {
                    GcManager target = index % 2 == 0 ? manager : secondManager;
                    requests.add(target.redeem(UUID.randomUUID(), index % 2 == 0
                            ? code : " " + code.toLowerCase(java.util.Locale.ROOT) + " "));
                }
            }
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            assertEquals(uses, requests.stream().filter(request -> request.join().result() == OK).count());
            assertEquals(32 - uses, requests.stream().filter(request -> request.join().result() == EXHAUSTED).count());
            assertEquals(17L * uses, storage.loadAllBalances().values().stream().mapToLong(Long::longValue).sum());
            assertEquals(uses, storage.loadLog(null, 100, 0).stream().filter(log -> log.action() == GcAction.REDEEM).count());
            assertEquals(EXHAUSTED, newManager().redeem(UUID.randomUUID(), code).get(5, TimeUnit.SECONDS).result());
        } finally {
            secondManager.awaitWrites();
        }
    }

    @Test
    void ledgerFailureRollsBackBothTheCodeUseAndCreditAndAllowsRetry() throws Exception {
        String code = code(25L, 1);
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER fail_redeem_log BEFORE INSERT ON gc_log "
                    + "WHEN NEW.action = 'REDEEM' BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END");
        }
        assertEquals(FAILED, claim(player, code).result());
        assertEquals(0L, manager.balance(player));
        assertFalse(storage.loadAllBalances().containsKey(player));
        assertTrue(storage.loadLog(player, 10, 0).isEmpty());
        assertEquals(COOLDOWN, claim(player, code).result(), "failure releases the in-flight guard but not the cooldown");
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TRIGGER fail_redeem_log");
        }
        clock.addAndGet(3000L);
        assertEquals(OK, claim(player, code).result(), "failed transaction must leave the code usable");
        assertEquals(25L, storage.loadAllBalances().get(player));
    }

    @Test
    void storageUnavailabilityDoesNotPermanentlyLockClaims() throws Exception {
        String code = code(15L, 1);
        try (var lease = database.beginExclusiveMaintenance(5, TimeUnit.SECONDS)) {
            assertEquals(FAILED, claim(player, code).result());
        }
        clock.addAndGet(3000L);
        assertEquals(OK, claim(player, code).result());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "61", "1.5", "true", "not-a-number"})
    void invalidCooldownValuesFallBackToThreeSeconds(String value) throws Exception {
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString("value: " + value);
        configure("redeem-cooldown-seconds", parsed.get("value"));
        assertEquals(NOT_FOUND, claim(player, "UNKNOWN").result());
        assertEquals(3000L, claim(player, "UNKNOWN").retryAfterMillis());
    }

    @Test
    void reloadAppliesToNewAttemptsWithoutClearingAnExistingCooldown() throws Exception {
        assertEquals(NOT_FOUND, claim(player, "UNKNOWN").result());
        configure("redeem-cooldown-seconds", 1);
        assertEquals(3000L, claim(player, "UNKNOWN").retryAfterMillis());
        clock.addAndGet(3000L);
        assertEquals(NOT_FOUND, claim(player, "UNKNOWN").result());
        assertEquals(1000L, claim(player, "UNKNOWN").retryAfterMillis());
    }

    @Test
    void codeFormatReloadDoesNotInvalidateAlreadyIssuedCodes() throws Exception {
        String code = code(15L, 1);
        configure("redeem-code-length", 4);
        configure("redeem-code-charset", "1234");
        assertEquals(OK, claim(player, code).result());
        assertEquals(15L, manager.balance(player));
    }

    @Test
    void withdrawalReservesFundsBeforeAnotherDebitCanBeAdmitted() throws Exception {
        assertTrue(manager.setBalance(player, staff, GcAction.STAFF_SET, 100, "seed"));
        CompletableFuture<GcManager.WithdrawCodeOutcome> withdrawal;
        try (var held = database.getConnection()) {
            withdrawal = manager.withdrawToCode(player, 100);
            assertEquals(0, manager.balance(player));
            assertFalse(manager.tryDebitDurably(player, player, GcAction.AUCTION_PURCHASE,
                    100, "purchase", "purchase").accepted());
        }
        assertEquals(GcManager.WithdrawCodeResult.OK, withdrawal.get(5, TimeUnit.SECONDS).result());
        assertEquals(0, storage.loadBalance(player));
        assertEquals(0, manager.balance(player));
    }

    @Test
    void withdrawalCompletionKeepsLaterReservationsInTheDisplayedBalance() throws Exception {
        manager.setBalance(player, staff, GcAction.STAFF_SET, 100, "seed");
        CompletableFuture<GcManager.WithdrawCodeOutcome> withdrawal;
        GcManager.DurableDebit debit;
        try (var held = database.getConnection()) {
            withdrawal = manager.withdrawToCode(player, 40);
            debit = manager.tryDebitDurably(player, player, GcAction.AUCTION_PURCHASE, 30, "purchase", "purchase");
            assertEquals(30, manager.balance(player));
        }
        assertEquals(GcManager.WithdrawCodeResult.OK, withdrawal.get(5, TimeUnit.SECONDS).result());
        assertTrue(debit.persisted().get(5, TimeUnit.SECONDS));
        assertEquals(30, storage.loadBalance(player));
        assertEquals(30, manager.balance(player));
    }

    @Test
    void staleIndependentWalletCannotCommitAnOverdraft() throws Exception {
        manager.setBalance(player, staff, GcAction.STAFF_SET, 100, "seed");
        GcManager stale = newManager();
        assertTrue(manager.tryDebitDurably(player, player, GcAction.AUCTION_PURCHASE, 80, "a", "a")
                .persisted().get(5, TimeUnit.SECONDS));
        assertFalse(stale.tryDebitDurably(player, player, GcAction.COINFLIP_WAGER, 80, "b", "b")
                .persisted().get(5, TimeUnit.SECONDS));
        assertEquals(20, storage.loadBalance(player));
        assertEquals(20, stale.balance(player));
        assertEquals(2, storage.loadLog(player, 10, 0).size(), "failed debit adds no audit row");
    }

    @Test
    void overflowingRedemptionPreservesBothWalletAndCode() throws Exception {
        manager.setBalance(player, staff, GcAction.STAFF_SET, Long.MAX_VALUE - 2, "seed");
        String code = code(10, 1);
        assertEquals(BALANCE_LIMIT, claim(player, code).result());
        assertEquals(Long.MAX_VALUE - 2, storage.loadBalance(player));
        assertEquals(Long.MAX_VALUE - 2, manager.balance(player));
        assertEquals(OK, claim(UUID.randomUUID(), code).result(), "code must remain available after rejected credit");
    }

    @Test
    void durablePayoutRetryAtTheBalanceLimitIsStillIdempotent() throws Exception {
        manager.setBalance(player, staff, GcAction.STAFF_SET, Long.MAX_VALUE - 10, "seed");
        assertTrue(manager.creditDurably(player, null, GcAction.COINFLIP_PAYOUT, 10, "payout").get(5, TimeUnit.SECONDS));
        assertTrue(manager.creditDurably(player, null, GcAction.COINFLIP_PAYOUT, 10, "payout").get(5, TimeUnit.SECONDS));
        assertFalse(manager.creditDurably(player, null, GcAction.COINFLIP_PAYOUT, 1, "different-payout").get(5, TimeUnit.SECONDS));
        assertEquals(Long.MAX_VALUE, storage.loadBalance(player));
        assertEquals(Long.MAX_VALUE, manager.balance(player));
    }

    @Test
    void failedSpeculativeCreditCannotFundAFollowingDebit() throws Exception {
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER fail_credit BEFORE INSERT ON gc_log "
                    + "WHEN NEW.action = 'STAFF_GIVE' BEGIN SELECT RAISE(ABORT, 'injected credit failure'); END");
        }
        CompletableFuture<Boolean> credit;
        GcManager.DurableDebit debit;
        try (var held = database.getConnection()) {
            credit = manager.creditDurably(player, staff, GcAction.STAFF_GIVE, 100, "credit");
            debit = manager.tryDebitDurably(player, player, GcAction.AUCTION_PURCHASE, 100, "spend", "spend");
        }
        assertFalse(credit.get(5, TimeUnit.SECONDS));
        assertFalse(debit.persisted().get(5, TimeUnit.SECONDS));
        assertEquals(0, storage.loadBalance(player));
        assertEquals(0, manager.balance(player));
        assertTrue(storage.loadLog(player, 10, 0).isEmpty());
    }

    @Test
    void codeLeakGuardRemembersFormatsAcrossReloadAndNewManagerStartup() throws Exception {
        String old = code(10, 1);
        var guard = new GcChatProtectionListener(plugin, manager, null);
        configure("redeem-code-length", 4);
        configure("redeem-code-charset", "1234");
        assertTrue(guard.containsUnescapedCode("(" + old + ")"));
        GcManager restarted = newManager();
        assertTrue(new GcChatProtectionListener(plugin, restarted, null).containsUnescapedCode(old));
        assertFalse(guard.containsUnescapedCode("\\" + old));
    }

    @Test
    void codeExpiryParserRejectsOverflowAndSupportsFractionalHoursExactly() throws Exception {
        var command = new GcCommand(plugin, manager, null, null, null);
        assertNull(command.parseDurationSeconds("1000000000000000000"));
        assertNull(command.parseDurationSeconds("1000000000000000000d"));
        assertNull(command.parseDurationSeconds("0"));
        assertNull(command.parseDurationSeconds("-1d"));
        assertNull(command.parseDurationSeconds("366d"));
        assertEquals(5400L, command.parseDurationSeconds("1.5h"));
        assertEquals(31_536_000L, command.parseDurationSeconds("365d"));
    }

    @Test
    void staffAdjustmentsUseSqlEvenWhenAnOfflineWalletCacheIsStale() throws Exception {
        storage.setAbsolute(player,staff,GcAction.STAFF_SET,100,"other shard",1);
        assertEquals(0,manager.balance(player));
        assertTrue(manager.adjustStaffDurably(player,staff,GcAction.STAFF_REMOVE,-80,"staff remove").get(5,TimeUnit.SECONDS));
        assertEquals(20,storage.loadBalance(player));
        assertEquals(20,manager.balance(player));
        assertFalse(manager.adjustStaffDurably(player,staff,GcAction.STAFF_REMOVE,-80,"staff remove").get(5,TimeUnit.SECONDS));
    }

    @Test
    void staffSetCompletionWaitsForTheLedgerWithoutBlockingAdmission() throws Exception {
        CompletableFuture<Boolean> result;
        try(var held=database.getConnection()){
            result=manager.setBalanceDurably(player,staff,GcAction.STAFF_SET,123,"staff set");
            assertFalse(result.isDone());assertEquals(0,manager.balance(player));
        }
        assertTrue(result.get(5,TimeUnit.SECONDS));
        assertEquals(123,storage.loadBalance(player));assertEquals(123,manager.balance(player));
    }
}
