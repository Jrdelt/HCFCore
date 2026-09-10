package me.vertex.core.portal;

import java.util.Locale;

/** A portal can feed one Zone type or one configured Mine id. */
public record PortalTarget(Kind kind, String id) {
    public enum Kind { HAVEN, RIFTLANDS, MINE }

    public PortalTarget {
        if (kind == null) throw new IllegalArgumentException("Portal target kind is required.");
        id = kind == Kind.MINE ? normalize(id) : kind.name().toLowerCase(Locale.ROOT);
        if (kind == Kind.MINE && id == null) throw new IllegalArgumentException("Mine id is required.");
    }

    public String storageKey() { return kind == Kind.MINE ? "mine:" + id : kind.name(); }
    public String displayName() { return kind == Kind.HAVEN ? "Haven" : kind == Kind.RIFTLANDS ? "Riftlands" : id; }

    public static PortalTarget parse(String raw) {
        String value = normalize(raw);
        if (value == null) return null;
        return switch (value) {
            case "haven" -> new PortalTarget(Kind.HAVEN, "haven");
            case "riftlands", "rift" -> new PortalTarget(Kind.RIFTLANDS, "riftlands");
            default -> new PortalTarget(Kind.MINE, value);
        };
    }
    public static PortalTarget fromStorage(String raw) {
        if (raw == null) return null;
        if (raw.equalsIgnoreCase("HAVEN")) return new PortalTarget(Kind.HAVEN, "haven");
        if (raw.equalsIgnoreCase("RIFTLANDS")) return new PortalTarget(Kind.RIFTLANDS, "riftlands");
        if (raw.regionMatches(true, 0, "mine:", 0, 5)) return new PortalTarget(Kind.MINE, raw.substring(5));
        return null;
    }
    public static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return value.isBlank() ? null : value;
    }
}
