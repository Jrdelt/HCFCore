package me.vertex.core.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShardScopeTest {

    @AfterEach
    void resetSharedState() {
        // ShardScope is process-wide static config, exactly like ChunkKey --
        // never leave a non-default shard configured for other test classes.
        ShardScope.configure("");
    }

    @Test
    void blankShardIsANoOpPassthrough() {
        ShardScope.configure("");
        assertEquals("world", ShardScope.qualify("world"));
        assertEquals("world", ShardScope.fromStorage("world"));
        assertTrue(ShardScope.isLocalShard("world"));
        assertEquals("world", ShardScope.localWorld("world"));
    }

    @Test
    void configuredShardQualifiesAndRoundTrips() {
        ShardScope.configure("Shard-A");
        String qualified = ShardScope.qualify("world");
        assertEquals("shard-a::world", qualified);
        assertEquals("shard-a", ShardScope.shardId(qualified));
        assertEquals("world", ShardScope.localWorld(qualified));
        assertTrue(ShardScope.isLocalShard(qualified));
    }

    @Test
    void alreadyQualifiedNameIsNotDoubleQualified() {
        ShardScope.configure("shard-a");
        String qualified = ShardScope.qualify("world");
        assertEquals(qualified, ShardScope.qualify(qualified), "re-qualifying must be idempotent");
    }

    @Test
    void aRowFromAnotherShardIsNotLocal() {
        ShardScope.configure("shard-a");
        String foreign = "shard-b::world";
        assertFalse(ShardScope.isLocalShard(foreign),
                "two shards can each have a world named 'world' at the same coordinates -- this must never read as local");
        assertEquals("shard-b", ShardScope.shardId(foreign));
        assertEquals("world", ShardScope.localWorld(foreign));
    }

    @Test
    void unqualifiedLegacyRowMigratesToTheLoadingShardOnRead() {
        ShardScope.configure("shard-a");
        // A row written before shard scoping existed (or by a standalone
        // server) has no separator at all.
        assertEquals("shard-a::world", ShardScope.fromStorage("world"));
        assertTrue(ShardScope.isLocalShard(ShardScope.fromStorage("world")));
    }

    @Test
    void blankWorldIsRejected() {
        ShardScope.configure("shard-a");
        assertThrows(IllegalArgumentException.class, () -> ShardScope.qualify(""));
        assertThrows(IllegalArgumentException.class, () -> ShardScope.qualify(null));
    }
}
