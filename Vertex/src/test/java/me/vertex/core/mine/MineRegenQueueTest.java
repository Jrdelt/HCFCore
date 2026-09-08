package me.vertex.core.mine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineRegenQueueTest {

    private static MineRegenQueue.Key key(int x) {
        return new MineRegenQueue.Key("stonewake", x, 64, 0);
    }

    @Test
    void returnsNothingBeforeAnythingIsDue() {
        MineRegenQueue queue = new MineRegenQueue();
        queue.schedule(key(1), 1_000L);

        assertEquals(List.of(), queue.drainDue(999L, 100));
        assertEquals(1, queue.size(), "a location not yet due stays queued");
    }

    @Test
    void returnsLocationsOldestDueFirst() {
        MineRegenQueue queue = new MineRegenQueue();
        queue.schedule(key(3), 3_000L);
        queue.schedule(key(1), 1_000L);
        queue.schedule(key(2), 2_000L);

        assertEquals(List.of(key(1), key(2), key(3)), queue.drainDue(5_000L, 100));
    }

    /**
     * A busy queue must never drop a location: the block would stay mined out
     * forever. Anything over the batch limit has to survive to the next pass.
     */
    @Test
    void leavesOverflowQueuedRatherThanDroppingIt() {
        MineRegenQueue queue = new MineRegenQueue();
        for (int x = 0; x < 250; x++) {
            queue.schedule(key(x), 1_000L);
        }

        assertEquals(100, queue.drainDue(2_000L, 100).size());
        assertEquals(150, queue.size(), "the rest must still be waiting, not lost");

        assertEquals(100, queue.drainDue(2_000L, 100).size());
        assertEquals(50, queue.drainDue(2_000L, 100).size());
        assertEquals(0, queue.size());
    }

    @Test
    void ignoresALocationAlreadyWaiting() {
        MineRegenQueue queue = new MineRegenQueue();
        queue.schedule(key(1), 1_000L);
        queue.schedule(key(1), 5_000L);

        assertEquals(1, queue.size(), "one block cannot occupy the queue twice");
        assertEquals(List.of(key(1)), queue.drainDue(2_000L, 10),
                "the first schedule wins, so a re-mine cannot delay regeneration");
    }

    @Test
    void allowsReschedulingOnceDrained() {
        MineRegenQueue queue = new MineRegenQueue();
        queue.schedule(key(1), 1_000L);
        assertTrue(queue.isQueued(key(1)));

        queue.drainDue(2_000L, 10);
        assertFalse(queue.isQueued(key(1)));

        queue.schedule(key(1), 3_000L);
        assertEquals(1, queue.size());
    }

    @Test
    void clearsOnlyTheNamedWorld() {
        MineRegenQueue queue = new MineRegenQueue();
        queue.schedule(new MineRegenQueue.Key("stonewake", 1, 64, 0), 1_000L);
        queue.schedule(new MineRegenQueue.Key("bloodvein", 2, 64, 0), 1_000L);

        queue.clearWorld("stonewake");

        assertEquals(1, queue.size());
        assertEquals(List.of(new MineRegenQueue.Key("bloodvein", 2, 64, 0)), queue.drainDue(2_000L, 10));
    }

    @Test
    void distinguishesLocationsThatDifferOnlyByAxis() {
        MineRegenQueue queue = new MineRegenQueue();
        queue.schedule(new MineRegenQueue.Key("stonewake", 1, 64, 0), 1_000L);
        queue.schedule(new MineRegenQueue.Key("stonewake", 0, 64, 1), 1_000L);

        assertEquals(2, queue.size());
    }
}
