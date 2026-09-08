package me.vertex.core.booster;

import java.util.List;

/**
 * How a category's active contributions combine into one number. Kept free
 * of Bukkit so the rules are unit-testable without a server, the same way
 * {@code ShopPricing} is.
 */
public final class BoosterStacking {

    /** A negative cap means "no cap configured". */
    public static final double UNCAPPED = -1D;

    public enum Mode {
        /** Every contribution adds together. The default, and what mining expects. */
        ADDITIVE,
        /** Only the single largest contribution counts. */
        HIGHEST_ONLY
    }

    /**
     * @param raw      what the sources add up to before any cap
     * @param effective what the server will actually apply
     * @param capped   whether a configured cap reduced the result, so the
     *                 tracker can show both numbers and explain the gap
     */
    public record Result(double raw, double effective, boolean capped) {
        public double multiplier() {
            return 1D + effective / 100D;
        }
    }

    private BoosterStacking() {
    }

    public static Result combine(List<Double> activePercents, Mode mode, double cap) {
        double raw = switch (mode) {
            case ADDITIVE -> activePercents.stream().mapToDouble(Double::doubleValue).sum();
            case HIGHEST_ONLY -> activePercents.stream().mapToDouble(Double::doubleValue).max().orElse(0D);
        };
        if (cap >= 0D && raw > cap) {
            return new Result(raw, cap, true);
        }
        return new Result(raw, raw, false);
    }
}
