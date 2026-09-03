package me.hcfcore.core.tag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GradientColorTest {

    @Test
    void reversesTwoStopGradient() {
        assertEquals("<gradient:#f97316:#facc15>", GradientColor.reverse("<gradient:#facc15:#f97316>"));
    }

    @Test
    void reversesThreeStopGradient() {
        assertEquals("<gradient:#c:#b:#a>", GradientColor.reverse("<gradient:#a:#b:#c>"));
    }

    @Test
    void keepsTrailingPhaseParameterInPlace() {
        assertEquals("<gradient:#f97316:#facc15:0.5>", GradientColor.reverse("<gradient:#facc15:#f97316:0.5>"));
    }

    @Test
    void leavesSolidColorsUnchanged() {
        assertEquals("<light_purple>", GradientColor.reverse("<light_purple>"));
    }

    @Test
    void leavesSurroundingTextInPlace() {
        assertEquals("<gray>[</gray><gradient:#b:#a>Legend<gray>]</gray>",
                GradientColor.reverse("<gray>[</gray><gradient:#a:#b>Legend<gray>]</gray>"));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(GradientColor.reverse(null));
    }

    @Test
    void doubleReverseRoundTrips() {
        String original = "<gradient:#facc15:#f97316>";
        assertEquals(original, GradientColor.reverse(GradientColor.reverse(original)));
    }
}
