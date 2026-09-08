package me.vertex.core.mine;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The point of persisting ownership is that a reboot must not hand a faction
 * a fresh booster ladder, so these focus on the ownership timestamp.
 */
class MineKothStorageTest {

    @TempDir
    Path dataFolder;

    private Database database;
    private MineKothStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new MineKothStorage(database);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void roundTripsOwnershipIncludingWhenItWasTaken() throws Exception {
        storage.save("stonewake", 7, 100.0, 1_700_000_000_000L);

        List<MineKothStorage.StoredKoth> loaded = storage.loadAll();

        assertEquals(1, loaded.size());
        assertEquals("stonewake", loaded.get(0).mineId());
        assertEquals(7, loaded.get(0).ownerFaction());
        assertEquals(100.0, loaded.get(0).control(), 0.0001);
        assertEquals(1_700_000_000_000L, loaded.get(0).ownedSinceMillis(),
                "the moment it was taken must survive, or a reboot resets the booster ladder");
    }

    @Test
    void storesAnUnownedPointWithoutAFaction() throws Exception {
        storage.save("bloodvein", null, 42.5, 0L);

        MineKothStorage.StoredKoth loaded = storage.loadAll().get(0);

        assertNull(loaded.ownerFaction(), "an unowned point has no faction");
        assertEquals(42.5, loaded.control(), 0.0001, "part-way capture progress still persists");
    }

    @Test
    void savingAgainReplacesRatherThanDuplicating() throws Exception {
        storage.save("stonewake", 7, 100.0, 1_000L);
        storage.save("stonewake", 9, 60.0, 2_000L);

        List<MineKothStorage.StoredKoth> loaded = storage.loadAll();

        assertEquals(1, loaded.size(), "one row per mine");
        assertEquals(9, loaded.get(0).ownerFaction(), "the newer owner wins");
        assertEquals(2_000L, loaded.get(0).ownedSinceMillis());
    }

    @Test
    void keepsEachMineSeparate() throws Exception {
        storage.save("stonewake", 1, 100.0, 500L);
        storage.save("bloodvein", 2, 100.0, 900L);

        assertEquals(2, storage.loadAll().size());
    }
}
