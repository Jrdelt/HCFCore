package me.vertex.core.shield;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * One faction's Shield protection window: a single daily recurring window,
 * expressed in real-world Eastern time ("EST" per spec; this actually
 * follows America/New_York, which observes EDT in summer -- the spec's own
 * wording, matched here rather than a fixed non-DST offset).
 *
 * <p>The window is anchored to wall-clock time of day, not an elapsing
 * countdown, so it naturally "keeps advancing during server downtime" --
 * there is nothing to persist beyond the two integers below; whether the
 * window is active at any instant is always recomputed live from that
 * instant's Eastern local time.
 */
public record ShieldSchedule(int startMinuteOfDay, int durationMinutes) {

    public static final ZoneId ZONE = ZoneId.of("America/New_York");

    public ShieldSchedule {
        if (startMinuteOfDay < 0 || startMinuteOfDay > 1439) {
            throw new IllegalArgumentException("startMinuteOfDay must be 0-1439");
        }
        if (durationMinutes <= 0 || durationMinutes > 1440) {
            throw new IllegalArgumentException("durationMinutes must be 1-1440");
        }
    }

    /** Whether this window is active at the given instant. */
    public boolean isActiveAt(long epochMillis) {
        long todayStart = windowStartOnOrBefore(epochMillis);
        long todayEnd = todayStart + durationMinutes * 60_000L;
        return epochMillis >= todayStart && epochMillis < todayEnd;
    }

    /** The end of the window currently containing {@code epochMillis}, or -1 if not currently active. */
    public long currentWindowEndMillis(long epochMillis) {
        if (!isActiveAt(epochMillis)) {
            return -1L;
        }
        return windowStartOnOrBefore(epochMillis) + durationMinutes * 60_000L;
    }

    private static final long DAY_MILLIS = 24 * 60 * 60_000L;

    /** The next time (strictly after {@code epochMillis} if already active) this window starts. */
    public long nextActivationAfter(long epochMillis) {
        if (isActiveAt(epochMillis)) {
            return windowStartOnOrBefore(epochMillis) + DAY_MILLIS;
        }
        long todayStart = windowStartToday(epochMillis);
        return todayStart > epochMillis ? todayStart : todayStart + DAY_MILLIS;
    }

    private long windowStartToday(long epochMillis) {
        ZonedDateTime now = Instant.ofEpochMilli(epochMillis).atZone(ZONE);
        return now.toLocalDate().atStartOfDay(ZONE).plusMinutes(startMinuteOfDay).toInstant().toEpochMilli();
    }

    /** Today's (Eastern-local-date) scheduled start, expressed as an absolute instant. */
    private long windowStartOnOrBefore(long epochMillis) {
        ZonedDateTime now = Instant.ofEpochMilli(epochMillis).atZone(ZONE);
        ZonedDateTime midnight = now.toLocalDate().atStartOfDay(ZONE);
        ZonedDateTime candidate = midnight.plusMinutes(startMinuteOfDay);
        if (candidate.toInstant().toEpochMilli() > epochMillis) {
            candidate = candidate.minusDays(1);
        }
        return candidate.toInstant().toEpochMilli();
    }

    public String serialize() {
        return startMinuteOfDay + ":" + durationMinutes;
    }

    public static ShieldSchedule parse(String text) {
        String[] parts = text.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected START:DURATION, got: " + text);
        }
        return new ShieldSchedule(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
    }

    public static ShieldSchedule ofHourMinuteDuration(int hour, int minute, int durationMinutes) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Invalid time of day: " + hour + ":" + minute);
        }
        return new ShieldSchedule(hour * 60 + minute, durationMinutes);
    }

    @Override
    public String toString() {
        return String.format("%02d:%02d for %dm (ET)", startMinuteOfDay / 60, startMinuteOfDay % 60, durationMinutes);
    }
}
