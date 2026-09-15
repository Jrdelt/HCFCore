package me.vertex.core.collector;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.ShardScope;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISS-15: same collision the spawner table has (see
 * {@code SpawnerShardIsolationTest}) -- two shards sharing one MySQL
 * database can each have a world literally named {@code world}, and without
 * a shard-qualified key a Chunk Collector at the same coordinates on both
 * shards collides in the shared {@code chunk_collectors} table.
 */
class ChunkCollectorShardIsolationTest {
    @TempDir Path dataFolder;
    private ServerMock server;
    private PluginMock plugin;
    private Database database;
    private ChunkCollectorStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new ChunkCollectorStorage(database);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        ShardScope.configure("");
        if (database != null) database.close();
        MockBukkit.unmock();
    }

    @Test
    void twoShardsWithTheSameWorldNameAndCoordinatesDoNotCollide() throws Exception {
        WorldMock world = server.addSimpleWorld("world");
        Location location = new Location(world, 50, 64, 50);
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();

        ShardScope.configure("shard-a");
        storage.save(location, "FactionA", ownerA.toString());

        ShardScope.configure("shard-b");
        storage.save(location, "FactionB", ownerB.toString());

        List<ChunkCollectorStorage.StoredCollector> stored = storage.loadAll();
        assertEquals(2, stored.size(), "each shard's collector at the same world/coordinates must survive independently");

        ShardScope.configure("shard-a");
        ChunkCollectorManager managerA = new ChunkCollectorManager(plugin, storage, null);
        managerA.load();
        managerA.loadIndexFromDatabase();
        assertTrue(managerA.isTracked(location));

        ShardScope.configure("shard-b");
        ChunkCollectorManager managerB = new ChunkCollectorManager(plugin, storage, null);
        managerB.load();
        managerB.loadIndexFromDatabase();
        assertTrue(managerB.isTracked(location));
    }

    @Test
    void deletingOneShardsCollectorLeavesTheOtherShardsUnchanged() throws Exception {
        WorldMock world = server.addSimpleWorld("world");
        Location location = new Location(world, 60, 64, 60);

        ShardScope.configure("shard-a");
        storage.save(location, "FactionA", UUID.randomUUID().toString());
        ShardScope.configure("shard-b");
        storage.save(location, "FactionB", UUID.randomUUID().toString());

        ShardScope.configure("shard-a");
        storage.delete(location);

        List<ChunkCollectorStorage.StoredCollector> stored = storage.loadAll();
        assertEquals(1, stored.size(), "deleting shard A's row must not touch shard B's row at the same coordinates");
        assertEquals("FactionB", stored.getFirst().ownerFactionTag());

        ShardScope.configure("shard-a");
        ChunkCollectorManager managerA = new ChunkCollectorManager(plugin, storage, null);
        managerA.load();
        managerA.loadIndexFromDatabase();
        assertFalse(managerA.isTracked(location), "shard A must not resurrect shard B's row as its own");
    }
}
