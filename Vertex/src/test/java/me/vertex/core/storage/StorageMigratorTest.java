package me.vertex.core.storage;

import me.vertex.core.blueprint.BlueprintStorage;
import me.vertex.core.collector.ChunkCollectorStorage;
import me.vertex.core.faction.FactionUpgradeStorage;
import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionRelation;
import me.vertex.core.factions.FactionRole;
import me.vertex.core.factions.FactionStorage;
import me.vertex.core.factions.FactionWarp;
import me.vertex.core.claims.ChunkKey;
import me.vertex.core.gc.GcAction;
import me.vertex.core.gc.GcStorage;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        FactionStorage sourceFactions = new FactionStorage(source);
        sourceFactions.init();
        long now = 2_000L;
        int factionId = sourceFactions.createFaction(
                new FactionData(-1, "Raiders", "Test faction", true, false, 10D, 20D, now,
                        new FactionData.Home("world", 4D, 70D, 8D, 90F, 0F)),
                new FactionMember(player, -1, FactionRole.LEADER, "Leader", now));
        sourceFactions.saveClaim(new ChunkKey("world", 3, -2), factionId);
        sourceFactions.saveRelation(factionId, 999, FactionRelation.ENEMY);
        sourceFactions.savePermission(factionId, "member", "doors", true);
        sourceFactions.saveWarp(new FactionWarp(factionId, "farm",
                new FactionData.Home("world", 8D, 70D, 4D, 0F, 0F)));
        sourceFactions.saveInvite(new FactionStorage.Invite(factionId, UUID.randomUUID(), player, 9_000L));
        sourceFactions.savePlayerSettings(player, new FactionStorage.PlayerSettings("FACTION", true, true));

        GcStorage sourceGc = new GcStorage(source);
        sourceGc.init();
        sourceGc.applyDelta(player, player, GcAction.DEPOSIT, 500L, null, 1_000L);
        sourceGc.insertRedeemCode("TESTCODE123", 250L, 1, player, 1_000L, null);

        seedRecoveryTables(player);

        StorageMigrator.Result result = StorageMigrator.migrate(source, target);

        assertEquals(23, result.total(),
                "one row per populated table should have been copied: " + result.rowsPerTable());
        assertEquals(result.rowsPerTable().size(), result.checksums().size());
        assertFalse(result.checksums().get("spawners").isBlank());

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

        GcStorage targetGc = new GcStorage(target);
        assertEquals(500L, targetGc.loadAllBalances().get(player),
                "a GC balance must survive a /vertex storage dialect migration");
        assertTrue(targetGc.codeExists("TESTCODE123"), "a GC redeem code must survive the migration too");

        FactionStorage.LoadedState migratedFactions = new FactionStorage(target).load();
        assertEquals("Raiders", migratedFactions.factions().get(factionId).tag());
        assertEquals(FactionRole.LEADER, migratedFactions.members().get(player).role());
        assertEquals(factionId, migratedFactions.claims().get(new ChunkKey("world", 3, -2)));
        assertEquals(FactionRelation.ENEMY, migratedFactions.relations().get(new FactionStorage.RelationKey(factionId, 999)));
        assertTrue(migratedFactions.permissions().get(new FactionStorage.PermissionKey(factionId, "member", "doors")));
        assertEquals("farm", migratedFactions.warps().values().iterator().next().name());
        assertEquals("FACTION", migratedFactions.players().get(player).chatMode());
        assertEquals(1, countRows(target, "auction_creation_intent_audit"));
        assertEquals(1, countRows(target, "coinflip_creation_intent_audit"));
        assertEquals(1, countRows(target, "trade_pending_payouts"));
        assertEquals(1, countRows(target, "vertex_transfer_handoffs"));
        assertEquals(1, countRows(target, "vertex_transfer_locks"));
    }

    @Test
    void exclusiveMaintenanceRejectsNormalConnectionsUntilReleased() throws Exception {
        new SqlStorage(source).init();
        try (Database.ExclusiveLease lease = source.beginExclusiveMaintenance(1L, TimeUnit.SECONDS)) {
            assertThrows(SQLException.class, source::getConnection);
            try (Connection connection = lease.openConnection()) {
                assertTrue(connection.isValid(1));
            }
        }
        try (Connection connection = source.getConnection()) {
            assertTrue(connection.isValid(1));
        }
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

    private void seedRecoveryTables(UUID player) throws SQLException {
        new me.vertex.core.auction.AuctionStorage(source).init();
        new me.vertex.core.coinflip.CoinflipStorage(source).init();
        new me.vertex.core.trade.TradeStorage(source).init();
        new me.vertex.core.network.NetworkStorage(source).init();
        String transferId = UUID.randomUUID().toString();
        try (Connection connection = source.getConnection()) {
            execute(connection, "INSERT INTO auction_creation_intent_audit "
                    + "(intent_key,seller_uuid,item_summary,price,currency,decision,actor_uuid,actor_name,resolved_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), player.toString(), "DIAMOND x1",
                    50D, "MONEY", "DEBITED", player.toString(), "Admin", 1_000L);
            execute(connection, "INSERT INTO coinflip_creation_intent_audit "
                    + "(intent_key,host_uuid,target_uuid,type,amount,decision,actor_uuid,actor_name,resolved_at) "
                    + "VALUES(?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), player.toString(), null,
                    "EXP", 25D, "NOT_DEBITED", player.toString(), "Admin", 1_001L);
            execute(connection, "INSERT INTO trade_pending_payouts "
                    + "(payout_key,owner_uuid,currency,amount,created_at,state,delivery_started_at) "
                    + "VALUES(?,?,?,?,?,'DELIVERING',?)", "legacy-money:" + player, player.toString(),
                    "MONEY", 125D, 1_002L, 1_003L);
            execute(connection, "INSERT INTO vertex_transfer_handoffs "
                    + "(transfer_id,player_uuid,source_shard,destination_shard,destination_world,x,y,z,yaw,pitch,"
                    + "reason,state,snapshot,created_at,updated_at,error) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    transferId, player.toString(), "hub", "factions", "world", 0D, 64D, 0D, 0F, 0F,
                    "TEST", "PREPARED", new byte[] {1}, 1_004L, 1_004L, null);
            execute(connection, "INSERT INTO vertex_transfer_locks "
                    + "(player_uuid,transfer_kind,reference_id,created_at) VALUES(?,?,?,?)",
                    player.toString(), "HANDOFF", transferId, 1_004L);
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private static int countRows(Database database, String table) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table); ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
