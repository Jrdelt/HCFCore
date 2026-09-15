package me.vertex.core.faction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TntUnfillCommandTest {

    @Test
    void omittedRadiusUsesTheConfiguredDefaultAndNeverExceedsTheHardCap() {
        assertEquals(100, TntUnfillCommand.boundedRadius(7_511));
        assertEquals(64, TntUnfillCommand.parseRadius(new String[0], 64, 100));
    }

    @Test
    void onlyTheOptionalBankLiteralIsAcceptedAsADestination() {
        assertEquals(25, TntUnfillCommand.parseRadius(new String[]{"25", "bank"}, 100, 100));
        assertEquals(-1, TntUnfillCommand.parseRadius(new String[]{"25", "inventory"}, 100, 100));
        assertEquals(-1, TntUnfillCommand.parseRadius(new String[]{"101"}, 100, 100));
    }
}
