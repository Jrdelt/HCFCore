package me.vertex.core.faction;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PvpTopManagerTest {
    @TempDir Path directory;
    Database database;
    PvpTopStorage storage;
    PvpTopManager manager;

    @BeforeEach void setup() throws Exception {
        MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        new YamlConfiguration().save(new java.io.File(plugin.getDataFolder(), "pvptop.yml"));
        database = new Database(new YamlConfiguration(), directory.toFile());
        storage = new PvpTopStorage(database);
        storage.init();
        manager = new PvpTopManager(plugin, storage);
        manager.load();
        manager.loadState();
    }
    @AfterEach void close() { manager.awaitWrites(); database.close(); MockBukkit.unmock(); }

    @Test void remoteAwardAppearsAfterRefreshWithoutRestart() throws Exception {
        storage.award(7, 10, "koth:one", "koth:one:1", null, 1);
        assertTrue(manager.leaderboard().isEmpty());
        manager.refreshAsync().join();
        assertEquals(10, manager.leaderboard().getFirst().points());
    }
    @Test void disbandedFactionIsRemovedInsteadOfSurvivingInCache() throws Exception {
        storage.award(7, 10, "koth:one", "koth:one:1", null, 1);
        manager.refreshAsync().join();
        try (var c = database.getConnection(); var s = c.createStatement()) { s.executeUpdate("DELETE FROM pvptop_points WHERE faction_id=7"); }
        manager.refreshAsync().join();
        assertTrue(manager.leaderboard().isEmpty());
    }
    @Test void restartLoadsLatestSharedSnapshot() throws Exception {
        storage.award(7, 10, "koth:one", "koth:one:1", null, 1);
        manager.loadState();
        assertEquals(7, manager.leaderboard().getFirst().factionId());
        storage.award(8, 20, "koth:two", "koth:two:1", null, 2);
        manager.loadState();
        assertEquals(8, manager.leaderboard().getFirst().factionId());
        assertEquals(2, manager.leaderboard().size());
    }
}
