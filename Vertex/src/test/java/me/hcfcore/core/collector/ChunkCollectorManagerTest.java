package me.hcfcore.core.collector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkCollectorManagerTest {

    private PluginMock plugin;
    private ChunkCollectorManager manager;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        // Storage is never touched by load()/capacityFor()/upgradeCost(),
        // only by loadIndexFromDatabase()/register()/unregister() -- this
        // file only covers the parts that don't need a live database.
        manager = new ChunkCollectorManager(plugin, null);
        manager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void loadParsesBundledDefaults() {
        assertTrue(manager.isEnabled());
        assertTrue(manager.isSilkTouchRequired());
        assertEquals(1, manager.maxPerChunk());
        assertEquals(3, manager.maxPerPlayer());
        assertEquals(5, manager.maxUpgradeTier());
        assertEquals(2, manager.hopperBlockRadius());
    }

    @Test
    void capacityScalesWithUpgradeTier() {
        long baseCapacity = manager.capacityFor(0);
        long tierOneCapacity = manager.capacityFor(1);
        assertEquals(50000, baseCapacity);
        assertTrue(tierOneCapacity > baseCapacity, "each tier should grant more capacity than the last");
    }

    @Test
    void upgradeCostIsPositiveBelowMaxTierAndNegativeAtMax() {
        assertTrue(manager.upgradeCost(0) > 0);
        assertEquals(-1, manager.upgradeCost(manager.maxUpgradeTier()));
    }
}
