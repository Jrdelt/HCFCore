package me.vertex.core.mine;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineOreTableTest {

    /** The shipped Mining World 1 shape: 60% base, 40% ore. */
    private static MineOreTable world1() {
        return MineOreTable.of(List.of(
                new MineOreTable.Entry(Material.STONE, 60.0, 1),
                new MineOreTable.Entry(Material.COAL_ORE, 18.0, 1),
                new MineOreTable.Entry(Material.IRON_ORE, 14.0, 1),
                new MineOreTable.Entry(Material.REDSTONE_ORE, 8.0, 1)));
    }

    @Test
    void reportsConfiguredChancesAsPercentages() {
        MineOreTable table = world1();
        assertEquals(60.0, table.chancePercent(Material.STONE), 0.0001);
        assertEquals(18.0, table.chancePercent(Material.COAL_ORE), 0.0001);
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
        assertEquals(Material.STONE, table.pick(0.59));
        assertEquals(Material.COAL_ORE, table.pick(0.70));
        assertEquals(Material.IRON_ORE, table.pick(0.85));
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
        assertEquals(40.0, orePercent, 0.1, "the table should yield its configured 40% ore rate");
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
    void usesResourcesRatherThanOreBlocksForMineDrops() {
        assertEquals(Material.COAL, new MineOreTable.Entry(Material.COAL_ORE, 1D, 1).dropMaterial());
        assertEquals(Material.IRON_INGOT, new MineOreTable.Entry(Material.IRON_ORE, 1D, 1).dropMaterial());
        assertEquals(Material.REDSTONE, new MineOreTable.Entry(Material.REDSTONE_ORE, 1D, 1).dropMaterial());
        assertEquals(Material.DIAMOND, new MineOreTable.Entry(Material.DEEPSLATE_DIAMOND_ORE, 1D, 1).dropMaterial());
        assertEquals(Material.NETHERITE_INGOT, new MineOreTable.Entry(Material.NETHERITE_BLOCK, 1D, 1).dropMaterial());
    }

    @Test
    void preservesAnExplicitlyConfiguredDropMaterialDuringHotZones() {
        MineOreTable table = MineOreTable.of(List.of(
                new MineOreTable.Entry(Material.IRON_ORE, 1D, 1, Material.GOLD_INGOT)));

        assertEquals(Material.GOLD_INGOT,
                table.withHotZone(0.20D, 0.5D, Map.of()).entry(Material.IRON_ORE).dropMaterial());
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
        MineOreTable curveOnly = base.withHotZone(0.20, 0.5, Map.of());
        MineOreTable hot = base.withHotZone(0.20, 0.5, Map.of(Material.COAL_ORE, 10.0));

        assertTrue(hot.chancePercent(Material.COAL_ORE) > curveOnly.chancePercent(Material.COAL_ORE) * 3,
                "an explicit 10x override should dominate the normal rarity curve after normalisation");
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
