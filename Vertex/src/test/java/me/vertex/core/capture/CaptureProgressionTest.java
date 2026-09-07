package me.vertex.core.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaptureProgressionTest {

    @Test
    void oneFactionMemberTakesTheConfiguredCaptureTime() {
        assertEquals(100D / 300D, CaptureProgression.progressPerSecond(300, 1, 0.25D), 0.000001D);
        assertEquals(300L, CaptureProgression.remainingSeconds(0D, 300, 1, 0.25D));
    }

    @Test
    void eachAdditionalFactionMemberSpeedsCaptureByTheConfiguredFraction() {
        assertEquals(100D / 240D, CaptureProgression.progressPerSecond(300, 2, 0.25D), 0.000001D);
        assertEquals(200L, CaptureProgression.remainingSeconds(0D, 300, 3, 0.25D));
    }

    @Test
    void remainingTimeRoundsUpAndNeverReturnsNegative() {
        assertEquals(1L, CaptureProgression.remainingSeconds(99.9D, 300, 1, 0.25D));
        assertEquals(0L, CaptureProgression.remainingSeconds(100D, 300, 1, 0.25D));
        assertEquals(0L, CaptureProgression.remainingSeconds(120D, 300, 1, 0.25D));
    }
}
