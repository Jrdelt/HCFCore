package me.vertex.core.factions;

import java.util.UUID;

/** Persistent personal power. It follows the player, not faction membership. */
public record FactionPowerProfile(UUID playerUuid, double current, double maximum,
                                  long nextRegenerationAtMillis) {
    public FactionPowerProfile {
        if (playerUuid == null) throw new IllegalArgumentException("playerUuid");
        maximum = finiteNonNegative(maximum);
        current = Math.min(maximum, finiteNonNegative(current));
        nextRegenerationAtMillis = Math.max(0L, nextRegenerationAtMillis);
    }

    public FactionPowerProfile withCurrent(double value, long nextRegenerationAt) {
        return new FactionPowerProfile(playerUuid, value, maximum, nextRegenerationAt);
    }

    public FactionPowerProfile withMaximum(double value) {
        return new FactionPowerProfile(playerUuid, current, value, nextRegenerationAtMillis);
    }

    public FactionPowerProfile resetCurrentToMaximum(long nextRegenerationAt) {
        return new FactionPowerProfile(playerUuid, maximum, maximum, nextRegenerationAt);
    }

    private static double finiteNonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0D, value) : 0D;
    }
}
