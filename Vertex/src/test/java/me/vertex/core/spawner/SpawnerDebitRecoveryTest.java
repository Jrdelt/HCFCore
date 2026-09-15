package me.vertex.core.spawner;

import me.vertex.core.storage.Database;
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
 * ISS-09: a withdrawal admits its payout to the durable delivery inbox --
 * which can never be undone -- before this plugin's own stack debit ever
 * reaches SQL or a chunk's on-disk PDC. These tests simulate a hard crash in
 * that exact gap by seeding the debit journal exactly as
 * {@code decreaseStack} would have left it, but WITHOUT letting the SQL row
 * itself change (standing in for "the process died before that write, or
 * before the block's own PDC change, ever reached disk"), then loading a
 * fresh {@link SpawnerManager} against the same plugin/storage the way a
 * restart would.
 */
class SpawnerDebitRecoveryTest {
    @TempDir Path dataFolder;
    private ServerMock server;
    private PluginMock plugin;
    private WorldMock world;
    private Database database;
    private SpawnerStorage storage;
    private Location location;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("debit-recovery-world");
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new SpawnerStorage(database);
        storage.init();
        location = new Location(world, 100, 64, 100);
    }

    @AfterEach
    void tearDown() {
        if (database != null) database.close();
        MockBukkit.unmock();
    }

    private SpawnerManager freshManager() {
        SpawnerManager manager = new SpawnerManager(plugin, storage);
        manager.load();
        return manager;
    }

    @Test
    void survivedDebitJournalOverridesAStaleFullSqlRowOnReload() throws Exception {
        // Establish the pre-withdrawal state a real shard would have had:
        // a 5-stack, durably in SQL.
        SpawnerManager before = freshManager();
        before.place(location, EntityType.ZOMBIE, "TestFaction");
        before.increaseStack(location, 4);
        before.awaitWrites();
        assertEquals(5, storage.loadAll().getFirst().stackSize());

        // The crash: a withdrawal of 2 already admitted its payout and wrote
        // this journal entry, but its own SQL save never reached disk --
        // the row above is untouched at 5.
        String locationKey = "debit-recovery-world:100:64:100";
        SpawnerData debited = new SpawnerData(EntityType.ZOMBIE, 3, "TestFaction");
        new SpawnerDebitWal(plugin.getDataFolder()).put(SpawnerDebitWal.Entry.applying(locationKey, 1, debited));

        // The restart: a fresh manager must trust the journal, not the stale row.
        SpawnerManager after = freshManager();
        after.loadSpawnersFromDatabase();
        assertEquals(3, after.get(location).stackSize(),
                "the journaled debit must win over the pre-crash SQL row immediately");

        after.awaitWrites();
        List<SpawnerStorage.StoredSpawner> stored = storage.loadAll();
        assertEquals(1, stored.size());
        assertEquals(3, stored.getFirst().stackSize(), "SQL itself must be corrected, not just memory");
    }

    @Test
    void survivedRemovalJournalOverridesAStaleNonEmptySqlRowOnReload() throws Exception {
        SpawnerManager before = freshManager();
        before.place(location, EntityType.ZOMBIE, "TestFaction");
        before.increaseStack(location, 4);
        before.awaitWrites();
        assertEquals(5, storage.loadAll().getFirst().stackSize());

        // Withdrawing the entire stack already admitted its payout and
        // journaled full removal, but the SQL delete never reached disk.
        String locationKey = "debit-recovery-world:100:64:100";
        new SpawnerDebitWal(plugin.getDataFolder()).put(SpawnerDebitWal.Entry.removing(locationKey, 1));

        SpawnerManager after = freshManager();
        after.loadSpawnersFromDatabase();
        assertNull(after.get(location), "a journaled removal must win over a stale non-empty row immediately");

        after.awaitWrites();
        assertTrue(storage.loadAll().isEmpty(), "SQL itself must be force-deleted, not just untracked in memory");
    }

    @Test
    void repeatedWithdrawalsLeaveOnlyTheFinalStateJournaledAndPersisted() throws Exception {
        SpawnerManager manager = freshManager();
        manager.place(location, EntityType.ZOMBIE, "TestFaction");
        manager.increaseStack(location, 4);
        manager.awaitWrites();

        // Two rapid withdrawals on the same spawner, exactly like a player
        // double-clicking withdraw-one -- both journal before either's SQL
        // write completes.
        manager.decreaseStack(location, 1);
        manager.decreaseStack(location, 1);
        manager.awaitWrites();

        assertEquals(3, storage.loadAll().getFirst().stackSize());
        assertTrue(new SpawnerDebitWal(plugin.getDataFolder()).load().isEmpty(),
                "a normal (non-crash) withdrawal must not leave a permanent journal entry behind");
    }
}
