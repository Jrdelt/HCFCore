package me.vertex.core.zone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneRegionTest {
    @Test
    void touchingCuboidsAreRejectedAsOverlappingButDifferentWorldsAreIndependent() {
        ZoneRegion haven = new ZoneRegion("haven", ZoneType.HAVEN, "world", 0, 0, 0, 10, 10, 10);
        assertTrue(haven.overlaps(new ZoneRegion("rift", ZoneType.RIFTLANDS, "world", 10, 0, 10, 20, 10, 20)));
        assertFalse(haven.overlaps(new ZoneRegion("other", ZoneType.RIFTLANDS, "world_nether", 0, 0, 0, 10, 10, 10)));
    }

    @Test
    void normalizedBoundsAndIdsAreStable() {
        ZoneRegion region = new ZoneRegion(" My Region! ", ZoneType.HAVEN, "world", 10, 20, 30, 0, 0, 0);
        assertTrue(region.id().equals("myregion"));
        assertTrue(region.minX() == 0 && region.maxY() == 20 && region.minZ() == 0);
    }
}
