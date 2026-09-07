package me.vertex.core.backpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackpackProgressionTest {

    @Test
    void upgradeCostUsesGentleGrowthThroughLevelFiftyThenDoubles() {
        assertEquals(500.0, BackpackProgression.upgradeCost(1, 500.0, 50, 1.10, 2.0));
        assertEquals(550.0, BackpackProgression.upgradeCost(2, 500.0, 50, 1.10, 2.0), 0.001);
        double levelFifty = 500.0 * Math.pow(1.10, 49);
        assertEquals(levelFifty, BackpackProgression.upgradeCost(50, 500.0, 50, 1.10, 2.0), 0.001);
        assertEquals(levelFifty * 2.0,
                BackpackProgression.upgradeCost(51, 500.0, 50, 1.10, 2.0), 0.001);
    }

    @Test
    void dropBonusPercentGrowsLinearlyPerLevel() {
        assertEquals(45.0, BackpackProgression.dropBonusPercent(45.0, 3.75, 1));
        assertEquals(48.75, BackpackProgression.dropBonusPercent(45.0, 3.75, 2), 0.001);
        assertEquals(48.75 + 3.75, BackpackProgression.dropBonusPercent(45.0, 3.75, 3), 0.001);
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
