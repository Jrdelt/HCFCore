package me.vertex.core.storage;

import me.vertex.core.blueprint.BlueprintStorage;
import me.vertex.core.collector.ChunkCollectorStorage;
import me.vertex.core.spawner.SpawnerStorage;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the local (SQLite) storage backend against a real database
 * file, rather than a fake. The SQL that differs between SQLite and MySQL
 * -- the upserts, the auto-incrementing id columns, and the death table's
 * separate index -- only runs against a real engine, so a syntax mistake
 * in either dialect is invisible to a mocked-out test.
 *
 * <p>The MySQL branch can't be covered here: it opens a real connection on
 * construction, so it would need a live MySQL server present in CI.
 */
class LocalStorageTest {

    @TempDir
    Path dataFolder;

    private ServerMock server;
    private Database database;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
            database = null;
        }
        MockBukkit.unmock();
    }

    private Database open(String configuredType) {
        YamlConfiguration config = new YamlConfiguration();
        if (configuredType != null) {
            config.set("storage.type", configuredType);
        }
        database = new Database(config, dataFolder.toFile());
        return database;
    }

    @Test
    void defaultsToLocalSqliteWhenNothingIsConfigured() {
        Database db = open(null);

        assertEquals(Database.Dialect.SQLITE, db.dialect());
        assertTrue(new File(dataFolder.toFile(), "vertex.db").exists(),
                "the local database file should be created in the plugin's data folder");
    }

    @Test
    void explicitLocalTypeUsesSqlite() {
        assertEquals(Database.Dialect.SQLITE, open("local").dialect());
    }

    @Test
    void unrecognizedTypeFallsBackToLocalRatherThanFailing() {
        // "defaults to local unless told otherwise" -- a typo in the config
        // must not leave the server unable to start.
        assertEquals(Database.Dialect.SQLITE, open("postgres-please").dialect());
    }

    @Test
    void kitAndAbilityCooldownsRoundTripAndUpdateInPlace() throws SQLException {
        SqlStorage storage = new SqlStorage(open(null));
        storage.init();
        UUID uuid = UUID.randomUUID();

        storage.saveCooldown(uuid, "archer", 1_000L);
        storage.saveAbilityCooldown(uuid, "leap", 2_000L);

        assertEquals(Map.of("archer", 1_000L), storage.loadCooldowns(uuid));
        assertEquals(Map.of("leap", 2_000L), storage.loadAbilityCooldowns(uuid));

        // Saving the same key again must update the row, not insert a
        // second one -- this is what the dialect-specific upsert is for.
        storage.saveCooldown(uuid, "archer", 5_000L);
        storage.saveAbilityCooldown(uuid, "leap", 6_000L);

        assertEquals(Map.of("archer", 5_000L), storage.loadCooldowns(uuid));
        assertEquals(Map.of("leap", 6_000L), storage.loadAbilityCooldowns(uuid));
    }

    @Test
    void localeRoundTripsAndUpdatesInPlace() throws SQLException {
        SqlStorage storage = new SqlStorage(open(null));
        storage.init();
        UUID uuid = UUID.randomUUID();

        assertNull(storage.loadLocale(uuid));

        storage.saveLocale(uuid, "es_us");
        assertEquals("es_us", storage.loadLocale(uuid));

        storage.saveLocale(uuid, "pt_br");
        assertEquals("pt_br", storage.loadLocale(uuid));
    }

    @Test
    void spawnersRoundTripAndUpdateInPlace() throws SQLException {
        SpawnerStorage storage = new SpawnerStorage(open(null));
        storage.init();
        Location location = new Location(world(), 10, 64, -20);

        storage.save(location, new me.vertex.core.spawner.SpawnerData(EntityType.ZOMBIE, 3, "Raiders"));
        List<SpawnerStorage.StoredSpawner> loaded = storage.loadAll();
        assertEquals(1, loaded.size());
        assertEquals(3, loaded.get(0).stackSize());
        assertEquals(EntityType.ZOMBIE, loaded.get(0).mobType());
        assertEquals("Raiders", loaded.get(0).ownerFactionTag());

        storage.save(location, new me.vertex.core.spawner.SpawnerData(EntityType.ZOMBIE, 9, "Raiders"));
        loaded = storage.loadAll();
        assertEquals(1, loaded.size(), "re-saving the same location must not duplicate the row");
        assertEquals(9, loaded.get(0).stackSize());

        storage.delete(location);
        assertTrue(storage.loadAll().isEmpty());
    }

    @Test
    void spawnerStorageUpgradesThePreOwnershipSchema() throws SQLException {
        Database oldDatabase = open(null);
        try (Connection connection = oldDatabase.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     CREATE TABLE spawners (
                         world VARCHAR(64) NOT NULL,
                         x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
                         mob_type VARCHAR(64) NOT NULL, stack_size INT NOT NULL,
                         PRIMARY KEY (world, x, y, z)
                     )""")) {
            statement.executeUpdate();
        }

        SpawnerStorage storage = new SpawnerStorage(oldDatabase);
        storage.init();
        Location location = new Location(world(), 3, 64, 3);
        storage.save(location, new me.vertex.core.spawner.SpawnerData(EntityType.SKELETON, 2, "Raiders"));

        assertEquals("Raiders", storage.loadAll().getFirst().ownerFactionTag(),
                "existing databases need the ownership column before current writes run");
    }

    @Test
    void chunkCollectorsRoundTripAndUpdateInPlace() throws SQLException {
        ChunkCollectorStorage storage = new ChunkCollectorStorage(open(null));
        storage.init();
        Location location = new Location(world(), 1, 70, 2);
        String owner = UUID.randomUUID().toString();

        storage.save(location, "Raiders", owner);
        List<ChunkCollectorStorage.StoredCollector> loaded = storage.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("Raiders", loaded.get(0).ownerFactionTag());
        assertEquals(owner, loaded.get(0).ownerUuid());

        storage.save(location, "Defenders", owner);
        loaded = storage.loadAll();
        assertEquals(1, loaded.size(), "re-saving the same location must not duplicate the row");
        assertEquals("Defenders", loaded.get(0).ownerFactionTag());

        storage.delete(location);
        assertTrue(storage.loadAll().isEmpty());
    }

    @Test
    void blueprintBuildsGetGeneratedIdsAndRoundTrip() throws SQLException {
        BlueprintStorage storage = new BlueprintStorage(open(null));
        storage.init();
        Location location = new Location(world(), 5, 65, 5);
        String owner = UUID.randomUUID().toString();

        // The auto-incrementing id column is declared differently in each
        // dialect, and the caller depends on getGeneratedKeys() working.
        int first = storage.insert(location, "outpost", owner, "Raiders", 123L);
        int second = storage.insert(location, "outpost", owner, "Raiders", 456L);
        assertTrue(first > 0, "SQLite should hand back a generated row id");
        assertTrue(second > first, "each build should get its own id");

        storage.updateProgress(first, 42);
        List<BlueprintStorage.StoredBuild> builds = storage.loadAll();
        assertEquals(2, builds.size());
        BlueprintStorage.StoredBuild reloaded = builds.stream()
                .filter(build -> build.id() == first)
                .findFirst()
                .orElseThrow();
        assertEquals(42, reloaded.currentIndex());
        assertEquals("outpost", reloaded.template());
        assertEquals(owner, reloaded.ownerUuid());
        assertEquals(123L, reloaded.startedAt());

        storage.delete(first);
        storage.delete(second);
        assertTrue(storage.loadAll().isEmpty());
    }

    @Test
    void dataSurvivesReopeningTheSameFile() throws SQLException {
        SqlStorage storage = new SqlStorage(open(null));
        storage.init();
        UUID uuid = UUID.randomUUID();
        storage.saveCooldown(uuid, "archer", 7_000L);
        database.close();
        database = null;

        SqlStorage reopened = new SqlStorage(open(null));
        reopened.init();
        assertEquals(Map.of("archer", 7_000L), reopened.loadCooldowns(uuid),
                "a local database must persist across restarts, not live in memory");
    }

    private World world() {
        return server.addSimpleWorld("world");
    }
}
