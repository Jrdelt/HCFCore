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
 * Covers the TNT balance Vertex took over from FactionsUUID, against a real
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

    @Test
    void refusesDepositBeyondCapacity() throws Exception {
        assertTrue(manager.depositTnt(FACTION, 900L, 1_000L).get());
        assertFalse(manager.depositTnt(FACTION, 200L, 1_000L).get());
        assertEquals(900L, manager.tnt(FACTION), "a refused deposit must not partially apply");
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
}
