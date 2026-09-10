package me.vertex.core.shield;

import me.vertex.core.claims.BaseClaimManager;
import me.vertex.core.claims.ClaimStorage;
import me.vertex.core.spawner.SpawnerManager;
import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ShieldManager}'s eligibility timer, schedule submission,
 * the "let the current window finish before switching schedules" edge
 * case, and admin override freeze/resume -- the trickiest timing logic in
 * this phase, per the same "verify independently, don't just trust it"
 * standard {@code RaidClaimManagerTest} applies to its own restart-catchup
 * contract.
 */
class ShieldManagerTest {

    @TempDir
    Path dataFolder;

    private org.mockbukkit.mockbukkit.ServerMock server;
    private PluginMock plugin;
    private ShieldStorage storage;
    private ShieldManager manager;
    private BaseClaimManager baseClaims;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Database database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new ShieldStorage(database);
        storage.init();
        ClaimStorage claimStorage = new ClaimStorage(database);
        claimStorage.init();
        SpawnerManager spawners = new SpawnerManager(plugin, null);
        spawners.load();
        baseClaims = new BaseClaimManager(plugin, claimStorage, spawners);
        baseClaims.load();
        baseClaims.loadState();
        manager = new ShieldManager(plugin, storage, baseClaims);
        manager.load();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void newFactionIsNotEligibleUntilItsWaitElapses() {
        manager.loadState();
        manager.onFactionCreated(1);
        assertFalse(manager.isEligible(1));
        assertTrue(manager.eligibleAtMillis(1) > System.currentTimeMillis());
    }

    @Test
    void eligibilityTimerSurvivesSimulatedDowntime() throws Exception {
        // Seed a row whose wait already elapsed while the server was "down",
        // the same way RaidClaimManagerTest seeds an already-overdue row
        // directly through storage rather than sleeping in real time.
        long alreadyElapsed = System.currentTimeMillis() - 1_000L;
        storage.upsertRow(new ShieldStorage.ShieldRow(2, null, null, null, null, null, null, alreadyElapsed));
        manager.loadState();
        assertTrue(manager.isEligible(2), "a wait that elapsed during downtime must count as satisfied on restart");
    }

    @Test
    void scheduleSubmissionIsRejectedBeforeEligibility() {
        manager.loadState();
        manager.onFactionCreated(3);
        ShieldManager.SubmitResult result = manager.submitSchedule(3,
                ShieldSchedule.ofHourMinuteDuration(22, 0, 60), UUID.randomUUID());
        assertEquals(ShieldManager.SubmitResult.NOT_ELIGIBLE, result);
        assertTrue(manager.liveSchedule(3).isEmpty());
    }

    @Test
    void scheduleSubmissionAfterEligibilityBecomesPendingNotImmediate() throws Exception {
        storage.upsertRow(new ShieldStorage.ShieldRow(4, null, null, null, null, null, null,
                System.currentTimeMillis() - 1_000L));
        manager.loadState();
        ShieldManager.SubmitResult result = manager.submitSchedule(4,
                ShieldSchedule.ofHourMinuteDuration(22, 0, 60), UUID.randomUUID());
        assertEquals(ShieldManager.SubmitResult.OK, result);
        assertTrue(manager.liveSchedule(4).isEmpty(), "a brand new submission must not apply instantly");
        assertTrue(manager.pendingSchedule(4).isPresent());
    }

    @Test
    void pendingSwapWaitsForTheCurrentActiveWindowToFinish() throws Exception {
        long now = System.currentTimeMillis();
        // Live schedule active right now (a huge window covering "now" every day).
        ShieldSchedule alwaysOnToday = new ShieldSchedule(0, 1440);
        // Pending schedule already past its activation delay -- but the old
        // schedule's window ("always on") is still active, so it must not
        // have switched yet.
        storage.upsertRow(new ShieldStorage.ShieldRow(5, alwaysOnToday.startMinuteOfDay(), alwaysOnToday.durationMinutes(),
                600, 30, now - 1_000L, null, now - 10_000L));
        manager.loadState();

        assertTrue(manager.isShieldActive(5), "the old schedule's still-running window must not be cut short");
        assertEquals(alwaysOnToday, manager.effectiveSchedule(5, now).orElseThrow(),
                "effective schedule must still be the old one while its window is active");
    }

