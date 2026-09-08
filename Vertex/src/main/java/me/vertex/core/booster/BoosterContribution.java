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
 */
public record BoosterContribution(
        String sourceId,
        BoosterCategory category,
        double percent,
        boolean active,
        String inactiveReasonKey) {

    public static BoosterContribution active(String sourceId, BoosterCategory category, double percent) {
        return new BoosterContribution(sourceId, category, percent, true, null);
    }

    public static BoosterContribution inactive(String sourceId, BoosterCategory category, double percent,
            String inactiveReasonKey) {
        return new BoosterContribution(sourceId, category, percent, false, inactiveReasonKey);
    }
}
