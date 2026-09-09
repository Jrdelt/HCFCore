package me.vertex.core.storage;

import me.vertex.core.blueprint.BlueprintStorage;
import me.vertex.core.collector.ChunkCollectorStorage;
import me.vertex.core.faction.FactionUpgradeStorage;
import me.vertex.core.spawner.SpawnerData;
import me.vertex.core.spawner.SpawnerStorage;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the copy that runs when an operator switches backends with
 * {@code /vertex storage}. Both ends are local databases here (two
 * separate files), which exercises every table, the wipe-then-copy
 * ordering, and the value round-tripping -- the parts that would silently
 * lose data. A real MySQL endpoint isn't available in tests, but the copy
 * itself is dialect-independent: it reads and writes through plain JDBC.
 */
class StorageMigratorTest {

    @TempDir
    Path sourceFolder;
    @TempDir
    Path targetFolder;

    private ServerMock server;
    private Database source;
    private Database target;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        YamlConfiguration config = new YamlConfiguration();
        source = new Database(config, sourceFolder.toFile(), Database.Dialect.SQLITE);
        target = new Database(config, targetFolder.toFile(), Database.Dialect.SQLITE);
    }

    @AfterEach
    void tearDown() {
        source.close();
        target.close();
        MockBukkit.unmock();
    }

    @Test
    void copiesEveryTableToAnEmptyTarget() throws SQLException {
        UUID player = UUID.randomUUID();
        Location location = new Location(server.addSimpleWorld("world"), 4, 70, 8);

        SqlStorage sourceStorage = new SqlStorage(source);
        sourceStorage.init();
        sourceStorage.saveCooldown(player, "archer", 1_234L);
        sourceStorage.saveAbilityCooldown(player, "leap", 5_678L);
        sourceStorage.saveLocale(player, "de_de");

        SpawnerStorage sourceSpawners = new SpawnerStorage(source);
        sourceSpawners.init();
        sourceSpawners.save(location, new SpawnerData(EntityType.BLAZE,
                List.of(101L, 102L, 103L, 104L, 105L, 106L, 107L), "Raiders"));

        ChunkCollectorStorage sourceCollectors = new ChunkCollectorStorage(source);
        sourceCollectors.init();
        sourceCollectors.save(location, "Raiders", player.toString());

        BlueprintStorage sourceBlueprints = new BlueprintStorage(source);
        sourceBlueprints.init();
        sourceBlueprints.insert(location, "outpost", player.toString(), "Raiders", 999L);

        FactionUpgradeStorage sourceUpgrades = new FactionUpgradeStorage(source);
        sourceUpgrades.init();
        sourceUpgrades.save(42, "spawner-rate", 3);

        StorageMigrator.Result result = StorageMigrator.migrate(source, target);

        assertEquals(7, result.total(), "one row per populated table should have been copied");

        SqlStorage migrated = new SqlStorage(target);
        assertEquals(Map.of("archer", 1_234L), migrated.loadCooldowns(player));
        assertEquals(Map.of("leap", 5_678L), migrated.loadAbilityCooldowns(player));
        assertEquals("de_de", migrated.loadLocale(player));

        List<SpawnerStorage.StoredSpawner> spawners = new SpawnerStorage(target).loadAll();
        assertEquals(1, spawners.size());
        assertEquals(EntityType.BLAZE, spawners.get(0).mobType());
        assertEquals(7, spawners.get(0).stackSize());
        assertEquals("Raiders", spawners.get(0).ownerFactionTag());
        assertEquals(List.of(101L, 102L, 103L, 104L, 105L, 106L, 107L), spawners.get(0).placedAtMillis());

        List<ChunkCollectorStorage.StoredCollector> collectors = new ChunkCollectorStorage(target).loadAll();
        assertEquals(1, collectors.size());
        assertEquals(player.toString(), collectors.get(0).ownerUuid());

        List<BlueprintStorage.StoredBuild> builds = new BlueprintStorage(target).loadAll();
        assertEquals(1, builds.size());
        assertEquals("outpost", builds.get(0).template());
        assertEquals(999L, builds.get(0).startedAt());
        assertTrue(builds.get(0).id() > 0, "the target assigns its own generated id");

        List<FactionUpgradeStorage.StoredLevel> upgrades = new FactionUpgradeStorage(target).loadAll();
        assertEquals(List.of(new FactionUpgradeStorage.StoredLevel(42, "spawner-rate", 3)), upgrades);
    }

    @Test
    void rerunningReplacesRatherThanDuplicating() throws SQLException {
        UUID player = UUID.randomUUID();
        SqlStorage sourceStorage = new SqlStorage(source);
        sourceStorage.init();
        sourceStorage.saveCooldown(player, "archer", 1L);

        StorageMigrator.migrate(source, target);
        StorageMigrator.migrate(source, target);

        assertEquals(1, StorageMigrator.countRows(target),
                "a second migration must not stack duplicate rows in the target");
    }

    @Test
    void countsExistingTargetRowsSoTheCommandCanWarn() throws SQLException {
        SqlStorage targetStorage = new SqlStorage(target);
        targetStorage.init();
        targetStorage.saveCooldown(UUID.randomUUID(), "archer", 1L);
        targetStorage.saveCooldown(UUID.randomUUID(), "bard", 2L);

        assertEquals(2, StorageMigrator.countRows(target));
    }

    @Test
    void writingStorageTypeKeepsTheConfigsComments() throws Exception {
        File config = sourceFolder.resolve("config.yml").toFile();
        Files.writeString(config.toPath(), """
                # ============================================
                # STORAGE
                # ============================================
                storage:
                  # local or mysql
                  type: local

                mysql:
                  host: localhost
                """, StandardCharsets.UTF_8);

        StorageMigrator.writeStorageType(config, "mysql");

        String updated = Files.readString(config.toPath(), StandardCharsets.UTF_8);
        assertTrue(updated.contains("  type: mysql"), "the value should have been switched");
        assertTrue(updated.contains("# local or mysql"), "inline comments must survive the edit");
        assertTrue(updated.contains("# STORAGE"), "section comments must survive the edit");
        assertTrue(updated.contains("host: localhost"), "unrelated settings must be untouched");
    }

    @Test
    void writingStorageTypeAddsTheBlockWhenAnOlderConfigLacksIt() throws Exception {
        File config = sourceFolder.resolve("legacy.yml").toFile();
        Files.writeString(config.toPath(), "mysql:\n  host: localhost\n", StandardCharsets.UTF_8);

        StorageMigrator.writeStorageType(config, "mysql");

        String updated = Files.readString(config.toPath(), StandardCharsets.UTF_8);
        assertTrue(updated.contains("storage:"), "a missing storage block should be appended");
        assertTrue(updated.contains("type: mysql"));
        assertTrue(updated.contains("host: localhost"));
    }
}
