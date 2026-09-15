package me.vertex.core.enchant.binds;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the single choke point every /binds mutation path shares: the 3-rune-per-bind cap, reordering, and rune pruning. */
class PlayerBindsTest {

    @Test
    void setSlotFillsContiguouslyAndRejectsAFourthRune() {
        PlayerBinds binds = new PlayerBinds();
        assertTrue(binds.setSlot(1, 0, "dasher"));
        assertTrue(binds.setSlot(1, 1, "sky_stepper"));
        assertTrue(binds.setSlot(1, 2, "riftwalker"));
        assertFalse(binds.setSlot(1, 3, "phoenix_heart"), "a bind may never exceed 3 slots");
        assertEquals(List.of("dasher", "sky_stepper", "riftwalker"), binds.bind(1));
    }

    @Test
    void setSlotRejectsAGapBeforeTheTargetSlot() {
        PlayerBinds binds = new PlayerBinds();
        assertFalse(binds.setSlot(1, 1, "dasher"), "slot 1 cannot be filled before slot 0");
        assertTrue(binds.bind(1).isEmpty());
    }

    @Test
    void loadBindTrimsLegacyOverflowInsteadOfExecutingExtras() {
        PlayerBinds binds = new PlayerBinds();
        binds.loadBind(1, List.of("dasher", "sky_stepper", "riftwalker", "phoenix_heart", "corrupted_detonation"), null);
        assertEquals(3, binds.bind(1).size(), "corrupt/legacy data must be safely capped, not executed in full");
        assertEquals(List.of("dasher", "sky_stepper", "riftwalker"), binds.bind(1));
    }

    @Test
    void reorderSwapsWithTheNeighborAndStopsAtTheEdges() {
        PlayerBinds binds = new PlayerBinds();
        binds.setSlot(1, 0, "dasher");
        binds.setSlot(1, 1, "sky_stepper");
        binds.setSlot(1, 2, "riftwalker");

        binds.reorder(1, 2, -1);
        assertEquals(List.of("dasher", "riftwalker", "sky_stepper"), binds.bind(1));

        binds.reorder(1, 0, -1);
        assertEquals(List.of("dasher", "riftwalker", "sky_stepper"), binds.bind(1), "moving earlier at the first slot is a no-op");
    }

    @Test
    void removeSlotShiftsLaterEntriesDown() {
        PlayerBinds binds = new PlayerBinds();
        binds.setSlot(1, 0, "dasher");
        binds.setSlot(1, 1, "sky_stepper");
        binds.setSlot(1, 2, "riftwalker");

        binds.removeSlot(1, 0);
        assertEquals(List.of("sky_stepper", "riftwalker"), binds.bind(1));
    }

    @Test
    void theSameRuneMayBeAssignedToMultipleBindKeys() {
        PlayerBinds binds = new PlayerBinds();
        assertTrue(binds.setSlot(1, 0, "dasher"));
        assertTrue(binds.setSlot(4, 0, "dasher"));
        assertEquals(List.of("dasher"), binds.bind(1));
        assertEquals(List.of("dasher"), binds.bind(4));
    }

    @Test
    void pruneRuneRemovesItFromEveryBindAndEveryPreset() {
        PlayerBinds binds = new PlayerBinds();
        binds.setSlot(1, 0, "dasher");
        binds.setSlot(1, 1, "sky_stepper");
        binds.setSlot(4, 0, "dasher");
        binds.loadPreset(2, Map.of(1, List.of("dasher", "sky_stepper")), null);

        assertTrue(binds.pruneRune("dasher"));

        assertEquals(List.of("sky_stepper"), binds.bind(1));
        assertTrue(binds.bind(4).isEmpty(), "a bind that only contained the pruned rune must end up empty");
        assertEquals(List.of("sky_stepper"), binds.preset(2).get(1));
    }

    @Test
    void loadingAPresetIntoTheLiveLayoutMarksItActiveAndClean() {
        PlayerBinds binds = new PlayerBinds();
        binds.loadPreset(1, Map.of(1, List.of("dasher")), null);

        binds.loadPresetIntoActive(1);

        assertEquals(List.of("dasher"), binds.bind(1));
        assertEquals(1, binds.activePresetIndex());
        assertFalse(binds.dirty());
    }

    @Test
    void editingAfterLoadingAPresetMarksItDirtyUntilExplicitlyUpdated() {
        PlayerBinds binds = new PlayerBinds();
        binds.loadPreset(1, Map.of(1, List.of("dasher")), null);
        binds.loadPresetIntoActive(1);

        binds.setSlot(1, 1, "sky_stepper");
        assertTrue(binds.dirty(), "changing the live layout away from its loaded preset must not be silently saved");

        binds.updateActivePresetFromLive();
        assertFalse(binds.dirty());
        assertEquals(List.of("dasher", "sky_stepper"), binds.preset(1).get(1));
    }

    @Test
    void deletingTheActivePresetClearsTheActiveMarker() {
        PlayerBinds binds = new PlayerBinds();
        binds.loadPreset(1, Map.of(1, List.of("dasher")), null);
        binds.loadPresetIntoActive(1);

        binds.deletePreset(1);

        assertNull(binds.activePresetIndex());
        assertTrue(binds.preset(1).isEmpty());
    }
}
