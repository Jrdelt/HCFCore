package me.vertex.core.mine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineKothControlTest {

    private static final int RED = 1;
    private static final int BLUE = 2;
    private static final int GREEN = 3;
    private static final MineKothControl.Settings SETTINGS = MineKothControl.Settings.DEFAULTS;

    private static MineKothControl.Result tick(MineKothControl.Snapshot from,
            Map<Integer, Integer> present, double seconds) {
        return MineKothControl.tick(from, present, seconds, SETTINGS);
    }

    @Test
    void anEmptyZoneChangesNothing() {
        MineKothControl.Result result = tick(MineKothControl.Snapshot.UNOWNED, Map.of(), 10D);

        assertEquals(MineKothControl.State.IDLE, result.state());
        assertEquals(0D, result.controlPercent(), 0.0001);
        assertNull(result.ownerFactionId());
    }

    @Test
    void oneFactionCapturesAnUnownedPointInTheConfiguredTime() {
        MineKothControl.Result result = tick(MineKothControl.Snapshot.UNOWNED, Map.of(RED, 1), 180D);

        assertEquals(RED, result.ownerFactionId());
        assertEquals(100D, result.controlPercent(), 0.0001);
        assertTrue(result.ownerChanged());
    }

    @Test
    void progressIsProportionalToTimeSpent() {
        MineKothControl.Result half = tick(MineKothControl.Snapshot.UNOWNED, Map.of(RED, 1), 90D);

        assertEquals(50D, half.controlPercent(), 0.0001);
        assertNull(half.ownerFactionId(), "a half-captured point is not owned yet");
        assertEquals(MineKothControl.State.CAPTURING, half.state());
    }

    @Test
    void extraMembersSpeedCaptureUpToTheConfiguredCeiling() {
        assertEquals(1.00D, MineKothControl.speedMultiplier(1, SETTINGS), 0.0001);
        assertEquals(1.25D, MineKothControl.speedMultiplier(2, SETTINGS), 0.0001);
        assertEquals(1.50D, MineKothControl.speedMultiplier(3, SETTINGS), 0.0001);
        assertEquals(2.00D, MineKothControl.speedMultiplier(5, SETTINGS), 0.0001);
        assertEquals(2.00D, MineKothControl.speedMultiplier(50, SETTINGS),
                0.0001, "members past the cap must add nothing");
    }

    @Test
    void multipleMemberSpeedUpCanBeTurnedOff() {
        MineKothControl.Settings off = new MineKothControl.Settings(180D, false, 0.25D, 5, 2.0D, 50D);
        assertEquals(1D, MineKothControl.speedMultiplier(5, off), 0.0001);
    }

    /** An attacker must wear the holder's control away before gaining their own. */
    @Test
    void anAttackerPushesTheOwnersControlDownFirst() {
        MineKothControl.Snapshot held = new MineKothControl.Snapshot(RED, 100D, null);

        MineKothControl.Result result = tick(held, Map.of(BLUE, 1), 90D);

        assertEquals(RED, result.ownerFactionId(), "the point does not change hands part-way");
        assertEquals(50D, result.controlPercent(), 0.0001);
        assertFalse(result.ownerChanged());
    }

    @Test
    void theOwnerLosesThePointOnlyOnceControlReachesZero() {
        MineKothControl.Snapshot held = new MineKothControl.Snapshot(RED, 100D, null);

        MineKothControl.Result result = tick(held, Map.of(BLUE, 1), 180D);

        assertNull(result.ownerFactionId(), "the point is unowned the moment it is worn away");
        assertTrue(result.lostControl());
        assertEquals(BLUE, result.capturingFactionId());
    }

    /** Taking a fully-held point costs the wear-down plus a full capture. */
    @Test
    void takingADefendedPointTakesTwiceAsLongAsAnEmptyOne() {
        MineKothControl.Snapshot state = new MineKothControl.Snapshot(RED, 100D, null);

        state = toSnapshot(tick(state, Map.of(BLUE, 1), 180D));
        MineKothControl.Result finished = tick(state, Map.of(BLUE, 1), 180D);

        assertEquals(BLUE, finished.ownerFactionId());
        assertTrue(finished.ownerChanged());
    }

    @Test
    void theOwnerStandingOnItRestoresControl() {
        MineKothControl.Snapshot worn = new MineKothControl.Snapshot(RED, 40D, BLUE);

        MineKothControl.Result result = tick(worn, Map.of(RED, 1), 90D);

        assertEquals(RED, result.ownerFactionId());
        assertEquals(90D, result.controlPercent(), 0.0001);
        assertEquals(MineKothControl.State.DEFENDING, result.state());
    }

    /**
     * The holder keeps the point while rivals fight over it, so a third
     * faction cannot be used as cover to take it from someone.
     */
    @Test
    void twoAttackersContestAndNeitherAdvances() {
        MineKothControl.Snapshot held = new MineKothControl.Snapshot(RED, 100D, null);

        MineKothControl.Result result = tick(held, Map.of(BLUE, 3, GREEN, 3), 60D);

        assertEquals(MineKothControl.State.CONTESTED, result.state());
        assertEquals(RED, result.ownerFactionId());
        assertEquals(100D, result.controlPercent(), 0.0001, "control must not move while contested");
    }

    @Test
    void anOwnerPresentAmongAttackersStillDefends() {
        MineKothControl.Snapshot worn = new MineKothControl.Snapshot(RED, 60D, BLUE);

        MineKothControl.Result result = tick(worn, Map.of(RED, 1, BLUE, 2, GREEN, 2), 36D);

        assertEquals(RED, result.ownerFactionId());
        assertTrue(result.controlPercent() > 60D, "the defender should be regaining ground");
    }

    @Test
    void contestedOnUnownedGroundAdvancesNobody() {
        MineKothControl.Snapshot partway = new MineKothControl.Snapshot(null, 30D, BLUE);

        MineKothControl.Result result = tick(partway, Map.of(BLUE, 1, GREEN, 1), 60D);

        assertEquals(MineKothControl.State.CONTESTED, result.state());
        assertEquals(30D, result.controlPercent(), 0.0001);
    }

    @Test
    void aDifferentFactionStartsFromZeroRatherThanInheritingProgress() {
        MineKothControl.Snapshot partway = new MineKothControl.Snapshot(null, 80D, BLUE);

        MineKothControl.Result result = tick(partway, Map.of(GREEN, 1), 18D);

        assertEquals(10D, result.controlPercent(), 0.0001,
                "GREEN must start their own climb, not continue BLUE's");
        assertEquals(GREEN, result.capturingFactionId());
    }

    @Test
    void reportsCrossingTheBoosterResetThreshold() {
        MineKothControl.Snapshot held = new MineKothControl.Snapshot(RED, 60D, null);

        MineKothControl.Result crossed = tick(held, Map.of(BLUE, 1), 36D);
        assertTrue(crossed.crossedResetThreshold(), "dropping from 60% to 40% crosses the 50% threshold");

        MineKothControl.Result notCrossed = tick(held, Map.of(BLUE, 1), 9D);
        assertFalse(notCrossed.crossedResetThreshold(), "55% is still above the threshold");
    }

    @Test
    void warnsTheHolderOnlyWhenControlCrossesTheftMilestones() {
        assertEquals(100, MineKothControl.theftWarningThreshold(100D, 99.5D));
        assertEquals(75, MineKothControl.theftWarningThreshold(76D, 75D));
        assertEquals(50, MineKothControl.theftWarningThreshold(51D, 50D));
        assertEquals(10, MineKothControl.theftWarningThreshold(11D, 10D));

        assertEquals(0, MineKothControl.theftWarningThreshold(75D, 74D),
                "the 75% notice was already sent when 75% was reached");
        assertEquals(0, MineKothControl.theftWarningThreshold(50D, 50D));
        assertEquals(0, MineKothControl.theftWarningThreshold(40D, 45D),
                "defending must not emit a theft notice");
    }

    @Test
    void controlNeverLeavesTheZeroToOneHundredRange() {
        MineKothControl.Result overshoot = tick(MineKothControl.Snapshot.UNOWNED, Map.of(RED, 5), 10_000D);
        assertEquals(100D, overshoot.controlPercent(), 0.0001);

        MineKothControl.Result wornOut = tick(new MineKothControl.Snapshot(RED, 100D, null),
                Map.of(BLUE, 5), 10_000D);
        assertTrue(wornOut.controlPercent() >= 0D && wornOut.controlPercent() <= 100D);
    }

    private static MineKothControl.Snapshot toSnapshot(MineKothControl.Result result) {
        return new MineKothControl.Snapshot(result.ownerFactionId(), result.controlPercent(),
                result.capturingFactionId());
    }
}
