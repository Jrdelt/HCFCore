package me.vertex.core.claims;

import me.vertex.core.spawner.SpawnerManager;
import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link BaseClaimManager}'s connected-region bookkeeping without
 * touching FactionsUUID's own Board/Factions singletons (not initialized
 * in a unit test) -- {@link BaseClaimManager#tryConnect} deliberately never
 * calls {@code FactionsHook} itself (only the anchor-seeding BFS does, for
 * an already-claimed neighbor probe), so it's exercised here purely against
 * {@link ClaimStorage}-seeded region state, the same way production code
 * rebuilds it from storage in {@link BaseClaimManager#loadState()}.
 */
class BaseClaimManagerTest {

    @TempDir
    Path dataFolder;

    private org.mockbukkit.mockbukkit.ServerMock server;
    private PluginMock plugin;
    private ClaimStorage storage;
    private BaseClaimManager manager;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Database database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new ClaimStorage(database);
        storage.init();
        SpawnerManager spawners = new SpawnerManager(plugin, null);
        spawners.load();
        manager = new BaseClaimManager(plugin, storage, spawners);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void withMaxChunksPerRegion(int max) throws Exception {
        File file = new File(plugin.getDataFolder(), "claims.yml");
        Files.writeString(file.toPath(), "base-claim:\n  max-chunks-per-region: " + max + "\n",
                StandardCharsets.UTF_8);
        manager.load();
    }

    @Test
    void freshFactionStartsWithOneUnlockedSlotAndNoAnchors() throws Exception {
        withMaxChunksPerRegion(2000);
        manager.loadState();
        assertEquals(1, manager.unlockedSlots(42));
        assertEquals(0, manager.anchoredCount(42));
    }

    @Test
    void purchaseFailsCleanlyWithoutAnEconomyPluginRegistered() throws Exception {
        withMaxChunksPerRegion(2000);
        manager.loadState();
        BaseClaimManager.PurchaseResult result = manager.purchaseNextSlot(server.addPlayer(), 1);
        assertEquals(BaseClaimManager.PurchaseResult.NO_ECONOMY, result);
        assertEquals(1, manager.unlockedSlots(1), "a failed purchase must not unlock a slot");
    }

    @Test
    void connectingChunkJoinsAnAdjacentRegionOfTheSameFaction() throws Exception {
        withMaxChunksPerRegion(2000);
        int factionId = 7;
        storage.insertBaseClaim(factionId, 1, "world", 0, 0, System.currentTimeMillis());
        storage.insertRegionChunk(factionId, 1, "world", 0, 0);
        manager.loadState();

        ChunkKey neighbor = new ChunkKey("world", 1, 0);
        BaseClaimManager.ConnectResult result = manager.tryConnect(factionId, neighbor);

        assertEquals(BaseClaimManager.ConnectResult.JOINED, result);
        assertTrue(storage.loadRegionChunks().stream()
                .anyMatch(row -> row.chunkX() == 1 && row.chunkZ() == 0 && row.factionId() == factionId));
    }

    @Test
    void chunkNotTouchingAnyRegionStaysUnconnected() throws Exception {
        withMaxChunksPerRegion(2000);
        int factionId = 7;
        storage.insertBaseClaim(factionId, 1, "world", 0, 0, System.currentTimeMillis());
        storage.insertRegionChunk(factionId, 1, "world", 0, 0);
        manager.loadState();

        // (5,5) is nowhere near (0,0) -- must not silently join.
        BaseClaimManager.ConnectResult result = manager.tryConnect(factionId, new ChunkKey("world", 5, 5));
        assertEquals(BaseClaimManager.ConnectResult.NOT_APPLICABLE, result);
    }

    @Test
    void anotherFactionsAdjacentClaimNeverJoinsTheRegion() throws Exception {
        withMaxChunksPerRegion(2000);
        storage.insertBaseClaim(1, 1, "world", 0, 0, System.currentTimeMillis());
        storage.insertRegionChunk(1, 1, "world", 0, 0);
        manager.loadState();

        // Faction 2, not faction 1 -- the adjacency check must be faction-scoped.
        BaseClaimManager.ConnectResult result = manager.tryConnect(2, new ChunkKey("world", 1, 0));
        assertEquals(BaseClaimManager.ConnectResult.NOT_APPLICABLE, result);
    }

    @Test
    void regionStopsGrowingOncePerRegionCapIsReached() throws Exception {
        withMaxChunksPerRegion(1); // the anchor alone already fills the region
        int factionId = 3;
        storage.insertBaseClaim(factionId, 1, "world", 0, 0, System.currentTimeMillis());
        storage.insertRegionChunk(factionId, 1, "world", 0, 0);
        manager.loadState();

        BaseClaimManager.ConnectResult result = manager.tryConnect(factionId, new ChunkKey("world", 1, 0));
        assertEquals(BaseClaimManager.ConnectResult.REGION_FULL, result,
                "a full region must leave the new chunk as a Raid Claim rather than silently absorbing it");
        assertFalse(storage.loadRegionChunks().stream().anyMatch(row -> row.chunkX() == 1 && row.chunkZ() == 0));
    }

    @Test
    void aChunkAlreadyAdmittedBeforeCountsAsJoinedEvenIfTheRegionIsOtherwiseFull() throws Exception {
        withMaxChunksPerRegion(1);
        int factionId = 9;
        storage.insertBaseClaim(factionId, 1, "world", 0, 0, System.currentTimeMillis());
        storage.insertRegionChunk(factionId, 1, "world", 0, 0);
        // Simulate a chunk that was admitted earlier (e.g. before the region
        // hit its cap), then unclaimed and now being reclaimed -- membership
        // must survive the unclaim per spec, so reclaiming it must not be
        // blocked by the cap even though the region is otherwise full.
        storage.insertRegionChunk(factionId, 1, "world", 1, 0);
        manager.loadState();

        BaseClaimManager.ConnectResult result = manager.tryConnect(factionId, new ChunkKey("world", 1, 0));
        assertEquals(BaseClaimManager.ConnectResult.JOINED, result);
    }

    @Test
    void removingAnAnchorDeletesItsRegionAndFreesEveryMemberChunk() throws Exception {
        withMaxChunksPerRegion(2000);
        int factionId = 4;
        storage.insertBaseClaim(factionId, 1, "world", 0, 0, System.currentTimeMillis());
        storage.insertRegionChunk(factionId, 1, "world", 0, 0);
        storage.insertRegionChunk(factionId, 1, "world", 1, 0);
        manager.loadState();

        BaseClaimManager.RemoveResult result = manager.removeAnchor(factionId, 1);

        assertEquals(BaseClaimManager.RemoveResult.OK, result);
        assertTrue(storage.loadBaseClaims().isEmpty());
        assertTrue(storage.loadRegionChunks().isEmpty());
        assertEquals(0, manager.anchoredCount(factionId));
        // Both chunks should now be free to join a *different* Base Claim / be Raid Claims.
        assertEquals(BaseClaimManager.ConnectResult.NOT_APPLICABLE,
                manager.tryConnect(factionId, new ChunkKey("world", 2, 0)));
    }

    @Test
    void removingAnUnknownSlotReportsNotFound() throws Exception {
        withMaxChunksPerRegion(2000);
        manager.loadState();
        assertEquals(BaseClaimManager.RemoveResult.NOT_FOUND, manager.removeAnchor(1, 1));
    }

    @Test
    void nextAvailableSlotReusesAFreedLowerSlotInsteadOfCollidingWithAHigherOne() throws Exception {
        withMaxChunksPerRegion(2000);
        int factionId = 5;
        storage.insertPurchasedSlot(factionId, 2, System.currentTimeMillis());
        storage.insertPurchasedSlot(factionId, 3, System.currentTimeMillis());
        storage.insertBaseClaim(factionId, 1, "world", 0, 0, System.currentTimeMillis());
        storage.insertRegionChunk(factionId, 1, "world", 0, 0);
        storage.insertBaseClaim(factionId, 2, "world", 10, 0, System.currentTimeMillis());
        storage.insertRegionChunk(factionId, 2, "world", 10, 0);
        storage.insertBaseClaim(factionId, 3, "world", 20, 0, System.currentTimeMillis());
        storage.insertRegionChunk(factionId, 3, "world", 20, 0);
        manager.loadState();
        assertEquals(3, manager.anchoredCount(factionId));
        assertEquals(3, manager.unlockedSlots(factionId));

        // Free the lowest slot while a higher one (3) stays anchored -- before
        // the fix, the next anchor always recomputed anchoredCount()+1 (=3
        // here), which collides with the still-live slot 3 instead of
        // reoccupying the slot that was actually freed.
        assertEquals(BaseClaimManager.RemoveResult.OK, manager.removeAnchor(factionId, 1));
        assertEquals(2, manager.anchoredCount(factionId));

        assertEquals(1, manager.nextAvailableSlot(factionId),
                "the freed slot 1 must be reused rather than colliding with the still-anchored slot 3");
    }
}
