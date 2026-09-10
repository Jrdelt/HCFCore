package me.vertex.core.dupe;

/** Staff-only record of an item identity that needs investigation. */
public record DupeCase(String id, String fingerprint, String holderUuid, String holderName, String itemId,
                       String material, String source, String details, String status, long createdAt,
                       String resolvedBy, long resolvedAt, String resolution) {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_DISMISSED = "DISMISSED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    public boolean unresolved() {
        return STATUS_OPEN.equalsIgnoreCase(status);
    }
}
