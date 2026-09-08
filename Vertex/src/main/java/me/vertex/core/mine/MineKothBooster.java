package me.vertex.core.mine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Ore Drop bonus a faction earns from holding a Mine KOTH, and how it
 * grows with uninterrupted ownership.
 *
 * <p>Every stage is stated outright in config rather than derived from a
 * multiplier, so the ladder can be shaped freely -- flat, steep, or with a
 * long final step -- without the code assuming a curve.
 */
public final class MineKothBooster {

    /**
     * @param afterSeconds how long the point must have been held continuously
     * @param percent      the Ore Drop bonus at that stage
     */
    public record Tier(long afterSeconds, double percent) {
    }

    private final List<Tier> tiers;

    private MineKothBooster(List<Tier> tiers) {
        this.tiers = List.copyOf(tiers);
    }

    public static MineKothBooster of(List<Tier> tiers) {
        List<Tier> sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingLong(Tier::afterSeconds));
        return new MineKothBooster(sorted);
    }

    public boolean isEmpty() {
        return tiers.isEmpty();
    }

    public List<Tier> tiers() {
        return tiers;
    }

    /** The bonus earned after holding for {@code heldSeconds}, or 0 before the first stage. */
    public double percentFor(long heldSeconds) {
        double percent = 0D;
        for (Tier tier : tiers) {
            if (heldSeconds >= tier.afterSeconds()) {
                percent = tier.percent();
            } else {
                break;
            }
        }
        return percent;
    }

    /** The stage after the one currently earned, or null once the ladder is topped out. */
    public Tier nextTier(long heldSeconds) {
        for (Tier tier : tiers) {
            if (heldSeconds < tier.afterSeconds()) {
                return tier;
            }
        }
        return null;
    }

    /** Seconds until the next stage, or -1 when there is none left. */
    public long secondsUntilNextTier(long heldSeconds) {
        Tier next = nextTier(heldSeconds);
        return next == null ? -1L : Math.max(0L, next.afterSeconds() - heldSeconds);
    }
}
