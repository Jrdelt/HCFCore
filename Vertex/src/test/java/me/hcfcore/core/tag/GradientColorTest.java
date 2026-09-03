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

    @Test
    void extractsLeadingMiniMessageColor() {
        assertEquals("<gradient:#facc15:#f97316>",
                GradientColor.extractLeadingColor("<gradient:#facc15:#f97316>Legend"));
        assertEquals("Legend", GradientColor.stripLeadingColor("<gradient:#facc15:#f97316>Legend"));
    }

    @Test
    void extractsLeadingLegacyColorCodes() {
        assertEquals("&a&l", GradientColor.extractLeadingColor("&a&lNewcomer"));
        assertEquals("Newcomer", GradientColor.stripLeadingColor("&a&lNewcomer"));
    }

    @Test
    void extractsLeadingLegacyHexCode() {
        assertEquals("&#facc15", GradientColor.extractLeadingColor("&#facc15Legend"));
        assertEquals("Legend", GradientColor.stripLeadingColor("&#facc15Legend"));
    }

    @Test
    void leadingColorIsNullForPlainText() {
        assertNull(GradientColor.extractLeadingColor("Newcomer"));
        assertEquals("Newcomer", GradientColor.stripLeadingColor("Newcomer"));
    }

    @Test
    void leadingColorHandlesNullInput() {
        assertNull(GradientColor.extractLeadingColor(null));
        assertNull(GradientColor.stripLeadingColor(null));
    }
}
