package me.vertex.core.booster;

/**
 * One source's offer toward a category, for one player, right now.
 *
 * <p>An inactive contribution is still returned rather than dropped: the
 * tracker is far more useful when it can say "Mine KOTH: +15% — inactive,
 * wrong world" than when the line silently vanishes. Only active
 * contributions are summed.
 *
 * @param sourceId          stable id of the contributing source, also its lang key suffix
 * @param category          which bonus this feeds
 * @param percent           the configured value, e.g. 15.0 for +15%
 * @param active            whether it currently applies
 * @param inactiveReasonKey lang key suffix explaining why not; null when active
 * @param expiresAt         epoch millisecond timestamp when this temporary boost expires, or null if permanent
 */
public record BoosterContribution(
        String sourceId,
        BoosterCategory category,
        double percent,
        boolean active,
        String inactiveReasonKey,
        Long expiresAt) {

    public BoosterContribution(String sourceId, BoosterCategory category, double percent, boolean active,
            String inactiveReasonKey) {
        this(sourceId, category, percent, active, inactiveReasonKey, null);
    }

    public static BoosterContribution active(String sourceId, BoosterCategory category, double percent) {
        return new BoosterContribution(sourceId, category, percent, true, null, null);
    }

    public static BoosterContribution activeTimed(String sourceId, BoosterCategory category, double percent,
            long expiresAt) {
        return new BoosterContribution(sourceId, category, percent, true, null, expiresAt);
    }

    public static BoosterContribution inactive(String sourceId, BoosterCategory category, double percent,
            String inactiveReasonKey) {
        return new BoosterContribution(sourceId, category, percent, false, inactiveReasonKey, null);
    }

    /** Whether this contribution expires at a known epoch millisecond timestamp. */
    public boolean isTimed() {
        return expiresAt != null && expiresAt > 0L;
    }

    /** Remaining seconds until expiry, clamped to 0. Returns 0 if not timed. */
    public long remainingSeconds() {
        if (!isTimed()) {
            return 0L;
        }
        return Math.max(0L, (expiresAt - System.currentTimeMillis()) / 1000L);
    }
}