    @Test
    void pendingSwapAppliesOnceOldWindowIsNotActive() throws Exception {
        long now = System.currentTimeMillis();
        // Old schedule's window is NOT active right now (a 1-minute window far in the past today).
        storage.upsertRow(new ShieldStorage.ShieldRow(6, 0, 1, 600, 30, now - 1_000L, null, now - 10_000L));
        manager.loadState();

        ShieldSchedule effective = manager.effectiveSchedule(6, now).orElseThrow();
        assertEquals(new ShieldSchedule(600, 30), effective, "must have switched to the pending schedule");
    }

    @Test
    void overrideForcesStateRegardlessOfSchedule() throws Exception {
        long now = System.currentTimeMillis();
        // No live schedule at all -- would normally be inactive.
        storage.upsertRow(new ShieldStorage.ShieldRow(7, null, null, null, null, null, null, now));
        manager.loadState();
        assertFalse(manager.isShieldActive(7));

        manager.applyOverride(7, true, UUID.randomUUID());
        assertTrue(manager.isShieldActive(7), "a force-ACTIVE override must win over an otherwise-inactive schedule");

        manager.applyOverride(7, false, UUID.randomUUID());
        assertFalse(manager.isShieldActive(7));
    }

    @Test
    void removingOverrideResumesRemainingTimeRatherThanRestartingTheWindow() throws Exception {
        long now = System.currentTimeMillis();
        // Build a window that starts now and lasts 10 minutes so it's active with ~10 min remaining.
        java.time.ZonedDateTime nowEastern = java.time.Instant.ofEpochMilli(now).atZone(ShieldSchedule.ZONE);
        int startMinute = nowEastern.getHour() * 60 + nowEastern.getMinute();
        ShieldSchedule tenMinuteWindow = new ShieldSchedule(startMinute, 10);
        storage.upsertRow(new ShieldStorage.ShieldRow(8, tenMinuteWindow.startMinuteOfDay(),
                tenMinuteWindow.durationMinutes(), null, null, null, null, now));
        manager.loadState();
        assertTrue(manager.isShieldActive(8), "setup: the faction should be inside its active window");

        // Force INACTIVE (overriding the currently-active window), which must freeze the remaining time.
        UUID admin = UUID.randomUUID();
        manager.applyOverride(8, false, admin);
        assertFalse(manager.isShieldActive(8));

        // Remove the override -- must resume as active for roughly the frozen remaining time,
        // not simply fall back to "is the wall-clock window still active" (which could already be false).
        manager.removeOverride(8, admin);
        assertTrue(manager.isShieldActive(8), "removing the override must resume protection for the frozen remaining time");
    }

    @Test
    void shieldNeverProtectsALocationOutsideABaseClaimRegion() {
        manager.loadState();
        manager.onFactionCreated(9);
        manager.applyOverride(9, true, UUID.randomUUID());
        assertTrue(manager.isShieldActive(9));

        org.bukkit.Location wildernessLocation = new org.bukkit.Location(
                server.addSimpleWorld("shield-test-world"), 0, 64, 0);
        assertFalse(manager.isBaseClaimProtected(wildernessLocation),
                "a location with no Base Claim region must never be reported as Shield-protected, "
                        + "even for a faction whose Shield is forced active (this is how Raid Claims stay excluded)");
    }

    @Test
    void disbandingAFactionClearsItsShieldStateForAFreshWaitOnRecreation() throws Exception {
        manager.loadState();
        manager.onFactionCreated(10);
        assertFalse(manager.isEligible(10));
        manager.applyOverride(10, true, UUID.randomUUID());

        manager.onFactionDisbanded(10);
        assertTrue(manager.override(10).isEmpty());
        assertEquals(0L, manager.eligibleAtMillis(10));

        // Recreating starts a brand new wait rather than reusing anything stale.
        manager.onFactionCreated(10);
        assertFalse(manager.isEligible(10));
    }
}
