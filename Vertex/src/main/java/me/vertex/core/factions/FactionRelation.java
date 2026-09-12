package me.vertex.core.factions;

import java.util.Locale;

/** A faction's requested relation to another faction. */
public enum FactionRelation {
    ALLY, NEUTRAL, ENEMY;

    public static FactionRelation parse(String raw, FactionRelation fallback) {
        if (raw == null) return fallback;
        // Truce existed in early native-faction builds. It is intentionally
        // migrated to Neutral because Vertex now exposes exactly three
        // relationship types.
        if (raw.trim().equalsIgnoreCase("TRUCE")) return NEUTRAL;
        try { return valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
}
