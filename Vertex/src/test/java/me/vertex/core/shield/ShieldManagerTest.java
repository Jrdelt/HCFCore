package me.vertex.core.shield;

import me.vertex.core.claims.BaseClaimManager;
import me.vertex.core.claims.ClaimStorage;
import me.vertex.core.spawner.SpawnerManager;
import me.vertex.core.storage.Database;
import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionRole;
import me.vertex.core.factions.FactionStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShieldManagerTest {
    @TempDir Path dataFolder;
    private PluginMock plugin;
    private Database database;
    private ShieldStorage storage;
    private ShieldManager manager;

    @BeforeEach void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new ShieldStorage(database); storage.init();
        ClaimStorage claims = new ClaimStorage(database); claims.init();
        SpawnerManager spawners = new SpawnerManager(plugin, null); spawners.load();
        BaseClaimManager baseClaims = new BaseClaimManager(plugin, claims, spawners); baseClaims.load(); baseClaims.loadState();
        manager = new ShieldManager(plugin, storage, baseClaims); manager.load(); manager.loadState();
    }

    @AfterEach void tearDown() { database.close(); MockBukkit.unmock(); }

    @Test void activationPersistsAndSurvivesReload() {
        assertEquals(ShieldManager.ActivateResult.OK, manager.activate(1, UUID.randomUUID()));
        assertTrue(manager.isShieldActive(1));
        ShieldManager reloaded = new ShieldManager(plugin, storage, null); reloaded.load(); reloaded.loadState();
        assertTrue(reloaded.isShieldActive(1));
        assertTrue(reloaded.secondsUntilDeactivation(1) > 0);
    }

    @Test void duplicateActivationCannotExtendAnActiveShield() {
        assertEquals(ShieldManager.ActivateResult.OK, manager.activate(2, UUID.randomUUID()));
        long remaining = manager.secondsUntilDeactivation(2);
        assertEquals(ShieldManager.ActivateResult.ALREADY_ACTIVE, manager.activate(2, UUID.randomUUID()));
        assertTrue(manager.secondsUntilDeactivation(2) <= remaining);
    }

    @Test void staleShardCannotActivateAnAlreadyActiveShield() {
        ShieldManager staleShard = new ShieldManager(plugin, storage, null);
        staleShard.load();
        staleShard.loadState();
        assertEquals(ShieldManager.ActivateResult.OK, manager.activate(9, UUID.randomUUID()));
        assertEquals(ShieldManager.ActivateResult.ALREADY_ACTIVE,
                staleShard.activate(9, UUID.randomUUID()));
    }

    @Test void storageRechecksLeaderOrCoLeaderForManualActivation() throws Exception {
        FactionStorage factions = new FactionStorage(database);
        factions.init();
        UUID leader = UUID.randomUUID();
        long now = System.currentTimeMillis();
        int factionId = factions.createFaction(
                new FactionData(-1, "Shielders", "", false, false, 100, 100, now, null),
                new FactionMember(leader, -1, FactionRole.LEADER, "Leader", now));

        assertEquals(ShieldManager.ActivateResult.NO_PERMISSION,
                manager.activate(factionId, UUID.randomUUID()));
        assertEquals(ShieldManager.ActivateResult.OK, manager.activate(factionId, leader));
    }

    @Test void configuredUpgradeBonusIncreasesDuration() {
        manager.setDurationBonusProvider(id -> id == 3 ? 3_600 : 0);
        assertEquals(manager.durationSeconds(4) + 3_600, manager.durationSeconds(3));
    }

    @Test void defaultManualShieldDurationIsEightHoursAndCapsAtTwelve() {
        assertEquals(28_800L, manager.durationSeconds(4));
        manager.setDurationBonusProvider(id -> id == 3 ? 14_400L : 0L);
        assertEquals(43_200L, manager.durationSeconds(3));
    }

    @Test void expiredShieldHonorsPersistedCooldown() throws Exception {
        long now = System.currentTimeMillis();
        storage.upsertActivation(new ShieldStorage.ActivationRow(5, now - 1_000, now + 60_000, now - 10_000, now - 20_000, null));
        manager.loadState();
        assertFalse(manager.isShieldActive(5));
        assertEquals(ShieldManager.ActivateResult.COOLDOWN, manager.activate(5, UUID.randomUUID()));
        assertTrue(manager.secondsUntilAvailable(5) > 0);
    }

    @Test void overrideWinsAndClearReturnsToActivationState() {
        assertEquals(ShieldManager.ActivateResult.OK, manager.activate(6, UUID.randomUUID()));
        assertTrue(manager.applyOverride(6, false, UUID.randomUUID()));
        assertFalse(manager.isShieldActive(6));
        assertTrue(manager.removeOverride(6, UUID.randomUUID()));
        assertTrue(manager.isShieldActive(6));
    }

    @Test void disbandRemovesActivationAndOverride() throws Exception {
        manager.activate(7, UUID.randomUUID());
        manager.applyOverride(7, true, UUID.randomUUID());
        manager.onFactionDisbanded(7);
        assertFalse(manager.isShieldActive(7));
        assertTrue(manager.override(7).isEmpty());
        assertTrue(storage.loadAllActivations().stream().noneMatch(row -> row.factionId() == 7));
    }

    @Test void weeklyEditLockIsAuthoritativeAcrossStaleShardCaches() {
        manager.onFactionCreated(8);
        ShieldManager secondShard = new ShieldManager(plugin, storage, null);
        secondShard.load();
        secondShard.loadState();
        ShieldSchedule first = ShieldSchedule.empty().withDay(0,
                new ShieldSchedule.Window(60, 120));
        ShieldSchedule second = ShieldSchedule.empty().withDay(0,
                new ShieldSchedule.Window(300, 120));

        assertEquals(ShieldManager.ScheduleResult.OK,
                manager.replaceSchedule(8, first, UUID.randomUUID(), false));
        assertEquals(ShieldManager.ScheduleResult.LOCKED,
                secondShard.replaceSchedule(8, second, UUID.randomUUID(), false));
        assertEquals(first, manager.pendingSchedule(8));
    }

    @Test void crossMidnightWindowsCannotExceedTheDailyMaximum() {
        manager.onFactionCreated(10);
        ShieldSchedule schedule = ShieldSchedule.empty()
                .withDay(0, new ShieldSchedule.Window(20 * 60, 8 * 60))
                .withDay(1, new ShieldSchedule.Window(4 * 60, 8 * 60));
        assertEquals(12 * 60, schedule.protectedMinutes(1));
        assertEquals(ShieldManager.ScheduleResult.INVALID,
                manager.replaceSchedule(10, schedule, UUID.randomUUID(), false));
    }
}
