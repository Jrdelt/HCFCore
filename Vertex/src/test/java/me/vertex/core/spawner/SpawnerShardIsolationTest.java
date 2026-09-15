package me.vertex.core.spawner;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.ShardScope;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ISS-15: two shards sharing one MySQL database can each have a world
 * literally named {@code world}. Without a shard-qualified key, a spawner
 * placed at the same coordinates on both shards collides in the shared
 * {@code spawners} table -- one shard's save/delete silently clobbers the
 * other's row, and loading resolves every shared row against whichever
 * shard happens to be reading, mistaking a remote row for a local block.
 *
 * <p>Simulates two shards with a single shared {@link Database} (standing in
 * for one shared MySQL instance) and two independently {@link ShardScope}-
 * configured {@link SpawnerManager}s pointed at worlds of the identical bare
 * name, at the identical coordinates.
 */
class SpawnerShardIsolationTest {
    @TempDir Path dataFolder;
    private ServerMock server;
    private PluginMock plugin;
    private Database database;
    private SpawnerStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new SpawnerStorage(database);
        storage.init();
    }

    @AfterEach
    void tearDown() {
        ShardScope.configure("");
        if (database != null) database.close();
        MockBukkit.unmock();
    }

    private SpawnerManager managerFor(String shardId) {
        ShardScope.configure(shardId);
        SpawnerManager manager = new SpawnerManager(plugin, storage);
        manager.load();
        return manager;
    }

    @Test
    void twoShardsWithTheSameWorldNameAndCoordinatesDoNotCollide() throws Exception {
        WorldMock world = server.addSimpleWorld("world");
        Location location = new Location(world, 100, 64, 100);

        SpawnerManager shardA = managerFor("shard-a");
        shardA.place(location, EntityType.ZOMBIE, "FactionA");
        shardA.awaitWrites();

        SpawnerManager shardB = managerFor("shard-b");
        shardB.place(location, EntityType.SKELETON, "FactionB");
        shardB.awaitWrites();

        List<SpawnerStorage.StoredSpawner> stored = storage.loadAll();
        assertEquals(2, stored.size(), "each shard's row at the same world/coordinates must survive independently");

        // Shard A must see only its own spawner at that location, not shard B's.
        ShardScope.configure("shard-a");
        SpawnerManager reloadedA = new SpawnerManager(plugin, storage);
        reloadedA.load();
        reloadedA.loadSpawnersFromDatabase();
        SpawnerData dataA = reloadedA.get(location);
        assertEquals(EntityType.ZOMBIE, dataA.mobType());
        assertEquals("FactionA", dataA.ownerFactionTag());

        // Shard B, reloading the exact same shared table, must see its own
        // different spawner at the identical world name and coordinates.
        ShardScope.configure("shard-b");
        SpawnerManager reloadedB = new SpawnerManager(plugin, storage);
        reloadedB.load();
        reloadedB.loadSpawnersFromDatabase();
        SpawnerData dataB = reloadedB.get(location);
        assertEquals(EntityType.SKELETON, dataB.mobType());
        assertEquals("FactionB", dataB.ownerFactionTag());
    }

    @Test
    void removingOneShardsSpawnerLeavesTheOtherShardsUnchanged() throws Exception {
        WorldMock world = server.addSimpleWorld("world");
        Location location = new Location(world, 200, 64, 200);

        SpawnerManager shardA = managerFor("shard-a");
        shardA.place(location, EntityType.ZOMBIE, "FactionA");
        shardA.awaitWrites();

        SpawnerManager shardB = managerFor("shard-b");
        shardB.place(location, EntityType.SKELETON, "FactionB");
        shardB.awaitWrites();

        // Shard A removes its own spawner entirely.
        ShardScope.configure("shard-a");
        shardA.remove(location);
        shardA.awaitWrites();

        List<SpawnerStorage.StoredSpawner> stored = storage.loadAll();
        assertEquals(1, stored.size(), "deleting shard A's row must not touch shard B's row at the same coordinates");

        ShardScope.configure("shard-b");
        SpawnerManager reloadedB = new SpawnerManager(plugin, storage);
        reloadedB.load();
        reloadedB.loadSpawnersFromDatabase();
        assertEquals(EntityType.SKELETON, reloadedB.get(location).mobType(), "shard B's spawner must be unchanged");

        ShardScope.configure("shard-a");
        SpawnerManager reloadedA = new SpawnerManager(plugin, storage);
        reloadedA.load();
        reloadedA.loadSpawnersFromDatabase();
        assertNull(reloadedA.get(location), "shard A must not resurrect shard B's row as its own");
    }

    @Test
    void unqualifiedLegacyRowIsAdoptedByWhicheverShardLoadsItFirst() throws Exception {
        WorldMock world = server.addSimpleWorld("world");
        Location location = new Location(world, 300, 64, 300);

        // A row saved before shard scoping existed (standalone deployment).
        ShardScope.configure("");
        storage.save(location, new SpawnerData(EntityType.ZOMBIE, 1, "LegacyFaction"));

        ShardScope.configure("shard-a");
        SpawnerManager shardA = new SpawnerManager(plugin, storage);
        shardA.load();
        shardA.loadSpawnersFromDatabase();
        assertTrue(shardA.isTracked(location), "an unqualified legacy row must migrate to the shard now loading it");
    }
}
