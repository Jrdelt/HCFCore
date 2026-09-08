package me.vertex.core.mine;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineOreTableTest {

    /** The shipped Mining World 1 shape: 90% base, 10% ore. */
    private static MineOreTable world1() {
        return MineOreTable.of(List.of(
                new MineOreTable.Entry(Material.STONE, 90.0, 1),
                new MineOreTable.Entry(Material.COAL_ORE, 4.5, 1),
                new MineOreTable.Entry(Material.IRON_ORE, 3.5, 1),
                new MineOreTable.Entry(Material.REDSTONE_ORE, 2.0, 1)));
    }

    @Test
    void reportsConfiguredChancesAsPercentages() {
        MineOreTable table = world1();
        assertEquals(90.0, table.chancePercent(Material.STONE), 0.0001);
        assertEquals(4.5, table.chancePercent(Material.COAL_ORE), 0.0001);
        assertEquals(0.0, table.chancePercent(Material.DIAMOND_ORE), 0.0001);
    }

    /** Weights need not total 100 -- an admin editing one ore must not have to rebalance the rest. */
    @Test
    void normalisesWeightsThatDoNotSumToOneHundred() {
        MineOreTable table = MineOreTable.of(List.of(
                new MineOreTable.Entry(Material.STONE, 3.0, 1),
                new MineOreTable.Entry(Material.COAL_ORE, 1.0, 1)));

        assertEquals(75.0, table.chancePercent(Material.STONE), 0.0001);
        assertEquals(25.0, table.chancePercent(Material.COAL_ORE), 0.0001);
    }

    @Test
    void picksAcrossTheWholeRangeInWeightOrder() {
        MineOreTable table = world1();
        assertEquals(Material.STONE, table.pick(0.0));
        assertEquals(Material.STONE, table.pick(0.89));
        assertEquals(Material.COAL_ORE, table.pick(0.92));
        assertEquals(Material.IRON_ORE, table.pick(0.96));
        assertEquals(Material.REDSTONE_ORE, table.pick(0.99));
    }

    @Test
    void clampsRollsOutsideTheUnitRange() {
        MineOreTable table = world1();
        assertEquals(Material.STONE, table.pick(-1.0));
        assertEquals(Material.REDSTONE_ORE, table.pick(1.0));
        assertEquals(Material.REDSTONE_ORE, table.pick(9.0));
    }

    @Test
    void producesTheConfiguredDistributionOverManyRolls() {
        MineOreTable table = world1();
        int diamondsWorth = 100_000;
        int ore = 0;
        for (int i = 0; i < diamondsWorth; i++) {
            if (table.pick(i / (double) diamondsWorth) != Material.STONE) {
                ore++;
            }
        }
        double orePercent = ore * 100D / diamondsWorth;
        assertEquals(10.0, orePercent, 0.1, "the table should yield its configured 10% ore rate");
    }

    @Test
    void dropsZeroWeightEntriesEntirely() {
        MineOreTable table = MineOreTable.of(List.of(
                new MineOreTable.Entry(Material.STONE, 100.0, 1),
                new MineOreTable.Entry(Material.DIAMOND_ORE, 0.0, 1)));

        assertNull(table.entry(Material.DIAMOND_ORE), "a zero weight means the ore is off, not merely rare");
        assertEquals(Material.STONE, table.pick(0.99));
    }

    @Test
    void anEmptyTablePicksNothingRatherThanThrowing() {
        MineOreTable empty = MineOreTable.of(List.of());
        assertTrue(empty.isEmpty());
        assertNull(empty.pick(0.5));
    }

    /** The whole point of a Hot Zone: the rarer the ore, the bigger its relative gain. */
    @Test
    void hotZoneFavoursRarerOresMoreThanCommonOnes() {
        MineOreTable base = world1();
        MineOreTable hot = base.withHotZone(0.20, 0.5, Map.of());

        double coalGain = hot.chancePercent(Material.COAL_ORE) / base.chancePercent(Material.COAL_ORE);
        double redstoneGain = hot.chancePercent(Material.REDSTONE_ORE) / base.chancePercent(Material.REDSTONE_ORE);

        assertTrue(redstoneGain > coalGain,
                "the rarer ore should gain proportionally more than the commoner one");
        assertTrue(hot.chancePercent(Material.STONE) < base.chancePercent(Material.STONE),
                "the base block's share should fall as ore rises");
    }

    @Test
    void hotZoneOverrideWinsOverTheCurve() {
        MineOreTable base = world1();
        MineOreTable hot = base.withHotZone(0.20, 0.5, Map.of(Material.COAL_ORE, 10.0));

        assertTrue(hot.chancePercent(Material.COAL_ORE) > base.chancePercent(Material.COAL_ORE) * 5,
                "an explicit 10x override should dominate the rarity curve");
    }

    @Test
    void hotZoneWithNoIntensityChangesNothing() {
        MineOreTable base = world1();
        MineOreTable hot = base.withHotZone(0.0, 0.5, Map.of());
        assertEquals(base.chancePercent(Material.COAL_ORE), hot.chancePercent(Material.COAL_ORE), 0.0001);
    }

    @Test
    void alwaysNormalisesToAValidTable() {
        MineOreTable hot = world1().withHotZone(0.5, 0.7, Map.of());
        double total = hot.asPercentages().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(100.0, total, 0.0001, "percentages must always add up to a whole table");
    }
}
