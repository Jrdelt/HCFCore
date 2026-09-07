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
     * Levels through {@code easyThroughLevel} use the gentle multiplier;
     * each level after that uses the post-threshold multiplier instead.
     */
    static double upgradeCost(int level, double upgradeCostBase, int easyThroughLevel,
            double easyMultiplier, double postThresholdMultiplier) {
        int growthSteps = Math.max(0, level - 1);
        int easySteps = Math.min(growthSteps, Math.max(0, easyThroughLevel - 1));
        int postThresholdSteps = Math.max(0, growthSteps - easySteps);
        double cost = upgradeCostBase * Math.pow(easyMultiplier, easySteps)
                * Math.pow(postThresholdMultiplier, postThresholdSteps);
        return Double.isFinite(cost) ? cost : Double.MAX_VALUE;
    }

    /** The automatic-collection bonus percentage applied at {@code level}. */
    static double dropBonusPercent(double basePercent, double perLevelPercent, int level) {
        return basePercent + perLevelPercent * Math.max(0, level - 1);
    }

    /** There is deliberately no configured Backpack level cap. */
    static int clampLevel(int level) {
        return Math.max(1, level);
    }

}
