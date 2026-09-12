package me.vertex.core.chunkbuster;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.storage.Database;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ChunkBusterManager} without booting the native faction
 * {@code Board} singleton or a real {@code CombatManager} -- every such
 * lookup is a plain lambda here, standing in for what {@code
 * FactionsHook}/{@code RallyManager}/{@code CombatManager} report in
 * production (see the class doc on {@code ChunkBusterManager} for why,
 * mirroring {@code ExplosionProtectionListenerTest}'s approach).
 */
class ChunkBusterManagerTest {

    @TempDir
    Path dataFolder;

    private ServerMock server;
    private WorldMock world;
    private PluginMock plugin;
    private Database database;
    private ChunkBusterStorage storage;

    /** Mutable per-test wiring for the injected lookups, read by the lambdas passed into the manager. */
    private int claimFactionId = FactionsHook.NO_FACTION;
    private String claimTag = null;
    private int playerFactionId = FactionsHook.NO_FACTION;
    private String playerRole = "member";
    private boolean combatTagged = false;

    private ChunkBusterManager manager;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("chunkbuster-test-world");

        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new ChunkBusterStorage(database);
        storage.init();

        manager = new ChunkBusterManager(plugin, storage, new me.vertex.core.spawner.SpawnerManager(plugin,
                new me.vertex.core.spawner.SpawnerStorage(database)),
                location -> claimFactionId,
                location -> claimTag,
                player -> playerFactionId,
                player -> playerRole,
                uuid -> combatTagged);
        manager.load();
        manager.loadState();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Location locationAt(int x, int y, int z) {
        return new Location(world, x, y, z);
    }

    // ---- Protected blocks ----

    @Test
    void bedrockIsAlwaysProtectedRegardlessOfConfig() {
        assertTrue(manager.isProtected(Material.BEDROCK));
    }

    // ---- Performance framework wiring ----

    @Test
    void loadRegistersTheBatchRemovalTaskWithTheWiredPerformanceManager() throws Exception {
        me.vertex.core.performance.PerformanceManager performance =
                new me.vertex.core.performance.PerformanceManager(plugin);
        java.io.File file = new java.io.File(plugin.getDataFolder(), "performance.yml");
        java.nio.file.Files.writeString(file.toPath(), "monitoring-level: BASIC\n",
                java.nio.charset.StandardCharsets.UTF_8);
        performance.load();

        manager.setPerformanceManager(performance);
        manager.load();

        var tasks = performance.scheduledTasks();
        assertTrue(tasks.stream().anyMatch(task -> task.label().equals("chunkbuster.batch-removal")
                && task.intervalTicks() == 1L), "chunkbuster.yml's default period-ticks (1) must be reflected");
    }

    @Test
    void configuredProtectedBlocksAreProtected() {
        // BARRIER ships in the default chunkbuster.yml protected-blocks list.
        assertTrue(manager.isProtected(Material.BARRIER));
    }

    @Test
    void ordinaryBlocksAreNotProtected() {
        assertFalse(manager.isProtected(Material.DIRT));
    }

    // ---- Zone / claim / combat / role-permission validation ----

    @Test
    void wildernessNeedsNoFactionPermissionAtAll() {
        claimFactionId = FactionsHook.NO_FACTION;
        playerFactionId = FactionsHook.NO_FACTION; // factionless player
        playerRole = "recruit"; // would fail a role check if one were run
        ChunkBusterManager.UseResult result = manager.validate(server.addPlayer(), locationAt(0, 64, 0), ChunkBusterType.FULL);
        assertEquals(ChunkBusterManager.UseResult.OK, result);
    }

    @Test
    void ownClaimWithAnAllowedRolePasses() {
        claimFactionId = 5;
        playerFactionId = 5;
        playerRole = "admin"; // default-allowed
        ChunkBusterManager.UseResult result = manager.validate(server.addPlayer(), locationAt(0, 64, 0), ChunkBusterType.FULL);
        assertEquals(ChunkBusterManager.UseResult.OK, result);
    }

    @Test
    void ownClaimWithADeniedRoleIsBlocked() {
        claimFactionId = 5;
        playerFactionId = 5;
        playerRole = "member"; // default-denied
        ChunkBusterManager.UseResult result = manager.validate(server.addPlayer(), locationAt(0, 64, 0), ChunkBusterType.FULL);
        assertEquals(ChunkBusterManager.UseResult.NO_ROLE_PERMISSION, result);
    }

    @Test
    void factionPermissionGuiCheckOverridesLegacyChunkBusterRoleRows() {
        ChunkBusterManager guiPermissionManager = new ChunkBusterManager(plugin, storage,
                new me.vertex.core.spawner.SpawnerManager(plugin, new me.vertex.core.spawner.SpawnerStorage(database)),
                location -> claimFactionId, location -> claimTag, player -> playerFactionId, player -> playerRole,
                uuid -> combatTagged, player -> false);
        guiPermissionManager.load();
        guiPermissionManager.loadState();

        claimFactionId = 5;
        playerFactionId = 5;
        playerRole = "admin"; // The old Chunk Buster defaults would allow this role.
        assertEquals(ChunkBusterManager.UseResult.NO_ROLE_PERMISSION,
                guiPermissionManager.validate(server.addPlayer(), locationAt(0, 64, 0), ChunkBusterType.FULL),
                "The /f permissions action must be authoritative when wired in production.");
    }

    @Test
    void everyChunkBusterIsAGlowingMagmaBlock() {
        ItemStack item = manager.createItem(ChunkBusterType.FULL);
        assertEquals(Material.MAGMA_BLOCK, item.getType());
        assertTrue(item.getItemMeta().hasEnchants(), "Chunk Busters should visibly glow in the Raiding Materials shop.");
    }

    @Test
    void anotherFactionsClaimIsAlwaysBlocked() {
        claimFactionId = 5;
        playerFactionId = 6; // different faction
        playerRole = "admin";
        ChunkBusterManager.UseResult result = manager.validate(server.addPlayer(), locationAt(0, 64, 0), ChunkBusterType.FULL);
        assertEquals(ChunkBusterManager.UseResult.NOT_YOUR_CLAIM, result);
    }

    @Test
    void factionlessPlayerCannotUseInAnyFactionsClaim() {
        claimFactionId = 5;
        playerFactionId = FactionsHook.NO_FACTION;
        ChunkBusterManager.UseResult result = manager.validate(server.addPlayer(), locationAt(0, 64, 0), ChunkBusterType.FULL);
        assertEquals(ChunkBusterManager.UseResult.NOT_YOUR_CLAIM, result);
    }

    @Test
    void safeZoneIsBlockedEvenOnThePlayersOwnFaction() {
        claimTag = "SafeZone";
        claimFactionId = 5;
        playerFactionId = 5;
        playerRole = "admin";
        ChunkBusterManager.UseResult result = manager.validate(server.addPlayer(), locationAt(0, 64, 0), ChunkBusterType.FULL);
        assertEquals(ChunkBusterManager.UseResult.BLOCKED_ZONE, result);
    }

    @Test
    void warZoneIsBlockedTooCaseInsensitively() {
        claimTag = "WARZONE";
        ChunkBusterManager.UseResult result = manager.validate(server.addPlayer(), locationAt(0, 64, 0), ChunkBusterType.FULL);
        assertEquals(ChunkBusterManager.UseResult.BLOCKED_ZONE, result);
    }

    @Test
    void combatTaggedIsBlockedEvenInWilderness() {
        combatTagged = true;
        claimFactionId = FactionsHook.NO_FACTION;
        ChunkBusterManager.UseResult result = manager.validate(server.addPlayer(), locationAt(0, 64, 0), ChunkBusterType.FULL);
        assertEquals(ChunkBusterManager.UseResult.IN_COMBAT, result);
    }

    // ---- Role permission overrides ----

    @Test
    void factionLeaderCanOverrideTheDefaultRolePermission() {
        assertFalse(manager.rolePermission(9, "member"), "member is denied by default");
        assertTrue(manager.setRolePermission(9, "member", true));
        assertTrue(manager.rolePermission(9, "member"), "override should take effect immediately");
    }

    @Test
    void unknownRoleIsRejected() {
        assertFalse(manager.setRolePermission(9, "owner", true));
    }

    @Test
    void rolePermissionOverridesSurviveAStorageReload() throws Exception {
        manager.setRolePermission(3, "recruit", true);
        manager.loadState();
        assertTrue(manager.rolePermission(3, "recruit"));
    }

    // ---- Restart recovery ----

    @Test
    void recoverAbandonedOperationsDeletesLeftoverRowsWithoutResuming() throws Exception {
        storage.insertOperation("world", 1, 1, ChunkBusterType.FULL.name(), System.currentTimeMillis(),
                ChunkBusterStorage.STATUS_IN_PROGRESS);
        assertEquals(1, storage.loadOperations().size());

        manager.recoverAbandonedOperations();

        assertTrue(storage.loadOperations().isEmpty(), "the leftover lock row must be cleared, not resumed");
    }

    @Test
    void recoverAbandonedOperationsIsANoOpWhenNothingWasLeftBehind() {
        manager.recoverAbandonedOperations();
        // Must not throw, and must not fabricate any row.
    }

    // ---- Persisted lock + place/break enforcement, end to end ----

    @Test
    void ownershipChangeStopsBeforeTheNextBatch() throws Exception {
        PlayerMock player=server.addPlayer();Location target=locationAt(5,64,5);
        world.getBlockAt(5,63,5).setType(Material.STONE);
        player.getInventory().setItemInMainHand(manager.createItem(ChunkBusterType.SINGLE_COLUMN));
        assertEquals(ChunkBusterManager.UseResult.OK,manager.confirmAndExecute(player,target,ChunkBusterType.SINGLE_COLUMN));
        claimFactionId=99;claimTag="Enemy";
        server.getScheduler().performTicks(2);
        assertEquals(Material.STONE,world.getBlockAt(5,63,5).getType());
        assertFalse(manager.isLocked(target));
        assertEquals("STOPPED",storage.loadOperations().getFirst().status());
    }

    @Test
    void confirmAndExecuteLocksTheAreaThenClearsItAndReleasesTheLock() throws Exception {
        PlayerMock player = server.addPlayer();
        Location target = locationAt(5, 64, 5);
        player.getInventory().setItemInMainHand(manager.createItem(ChunkBusterType.SINGLE_COLUMN));
        claimFactionId = FactionsHook.NO_FACTION; // Wilderness: simplest path, no permission check.

        assertFalse(manager.isLocked(target), "must not be locked before anything starts");

        ChunkBusterManager.UseResult result = manager.confirmAndExecute(player, target, ChunkBusterType.SINGLE_COLUMN);
        assertEquals(ChunkBusterManager.UseResult.OK, result);
        assertTrue(manager.isLocked(target), "the chunk must be locked the instant processing starts");
        assertEquals(1, storage.loadOperations().size(), "a durable lock row must exist while processing is in flight");

        // The item was consumed immediately, per spec.
        ItemStack heldAfter = player.getInventory().getItemInMainHand();
        assertNotEquals(ChunkBusterType.SINGLE_COLUMN, manager.typeOf(heldAfter));

        BukkitSchedulerMock scheduler = (BukkitSchedulerMock) server.getScheduler();
        for (int tick = 0; tick < 50 && manager.isLocked(target); tick++) {
            scheduler.performOneTick();
        }

        assertFalse(manager.isLocked(target), "the lock must be released once the batch finishes");
        assertTrue(storage.loadOperations().isEmpty(), "the durable lock row must be cleared on completion");
        assertEquals(Material.AIR, world.getBlockAt(target).getType(), "the single column must have been cleared");
    }

    @Test
    void placingOrBreakingInsideALockedAreaIsRejectedByIsLocked() throws Exception {
        PlayerMock player = server.addPlayer();
        Location target = locationAt(5, 64, 5);
        player.getInventory().setItemInMainHand(manager.createItem(ChunkBusterType.FULL));
        claimFactionId = FactionsHook.NO_FACTION;

        manager.confirmAndExecute(player, target, ChunkBusterType.FULL);

        assertTrue(manager.isLocked(locationAt(0, 64, 0)), "every block in the same chunk is locked, not just the placement point");
        assertFalse(manager.isLocked(locationAt(16, 64, 0)), "a neighboring chunk must not be affected");
    }

    @Test
    void confirmAndExecuteFailsCleanlyWhenTheItemIsNoLongerHeld() throws Exception {
        PlayerMock player = server.addPlayer();
        // Main hand is empty.
        ChunkBusterManager.UseResult result = manager.confirmAndExecute(player, locationAt(0, 64, 0), ChunkBusterType.FULL);
        assertEquals(ChunkBusterManager.UseResult.ITEM_MISSING, result);
        assertTrue(storage.loadOperations().isEmpty(), "nothing should have been persisted");
    }
}
