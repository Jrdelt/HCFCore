package me.vertex.core.enchant;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mirrors {@code MineOreTableTest}'s own test style, since {@link RuneRollTable} mirrors {@code MineOreTable}'s shape. */
class RuneRollTableTest {

    private static RuneRollTable simpleTier() {
        return RuneRollTable.of(List.of(
                new RuneRollTable.Entry("haste_pickaxe", 1, 60.0),
                new RuneRollTable.Entry("haste_pickaxe", 2, 18.0),
                new RuneRollTable.Entry("sharpened_edge", 1, 14.0),
                new RuneRollTable.Entry("sharpened_edge", 2, 8.0)));
    }

    @Test
    void reportsConfiguredChancesAsPercentages() {
        RuneRollTable table = simpleTier();
        assertEquals(60.0, table.chancePercent("haste_pickaxe", 1), 0.0001);
        assertEquals(18.0, table.chancePercent("haste_pickaxe", 2), 0.0001);
        assertEquals(0.0, table.chancePercent("haste_pickaxe", 3), 0.0001);
    }

    /** Weights need not total 100 -- an admin editing one entry must not have to rebalance the rest. */
    @Test
    void normalisesWeightsThatDoNotSumToOneHundred() {
        RuneRollTable table = RuneRollTable.of(List.of(
                new RuneRollTable.Entry("haste_pickaxe", 1, 3.0),
                new RuneRollTable.Entry("haste_pickaxe", 2, 1.0)));

        assertEquals(75.0, table.chancePercent("haste_pickaxe", 1), 0.0001);
        assertEquals(25.0, table.chancePercent("haste_pickaxe", 2), 0.0001);
    }

    @Test
    void picksAcrossTheWholeRangeInWeightOrder() {
        RuneRollTable table = simpleTier();
        assertEquals(new RuneRollTable.Selection("haste_pickaxe", 1), table.pick(0.0));
        assertEquals(new RuneRollTable.Selection("haste_pickaxe", 1), table.pick(0.59));
        assertEquals(new RuneRollTable.Selection("haste_pickaxe", 2), table.pick(0.70));
        assertEquals(new RuneRollTable.Selection("sharpened_edge", 1), table.pick(0.85));
        assertEquals(new RuneRollTable.Selection("sharpened_edge", 2), table.pick(0.99));
    }

    @Test
    void clampsRollsOutsideTheUnitRange() {
        RuneRollTable table = simpleTier();
        assertEquals(new RuneRollTable.Selection("haste_pickaxe", 1), table.pick(-1.0));
        assertEquals(new RuneRollTable.Selection("sharpened_edge", 2), table.pick(1.0));
        assertEquals(new RuneRollTable.Selection("sharpened_edge", 2), table.pick(9.0));
    }

    @Test
    void producesTheConfiguredDistributionOverManyRolls() {
        RuneRollTable table = simpleTier();
        int trials = 100_000;
        int highLevelHaste = 0;
        for (int i = 0; i < trials; i++) {
            RuneRollTable.Selection selection = table.pick(i / (double) trials);
            if (selection.enchantId().equals("haste_pickaxe") && selection.level() == 2) {
                highLevelHaste++;
            }
        }
        double percent = highLevelHaste * 100D / trials;
        assertEquals(18.0, percent, 0.1, "the table should yield its configured 18% rate for haste_pickaxe level 2");
    }

    @Test
    void dropsZeroWeightEntriesEntirely() {
        RuneRollTable table = RuneRollTable.of(List.of(
                new RuneRollTable.Entry("haste_pickaxe", 1, 100.0),
                new RuneRollTable.Entry("haste_pickaxe", 4, 0.0)));

        assertNull(table.entry("haste_pickaxe", 4), "a zero weight means the combo is off, not merely rare");
        assertEquals(new RuneRollTable.Selection("haste_pickaxe", 1), table.pick(0.99));
    }

    @Test
    void anEmptyTablePicksNothingRatherThanThrowing() {
        RuneRollTable empty = RuneRollTable.of(List.of());
        assertTrue(empty.isEmpty());
        assertNull(empty.pick(0.5));
    }

    @Test
    void percentagesAlwaysAddUpToAWholeTable() {
        RuneRollTable table = simpleTier();
        Map<RuneRollTable.Entry, Double> percentages = table.asPercentages();
        double total = percentages.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(100.0, total, 0.0001);
    }
}
