package me.vertex.core.enchant.binds;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trips every table {@link BindManager} relies on -- the live layout, presets, the active-preset marker, and global rune pruning. */
class BindStorageTest {

    @TempDir
    Path dataFolder;

    private Database database;
    private BindStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new BindStorage(database);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void savingABindReplacesItWholesaleRatherThanLeavingStaleSlots() throws Exception {
        UUID uuid = UUID.randomUUID();
        storage.saveBind(uuid, 1, List.of("dasher", "sky_stepper", "riftwalker"));
        assertEquals(List.of("dasher", "sky_stepper", "riftwalker"), storage.loadSlots(uuid).get(1));

        storage.saveBind(uuid, 1, List.of("dasher"));
        assertEquals(List.of("dasher"), storage.loadSlots(uuid).get(1),
                "the old level-2/3 rows must be gone, not merely appended to");
    }

    @Test
    void presetsRoundTripIndependentlyOfTheLiveLayout() throws Exception {
        UUID uuid = UUID.randomUUID();
        storage.savePreset(uuid, 1, Map.of(1, List.of("dasher"), 2, List.of("sky_stepper")));

        Map<Integer, Map<Integer, List<String>>> presets = storage.loadAllPresets(uuid);
        assertEquals(List.of("dasher"), presets.get(1).get(1));
        assertEquals(List.of("sky_stepper"), presets.get(1).get(2));
        assertTrue(storage.loadSlots(uuid).isEmpty(), "saving a preset must never touch the live layout table");
    }

    @Test
    void activePresetMarkerRoundTrips() throws Exception {
        UUID uuid = UUID.randomUUID();
        storage.saveActivePreset(uuid, 3, true);

        BindStorage.ActivePreset active = storage.loadActivePreset(uuid);
        assertEquals(3, active.presetIndex());
        assertTrue(active.dirty());

        storage.saveActivePreset(uuid, null, false);
        active = storage.loadActivePreset(uuid);
        assertNull(active.presetIndex());
    }

    @Test
    void deletePresetsAboveEnforcesALoweredRankEntitlement() throws Exception {
        UUID uuid = UUID.randomUUID();
        for (int preset = 1; preset <= 5; preset++) {
            storage.savePreset(uuid, preset, Map.of(1, List.of("dasher")));
        }

        storage.deletePresetsAbove(uuid, 3);

        Map<Integer, Map<Integer, List<String>>> remaining = storage.loadAllPresets(uuid);
        assertEquals(java.util.Set.of(1, 2, 3), remaining.keySet());
    }

    @Test
    void pruningARuneRemovesItFromEveryPlayersBindsAndPresets() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        storage.saveBind(first, 1, List.of("dasher", "sky_stepper"));
        storage.savePreset(second, 1, Map.of(1, List.of("dasher")));

        storage.pruneRuneEverywhere("dasher");

        assertEquals(List.of("sky_stepper"), storage.loadSlots(first).get(1));
        assertTrue(storage.loadAllPresets(second).isEmpty());
    }
}
