package me.hcfcore.core.ability;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockupTrackerTest {

    private final BlockupTracker tracker = new BlockupTracker();

    @Test
    void doesNotDenyUntilTheHitThresholdIsReached() {
        UUID victim = UUID.randomUUID();

        assertFalse(tracker.recordHit(victim, 3, 15));
        assertFalse(tracker.isDenied(victim));
        assertFalse(tracker.recordHit(victim, 3, 15));
        assertFalse(tracker.isDenied(victim));

        assertTrue(tracker.recordHit(victim, 3, 15), "the third hit should trigger the deny window");
        assertTrue(tracker.isDenied(victim));
    }

    @Test
    void resetsTheCounterAfterTriggering() {
        UUID victim = UUID.randomUUID();
        tracker.recordHit(victim, 2, 15);
        tracker.recordHit(victim, 2, 15);

        assertFalse(tracker.recordHit(victim, 2, 15), "the counter should have reset after the previous trigger");
    }

    @Test
    void isDeniedIsFalseForAnUntrackedPlayer() {
        assertFalse(tracker.isDenied(UUID.randomUUID()));
    }
}
