package me.vertex.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AmountParserTest {

    @Test
    void parsesPlainWholeNumbers() {
        assertEquals(1L, AmountParser.parse("1"));
        assertEquals(1_000_000L, AmountParser.parse("1000000"));
    }

    @Test
    void parsesCommaGroupedNumbers() {
        assertEquals(1_000_000L, AmountParser.parse("1,000,000"));
    }

    @Test
    void parsesShorthandSuffixes() {
        assertEquals(100_000L, AmountParser.parse("100k"));
        assertEquals(100_000_000L, AmountParser.parse("100m"));
        assertEquals(1_000_000_000L, AmountParser.parse("1b"));
        assertEquals(1_500_000L, AmountParser.parse("1.5m"));
        assertEquals(1_500L, AmountParser.parse("1.5k"));
    }

    @Test
    void suffixesAreCaseInsensitive() {
        assertEquals(100_000L, AmountParser.parse("100K"));
        assertEquals(100_000_000L, AmountParser.parse("100M"));
        assertEquals(1_000_000_000L, AmountParser.parse("1B"));
    }

    @Test
    void toleratesSurroundingAndInternalWhitespace() {
        assertEquals(1_000L, AmountParser.parse("  1k  "));
        assertEquals(1_000_000L, AmountParser.parse("1 000 000"));
    }

    @Test
    void rejectsBlankNullZeroAndNegative() {
        assertNull(AmountParser.parse(null));
        assertNull(AmountParser.parse(""));
        assertNull(AmountParser.parse("   "));
        assertNull(AmountParser.parse("0"));
        assertNull(AmountParser.parse("-5"));
        assertNull(AmountParser.parse("-1k"));
    }

    @Test
    void rejectsGarbageAndBareSuffixes() {
        assertNull(AmountParser.parse("abc"));
        assertNull(AmountParser.parse("k"));
        assertNull(AmountParser.parse("m"));
        assertNull(AmountParser.parse("12x"));
    }

    @Test
    void rejectsNonFiniteValues() {
        assertNull(AmountParser.parse("Infinity"));
        assertNull(AmountParser.parse("NaN"));
    }

    @Test
    void roundsFractionalResultsToTheNearestWholeUnit() {
        // 1.005k = 1005.0 exactly, but a suffix producing a fractional
        // sub-unit result (e.g. 1.0005k = 1000.5) must still resolve to a
        // whole amount, since these are all whole-item/whole-currency counts.
        assertEquals(1L, AmountParser.parse("0.6"));
        assertEquals(1_001L, AmountParser.parse("1.0005k"));
    }
}
