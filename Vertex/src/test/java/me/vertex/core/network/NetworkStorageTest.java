package me.vertex.core.network;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Durable network state and the one-active-transfer invariant. */
class NetworkStorageTest {
    @TempDir Path dataFolder;
    private Database database;

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void persistsShardEventsLocationsAndCooldowns() throws Exception {
        NetworkStorage storage = open();
        storage.heartbeat("star", "base", ShardState.ONLINE, 100, 12, 0L, "test");
        assertEquals(12, storage.shard("star").orElseThrow().currentPlayers());
        storage.setShardState("star", ShardState.DRAINING, 55_000L, "admin");
        assertEquals(ShardState.DRAINING, storage.shard("star").orElseThrow().state());
        long event = storage.publish("star", "factions", "changed");
        assertEquals(event, storage.eventsAfter(0L, 10).getFirst().id());

        NetworkLocation location = new NetworkLocation("spawn", "world", 1D, 65D, 2D, 3F, 4F);
        storage.saveLocation("spawn", "primary", location, "Spawn");
        assertEquals(location, storage.location("spawn", "primary").orElseThrow().location());
        UUID player = UUID.randomUUID();
        storage.saveCooldown(player, "rtp", 123_456L);
        assertEquals(123_456L, storage.cooldown(player, "rtp"));
    }

    @Test
    void heartbeatCannotSilentlyClearCrashRecovery() throws Exception {
        NetworkStorage storage = open();
        assertEquals(ShardState.ONLINE,
                storage.heartbeat("star", "base", ShardState.ONLINE, 100, 4, 0L, "startup"));
        storage.setShardState("star", ShardState.CRASH_RECOVERY, 0L, "watchdog");

        assertEquals(ShardState.CRASH_RECOVERY,
                storage.heartbeat("star", "base", ShardState.ONLINE, 100, 4, 0L, "stale-process"));
        assertEquals(ShardState.CRASH_RECOVERY, storage.shard("star").orElseThrow().state());

        storage.setShardState("star", ShardState.ONLINE, 0L, "admin");
        assertEquals(ShardState.ONLINE,
                storage.heartbeat("star", "base", ShardState.ONLINE, 100, 4, 0L, "heartbeat"));
    }

    @Test
    void onlyOneQueueOrHandoffCanExistForAPlayer() throws Exception {
        NetworkStorage storage = open();
        UUID player = UUID.randomUUID();
        long now = 1_000L;
        NetworkLocation comet = new NetworkLocation("comet", "world", 4D, 70D, 5D, 0F, 0F);
        NetworkStorage.Handoff first = handoff("00000000-0000-0000-0000-000000000001",
                player, comet, now);
        NetworkStorage.Handoff second = handoff("00000000-0000-0000-0000-000000000002",
                player, comet, now + 1L);

        assertTrue(storage.createHandoff(first));
        assertFalse(storage.createHandoff(second));
        assertFalse(storage.queue(player, "star", comet, "rtp", now + 60_000L));
        assertEquals(first.id(), storage.unresolved(player).orElseThrow().id());
        assertTrue(storage.transition(first.id(), "PREPARED", "ACKED", null));

        assertTrue(storage.queue(player, "star", comet, "rtp", now + 60_000L));
        assertFalse(storage.createHandoff(second));
        storage.deleteQueue(player);
        assertTrue(storage.createHandoff(second));
        assertTrue(storage.resolveHandoff(second.id(), "ABORTED", "test complete"));
        assertTrue(storage.unresolved(player).isEmpty());
    }

    @Test
    void pruneRemovesOnlyOldTerminalHandoffPayloads() throws Exception {
        NetworkStorage storage = open();
        UUID completedPlayer = UUID.randomUUID();
        UUID unresolvedPlayer = UUID.randomUUID();
        NetworkLocation target = new NetworkLocation("comet", "world", 4D, 70D, 5D, 0F, 0F);
        NetworkStorage.Handoff completed = handoff("00000000-0000-0000-0000-000000000011",
                completedPlayer, target, 1_000L);
        NetworkStorage.Handoff unresolved = handoff("00000000-0000-0000-0000-000000000012",
                unresolvedPlayer, target, 1_000L);
        assertTrue(storage.createHandoff(completed));
        assertTrue(storage.createHandoff(unresolved));
        assertTrue(storage.transition(completed.id(), "PREPARED", "ACKED", null));

        storage.prune(Long.MAX_VALUE, Long.MAX_VALUE);

        try (var connection = database.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM vertex_transfer_handoffs WHERE transfer_id=?")) {
            statement.setString(1, completed.id());
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
            }
            statement.setString(1, unresolved.id());
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
        }
        assertEquals(unresolved.id(), storage.unresolved(unresolvedPlayer).orElseThrow().id());
    }

    private NetworkStorage open() throws Exception {
        database = new Database(new YamlConfiguration(), dataFolder.toFile());
        NetworkStorage storage = new NetworkStorage(database);
        storage.init();
        return storage;
    }

    private static NetworkStorage.Handoff handoff(String id, UUID player,
            NetworkLocation destination, long now) {
        return new NetworkStorage.Handoff(id, player, "star", destination, "test",
                "PREPARED", new byte[]{1, 2, 3}, now, now, null);
    }
}
