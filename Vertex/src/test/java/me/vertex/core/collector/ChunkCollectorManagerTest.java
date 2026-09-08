package me.vertex.core.collector;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

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
        manager = new ChunkCollectorManager(plugin, null, null);
        manager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void loadParsesBundledDefaults() {
        assertTrue(manager.isEnabled());
        assertEquals(1, manager.maxPerChunk());
        assertEquals(3, manager.maxPerPlayer());
        assertEquals(24, manager.maxStoredMaterialTypes());
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

    @Test
    void materialTypeCapAllowsExistingStoredMaterialOnly() {
        ChunkCollectorData data = new ChunkCollectorData(0, java.util.UUID.randomUUID(), "test");
        for (org.bukkit.Material material : org.bukkit.Material.values()) {
            if (material.isItem() && data.stored().size() < manager.maxStoredMaterialTypes()) {
                data.setStored(material, 1);
            }
        }
        assertTrue(manager.canStore(data, data.stored().keySet().iterator().next()));
        org.bukkit.Material newMaterial = org.bukkit.Material.values()[0];
        for (org.bukkit.Material material : org.bukkit.Material.values()) {
            if (material.isItem() && !data.stored().containsKey(material)) {
                newMaterial = material;
                break;
            }
        }
        assertTrue(!manager.canStore(data, newMaterial));
    }

    @Test
    void unplacedCollectorItemShowsPortableStatsButNeverFactionOrOwner() {
        ChunkCollectorData data = new ChunkCollectorData(2, java.util.UUID.randomUUID(), "secret-faction");
        data.setStored(org.bukkit.Material.DIAMOND, 42);
        org.bukkit.inventory.ItemStack item = manager.createCollectorItem(Component.text("Chunk Collector"), data);

        String lore = item.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .reduce("", (left, right) -> left + "\n" + right);

        assertTrue(lore.contains("Level: 2"));
        assertTrue(lore.contains("42 items"));
        assertTrue(lore.contains("Capacity:"));
        assertTrue(!lore.contains("secret-faction"));
        assertTrue(!lore.contains(data.ownerUuid().toString()));
    }
}
