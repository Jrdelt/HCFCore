package me.vertex.core.grace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DurationParserTest {
    @Test void parsesCompoundDuration() { assertEquals(216_000, DurationParser.parseSeconds("2d12h")); }
    @Test void rejectsGapsAndUnknownUnits() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("2d-nope"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("0s"));
    }
    @Test void formatsDuration() { assertEquals("2d 12h", DurationParser.format(216_000)); }
}
