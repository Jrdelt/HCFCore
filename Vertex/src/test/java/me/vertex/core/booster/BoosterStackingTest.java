package me.vertex.core.booster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoosterStackingTest {

    @Test
    void addsEveryActiveContribution() {
        BoosterStacking.Result result = BoosterStacking.combine(
                List.of(10D, 15D, 20D, 25D), BoosterStacking.Mode.ADDITIVE, BoosterStacking.UNCAPPED);

        assertEquals(70D, result.raw(), 0.0001);
        assertEquals(70D, result.effective(), 0.0001);
        assertFalse(result.capped());
        assertEquals(1.70D, result.multiplier(), 0.0001);
    }

    @Test
    void takesOnlyTheLargestInHighestOnlyMode() {
        BoosterStacking.Result result = BoosterStacking.combine(
                List.of(10D, 45D, 20D), BoosterStacking.Mode.HIGHEST_ONLY, BoosterStacking.UNCAPPED);

        assertEquals(45D, result.effective(), 0.0001);
    }

    /** The tracker has to be able to show the raw total alongside the capped one. */
    @Test
    void keepsTheRawTotalVisibleWhenACapBites() {
        BoosterStacking.Result result = BoosterStacking.combine(
                List.of(200D, 150D, 100D, 100D), BoosterStacking.Mode.ADDITIVE, 500D);

        assertEquals(550D, result.raw(), 0.0001);
        assertEquals(500D, result.effective(), 0.0001);
        assertTrue(result.capped());
        assertEquals(6.00D, result.multiplier(), 0.0001);
    }

    @Test
    void doesNotReportCappedWhenTheTotalIsExactlyTheCap() {
        BoosterStacking.Result result = BoosterStacking.combine(
                List.of(500D), BoosterStacking.Mode.ADDITIVE, 500D);

        assertEquals(500D, result.effective(), 0.0001);
        assertFalse(result.capped());
    }

    @Test
    void treatsNoContributionsAsNoBonus() {
        BoosterStacking.Result result = BoosterStacking.combine(
                List.of(), BoosterStacking.Mode.ADDITIVE, BoosterStacking.UNCAPPED);

        assertEquals(0D, result.effective(), 0.0001);
        assertEquals(1.0D, result.multiplier(), 0.0001);
    }

    @Test
    void allowsTotalsAboveOneHundredPercent() {
        BoosterStacking.Result result = BoosterStacking.combine(
                List.of(100D, 50D), BoosterStacking.Mode.ADDITIVE, BoosterStacking.UNCAPPED);

        assertEquals(150D, result.effective(), 0.0001);
        assertEquals(2.50D, result.multiplier(), 0.0001);
    }

    @Test
    void aZeroCapDisablesTheCategoryRatherThanMeaningUncapped() {
        BoosterStacking.Result result = BoosterStacking.combine(
                List.of(40D), BoosterStacking.Mode.ADDITIVE, 0D);

        assertEquals(0D, result.effective(), 0.0001);
        assertTrue(result.capped());
    }
}
