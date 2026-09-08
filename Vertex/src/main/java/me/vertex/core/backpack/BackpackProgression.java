package me.vertex.core.backpack;

/**
 * Pure level/size/cost math for Backpacks, kept free of Bukkit so it's
 * unit-testable without a server. Mirrors the shape of
 * {@code ChunkCollectorManager}'s tier-growth/upgrade-cost curves.
 */
final class BackpackProgression {

    private BackpackProgression() {
    }

    /**
     * Vault cost to upgrade from {@code level} to {@code level + 1}.
     * Each individual level-up step uses its own multiplier: steps before
     * {@code easyThroughLevel} use the gentle {@code easyMultiplier}; from
     * there the per-step multiplier ramps up smoothly (linearly) until it
     * reaches {@code maxMultiplier} exactly at {@code rampThroughLevel},
     * and stays capped at {@code maxMultiplier} for every step after that
     * -- so there's no sudden jump in cost right at the easy/ramp
     * boundary, only a gradually steepening climb.
     */
    static double upgradeCost(int level, double upgradeCostBase, int easyThroughLevel,
            double easyMultiplier, int rampThroughLevel, double maxMultiplier) {
        int growthSteps = Math.max(0, level - 1);
        double cost = upgradeCostBase;
        for (int step = 1; step <= growthSteps && Double.isFinite(cost); step++) {
            cost *= stepMultiplier(step, easyThroughLevel, easyMultiplier, rampThroughLevel, maxMultiplier);
        }
        return Double.isFinite(cost) ? cost : Double.MAX_VALUE;
    }

    private static double stepMultiplier(int step, int easyThroughLevel, double easyMultiplier,
            int rampThroughLevel, double maxMultiplier) {
        if (step < easyThroughLevel) {
            return easyMultiplier;
        }
        if (rampThroughLevel <= easyThroughLevel || step >= rampThroughLevel) {
            return maxMultiplier;
        }
        double progress = (double) (step - easyThroughLevel) / (rampThroughLevel - easyThroughLevel);
        return easyMultiplier + (maxMultiplier - easyMultiplier) * progress;
    }

    /**
     * The automatic-collection bonus percentage applied at {@code level}.
     * Compounds {@code perLevelGrowthPercent} onto {@code basePercent}
     * every level (like interest, not a flat per-level add-on), so a
     * heavily-upgraded Backpack pulls dramatically further ahead of a
     * fresh one instead of only creeping up by a fixed amount each level.
     */
    static double dropBonusPercent(double basePercent, double perLevelGrowthPercent, int level) {
        int growthSteps = Math.max(0, level - 1);
        double bonus = basePercent * Math.pow(1 + perLevelGrowthPercent / 100.0, growthSteps);
        return Double.isFinite(bonus) ? bonus : Double.MAX_VALUE;
    }

    /** There is deliberately no configured Backpack level cap. */
    static int clampLevel(int level) {
        return Math.max(1, level);
    }

}
