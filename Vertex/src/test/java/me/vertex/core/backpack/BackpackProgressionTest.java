package me.vertex.core.backpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackpackProgressionTest {

    @Test
    void upgradeCostUsesGentleGrowthThroughLevelFifty() {
        assertEquals(500.0, BackpackProgression.upgradeCost(1, 500.0, 50, 1.10, 150, 2.0));
        assertEquals(550.0, BackpackProgression.upgradeCost(2, 500.0, 50, 1.10, 150, 2.0), 0.001);
        double levelFifty = 500.0 * Math.pow(1.10, 49);
        assertEquals(levelFifty, BackpackProgression.upgradeCost(50, 500.0, 50, 1.10, 150, 2.0), 0.001);
    }

    @Test
    void upgradeCostRampsGraduallyInsteadOfJumpingStraightToTheCap() {
        double levelFifty = BackpackProgression.upgradeCost(50, 500.0, 50, 1.10, 150, 2.0);
        double levelFiftyOne = BackpackProgression.upgradeCost(51, 500.0, 50, 1.10, 150, 2.0);

        // The very first step past the easy phase must continue smoothly at
        // the easy rate (no discontinuity), not jump straight to the cap.
        assertEquals(levelFifty * 1.10, levelFiftyOne, levelFifty * 0.0001);
    }

    @Test
    void upgradeCostReachesTheCapExactlyAtTheRampThroughLevel() {
        double levelOneFifty = BackpackProgression.upgradeCost(150, 500.0, 50, 1.10, 150, 2.0);
        double levelOneFiftyOne = BackpackProgression.upgradeCost(151, 500.0, 50, 1.10, 150, 2.0);

        // The step starting at level 150 must be exactly double -- fully ramped.
        assertEquals(levelOneFifty * 2.0, levelOneFiftyOne, levelOneFifty * 0.0001);
    }

    @Test
    void upgradeCostPerStepMultiplierNeverDecreasesWhileRamping() {
        double previousStepMultiplier = 1.10;
        for (int level = 51; level <= 150; level++) {
            double cost = BackpackProgression.upgradeCost(level, 500.0, 50, 1.10, 150, 2.0);
            double previousCost = BackpackProgression.upgradeCost(level - 1, 500.0, 50, 1.10, 150, 2.0);
            double stepMultiplier = cost / previousCost;
            assertTrue(stepMultiplier >= previousStepMultiplier - 0.0001,
                    "the per-step multiplier must climb smoothly toward the cap, never dip back down");
            assertTrue(stepMultiplier <= 2.0 + 0.0001, "the per-step multiplier must never exceed the configured cap");
            previousStepMultiplier = stepMultiplier;
        }
    }

    @Test
    void dropBonusPercentCompoundsPerLevelInsteadOfAddingAFlatAmount() {
        assertEquals(25.0, BackpackProgression.dropBonusPercent(25.0, 3.725, 1), 0.0001);
        assertEquals(25.0 * 1.03725, BackpackProgression.dropBonusPercent(25.0, 3.725, 2), 0.0001);
        assertEquals(25.0 * Math.pow(1.03725, 2), BackpackProgression.dropBonusPercent(25.0, 3.725, 3), 0.0001);
    }

    @Test
    void dropBonusPercentPullsDramaticallyAheadAtHighLevels() {
        double atLevelOne = BackpackProgression.dropBonusPercent(25.0, 3.725, 1);
        double atLevelSixtySeven = BackpackProgression.dropBonusPercent(25.0, 3.725, 67);

        // Compounding 66 steps at 3.725% should multiply the bonus roughly
        // 11x -- a heavily upgraded Backpack must pull dramatically ahead of
        // a fresh one, not just creep up by a fixed amount each level.
        assertTrue(atLevelSixtySeven > atLevelOne * 10,
                "high levels should compound far ahead of the base, not stay nearly flat");
    }

    @Test
    void clampLevelKeepsAnInRangeLevelUnchanged() {
        assertEquals(5, BackpackProgression.clampLevel(5));
    }

    @Test
    void clampLevelFloorsBelowOneWithoutAnUpperCap() {
        assertEquals(1, BackpackProgression.clampLevel(0));
        assertEquals(1, BackpackProgression.clampLevel(-5));
        assertEquals(999, BackpackProgression.clampLevel(999));
    }

}
