package me.vertex.core.mine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MineKothBoosterTest {

    /** The shipped ladder: +5% on capture, then 10/15/20 at 30m, 1h, 2h. */
    private static MineKothBooster ladder() {
        return MineKothBooster.of(List.of(
                new MineKothBooster.Tier(0L, 5.0),
                new MineKothBooster.Tier(1800L, 10.0),
                new MineKothBooster.Tier(3600L, 15.0),
                new MineKothBooster.Tier(7200L, 20.0)));
    }

    @Test
    void awardsTheStageEarnedForHowLongItHasBeenHeld() {
        MineKothBooster booster = ladder();
        assertEquals(5.0, booster.percentFor(0L), 0.0001);
        assertEquals(5.0, booster.percentFor(1799L), 0.0001);
        assertEquals(10.0, booster.percentFor(1800L), 0.0001);
        assertEquals(15.0, booster.percentFor(3600L), 0.0001);
        assertEquals(20.0, booster.percentFor(7200L), 0.0001);
        assertEquals(20.0, booster.percentFor(999_999L), 0.0001, "the top stage is the ceiling");
    }

    @Test
    void reportsTheNextStageAndTheWaitForIt() {
        MineKothBooster booster = ladder();
        assertEquals(1800L, booster.nextTier(0L).afterSeconds());
        assertEquals(1800L, booster.secondsUntilNextTier(0L));
        assertEquals(600L, booster.secondsUntilNextTier(1200L));
        assertNull(booster.nextTier(7200L), "there is nothing after the top stage");
        assertEquals(-1L, booster.secondsUntilNextTier(7200L));
    }

    /** Stages are read as configured, so an unsorted or gapped ladder still behaves. */
    @Test
    void sortsStagesRegardlessOfConfigOrder() {
        MineKothBooster booster = MineKothBooster.of(List.of(
                new MineKothBooster.Tier(3600L, 15.0),
                new MineKothBooster.Tier(0L, 5.0),
                new MineKothBooster.Tier(1800L, 10.0)));

        assertEquals(5.0, booster.percentFor(10L), 0.0001);
        assertEquals(15.0, booster.percentFor(3600L), 0.0001);
    }

    @Test
    void aLadderThatDoesNotStartAtZeroPaysNothingUntilItsFirstStage() {
        MineKothBooster booster = MineKothBooster.of(List.of(new MineKothBooster.Tier(600L, 25.0)));

        assertEquals(0.0, booster.percentFor(0L), 0.0001);
        assertEquals(25.0, booster.percentFor(600L), 0.0001);
    }

    @Test
    void anEmptyLadderPaysNothing() {
        MineKothBooster booster = MineKothBooster.of(List.of());
        assertEquals(0.0, booster.percentFor(10_000L), 0.0001);
        assertNull(booster.nextTier(0L));
    }
}
