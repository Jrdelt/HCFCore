package me.vertex.core.claims;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the "persist the deadline, catch up on restart" contract Raid
 * Claim expiration relies on -- the same shape {@code FTopManagerTest}/
 * {@code HotZoneManagerTest} would cover for their own schedules, adapted
 * to a per-chunk composite key instead of a singleton row.
 */
class RaidClaimManagerTest {

    @TempDir
    Path dataFolder;

    private PluginMock plugin;
    private ClaimStorage storage;
    private RaidClaimManager manager;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Database database = new Database(new YamlConfiguration(), dataFolder.toFile());
        storage = new ClaimStorage(database);
        storage.init();
        manager = new RaidClaimManager(plugin, storage);
        manager.load();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void trackedChunkKeepsCountingDownInRealTime() {
        ChunkKey chunk = new ChunkKey("world", 1, 1);
        manager.loadState();
        manager.track(1, chunk);
        assertTrue(manager.isTracked(chunk));
        assertTrue(manager.remainingSeconds(chunk) > 0);
    }

    @Test
    void untrackStopsTrackingAndRemovesTheDurableRow() throws Exception {
        ChunkKey chunk = new ChunkKey("world", 2, 2);
        manager.loadState();
        manager.track(1, chunk);
        manager.untrack(chunk);
        assertFalse(manager.isTracked(chunk));
        assertTrue(storage.loadRaidClaims().isEmpty());
    }

    @Test
    void restartCatchUpUnclaimsAlreadyExpiredChunksButKeepsUnexpiredOnes() throws Exception {
        long now = System.currentTimeMillis();
        // Row that already expired while the server was "down".
        storage.upsertRaidClaim("world", 5, 5, 1, now - 10_000L);
        // Row still well within its window.
        storage.upsertRaidClaim("world", 6, 6, 1, now + 3_600_000L);

        manager.loadState();
        assertEquals(2, storage.loadRaidClaims().size(), "both rows should have loaded before recovery runs");

        manager.recoverState();

        assertFalse(manager.isTracked(new ChunkKey("world", 5, 5)), "the overdue chunk should have expired immediately on startup");
        assertTrue(manager.isTracked(new ChunkKey("world", 6, 6)), "the chunk still inside its window must not be reset or dropped");
        assertEquals(1, storage.loadRaidClaims().size(), "only the expired row should have been removed from storage");
    }

    @Test
    void unexpiredChunkResumesWithItsOriginalDeadlineNotAFreshOne() throws Exception {
        long now = System.currentTimeMillis();
        long originalDeadline = now + 1_800_000L; // 30 minutes left
        storage.upsertRaidClaim("world", 7, 7, 1, originalDeadline);

        manager.loadState();
        manager.recoverState();

        long remaining = manager.remainingSeconds(new ChunkKey("world", 7, 7));
        // Should be close to 30 minutes remaining, not the full configured
        // duration (7 hours / 25200s by default) -- proves the deadline
        // survived the restart instead of being reset.
        assertTrue(remaining <= 1_800L && remaining > 1_700L,
                "expected roughly 1800s remaining, was " + remaining);
    }
}
