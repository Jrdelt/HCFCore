package me.vertex.core.factions;

import java.util.Locale;

/** Vertex-owned faction ranks, ordered from least to most authority. */
public enum FactionRole {
    RECRUIT(0, "Recruit"),
    MEMBER(1, "Member"),
    MODERATOR(2, "Mod"),
    ADMIN(3, "Admin"),
    COLEADER(4, "Co-Leader"),
    LEADER(5, "Leader");

    private final int weight;
    private final String displayName;

    FactionRole(int weight, String displayName) {
        this.weight = weight;
        this.displayName = displayName;
    }

    public int weight() { return weight; }
    public String displayName() { return displayName; }

    public boolean atLeast(FactionRole other) { return weight >= other.weight; }

    /** GUI compatibility buckets used by the existing Vertex permission menu. */
    public String permissionBucket() {
        return switch (this) {
            case LEADER -> "leader";
            case COLEADER -> "coleader";
            case ADMIN -> "admin";
            case MODERATOR -> "mod";
            case MEMBER -> "member";
            case RECRUIT -> "recruit";
        };
    }

    public static FactionRole parse(String raw, FactionRole fallback) {
        if (raw == null) return fallback;
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalized.equals("MOD")) normalized = "MODERATOR";
        if (normalized.equals("NORMAL")) normalized = "MEMBER";
        try { return valueOf(normalized); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
}
