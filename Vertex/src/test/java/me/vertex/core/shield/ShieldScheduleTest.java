package me.vertex.core.shield;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the wall-clock-anchored recurring window math {@link ShieldSchedule} relies on. */
class ShieldScheduleTest {

    private long easternMillis(int hour, int minute) {
        // A fixed, arbitrary Wednesday, so the test is deterministic regardless of when it runs.
        return ZonedDateTime.of(2026, 1, 7, hour, minute, 0, 0, ShieldSchedule.ZONE).toInstant().toEpochMilli();
    }

    @Test
    void activeInsideItsDailyWindow() {
        ShieldSchedule schedule = ShieldSchedule.ofHourMinuteDuration(22, 0, 120); // 22:00-00:00 ET
        assertTrue(schedule.isActiveAt(easternMillis(22, 30)));
        assertTrue(schedule.isActiveAt(easternMillis(23, 59)));
    }

    @Test
    void inactiveOutsideItsDailyWindow() {
        ShieldSchedule schedule = ShieldSchedule.ofHourMinuteDuration(22, 0, 60); // 22:00-23:00 ET
        assertFalse(schedule.isActiveAt(easternMillis(12, 0)));
        assertFalse(schedule.isActiveAt(easternMillis(23, 30)));
    }

    @Test
    void currentWindowEndMatchesStartPlusDuration() {
        ShieldSchedule schedule = ShieldSchedule.ofHourMinuteDuration(2, 0, 90); // 02:00-03:30 ET
        long now = easternMillis(2, 30);
        long expectedEnd = easternMillis(3, 30);
        assertEquals(expectedEnd, schedule.currentWindowEndMillis(now));
    }

    @Test
    void currentWindowEndIsMinusOneWhenNotActive() {
        ShieldSchedule schedule = ShieldSchedule.ofHourMinuteDuration(2, 0, 60);
        assertEquals(-1L, schedule.currentWindowEndMillis(easternMillis(10, 0)));
    }

    @Test
    void nextActivationBeforeTodaysWindowIsToday() {
        ShieldSchedule schedule = ShieldSchedule.ofHourMinuteDuration(22, 0, 60);
        long now = easternMillis(9, 0);
        assertEquals(easternMillis(22, 0), schedule.nextActivationAfter(now));
    }

    @Test
    void nextActivationAfterTodaysWindowIsTomorrow() {
        ShieldSchedule schedule = ShieldSchedule.ofHourMinuteDuration(9, 0, 60);
        long now = easternMillis(12, 0);
        long expected = easternMillis(9, 0) + 24 * 60 * 60_000L;
        assertEquals(expected, schedule.nextActivationAfter(now));
    }

    @Test
    void nextActivationWhileActiveIsTomorrowsOccurrence() {
        ShieldSchedule schedule = ShieldSchedule.ofHourMinuteDuration(9, 0, 120);
        long now = easternMillis(9, 30);
        long expected = easternMillis(9, 0) + 24 * 60 * 60_000L;
        assertEquals(expected, schedule.nextActivationAfter(now));
    }

    @Test
    void serializeAndParseRoundTrip() {
        ShieldSchedule schedule = ShieldSchedule.ofHourMinuteDuration(14, 30, 45);
        assertEquals(schedule, ShieldSchedule.parse(schedule.serialize()));
    }
}
