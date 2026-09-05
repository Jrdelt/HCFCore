package me.hcfcore.core.spawner;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnerManagerTest {

    private PluginMock plugin;
    private SpawnerManager manager;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        // Storage is never touched by load()/rollDrops()/the static item
        // helpers, only by the stacking methods -- this file only covers
        // the parts that don't need a live database.
        manager = new SpawnerManager(plugin, null);
        manager.load();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void loadParsesTheBundledMobCatalog() {
        for (EntityType type : List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.BLAZE,
                EntityType.IRON_GOLEM, EntityType.EVOKER)) {
            SpawnerManager.MobConfig config = manager.getMobConfig(type);
            assertNotNull(config, "spawners.yml should define " + type);
            assertTrue(config.price() > 0, type + " should have a positive price");
            assertFalse(config.drops().isEmpty(), type + " should have a configured drop table");
        }
        assertEquals(50, manager.maxStackSize());
        assertFalse(manager.isSilkTouchRequired());
    }

    @Test
    void unconfiguredMobHasNoConfig() {
        assertNull(manager.getMobConfig(EntityType.CREEPER));
    }

    @Test
    void spawnerItemRoundTripsItsMobType() {
        ItemStack item = SpawnerManager.createSpawnerItem(EntityType.BLAZE, Component.text("Blaze Spawner"));

        assertEquals(Material.SPAWNER, item.getType());
        assertEquals(EntityType.BLAZE, SpawnerManager.readSpawnedType(item));
    }

    @Test
    void readSpawnedTypeIsNullForNonSpawnerItems() {
        assertNull(SpawnerManager.readSpawnedType(new ItemStack(Material.DIRT)));
        assertNull(SpawnerManager.readSpawnedType(null));
    }

    @Test
    void rollDropsStaysWithinConfiguredBoundsForA100PercentEntry() {
        // Skeleton's ARROW entry is chance 1.0, min 0, max 2 -- every roll
        // across many attempts must land in [0, 2] and never include a
        // material the config didn't list.
        for (int i = 0; i < 200; i++) {
            List<ItemStack> drops = manager.rollDrops(EntityType.SKELETON);
            for (ItemStack drop : drops) {
                assertTrue(drop.getType() == Material.BONE || drop.getType() == Material.ARROW
                                || drop.getType() == Material.IRON_INGOT,
                        "unexpected drop material " + drop.getType());
                assertTrue(drop.getAmount() >= 1 && drop.getAmount() <= 2,
                        "drop amount out of configured bounds: " + drop.getAmount());
            }
        }
    }

    @Test
    void rollDropsIsEmptyForAMobWithNoDropTable() {
        assertTrue(manager.rollDrops(EntityType.CREEPER).isEmpty());
    }

    @Test
    void loadParsesTheBundledMobStackingSection() {
        assertTrue(manager.isMobStackingEnabled());
        assertEquals(50.0, manager.mergeRadiusBlocks());
        assertEquals(100, manager.maxStackLimit());
        assertEquals(64, manager.dropBatchSize());
        assertEquals("<gray>[x{count}] <white>{name}", manager.stackDisplayFormat());
        for (EntityType type : List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.BLAZE,
                EntityType.IRON_GOLEM, EntityType.EVOKER)) {
            assertTrue(manager.stackableTypes().contains(type), type + " should be stackable per spawners.yml");
        }
        assertFalse(manager.stackableTypes().contains(EntityType.CREEPER));
    }

    @Test
    void loadFallsBackToBundledDefaultsWhenOnDiskFileIsMissingNewerKeys() throws Exception {
        // Simulates a spawners.yml saved to disk before mob-stacking existed
        // -- getStringList/getBoolean/etc. on a section that's just plain
        // absent from the file would otherwise silently read as
        // empty/disabled forever, since nothing ever re-adds it.
        File file = new File(plugin.getDataFolder(), "spawners.yml");
        Files.writeString(file.toPath(), "max-stack-size: 32\n", StandardCharsets.UTF_8);

        SpawnerManager fallbackManager = new SpawnerManager(plugin, null);
        fallbackManager.load();

        assertEquals(32, fallbackManager.maxStackSize(), "the on-disk override should still win");
        assertTrue(fallbackManager.isMobStackingEnabled(), "missing section should fall back to the bundled default");
        assertFalse(fallbackManager.stackableTypes().isEmpty(), "missing stackable-types should fall back to the bundled list");
    }
}
