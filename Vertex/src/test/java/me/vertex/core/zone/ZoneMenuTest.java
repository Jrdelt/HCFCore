package me.vertex.core.zone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ZoneMenuTest {
    @Test
    void lootPaginationAlwaysHasAValidPageAndClampsStaleRequests() {
        assertEquals(1, ZoneMenu.lootPageCount(0));
        assertEquals(1, ZoneMenu.lootPageCount(45));
        assertEquals(2, ZoneMenu.lootPageCount(46));
        assertEquals(0, ZoneMenu.normalizeLootPage(-1, 46));
        assertEquals(1, ZoneMenu.normalizeLootPage(9, 46));
    }

    @Test
    void dropChanceDoesNotShowAnUnnecessaryDecimal() {
        assertEquals("20", ZoneMenu.formatChance(20D));
        assertEquals("0.25", ZoneMenu.formatChance(.25D));
    }
}
