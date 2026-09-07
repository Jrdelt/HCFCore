package me.vertex.core.blueprint;

import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure geometry coverage for the placement-preview particle box -- no
 * MockBukkit server needed since {@link Location} tolerates a null world
 * and none of this math ever reads it.
 */
class BlueprintOutlineTest {

    @Test
    void worldBoundsOffsetsByTheAnchorAndPadsTheMaxCornerByOne() {
        Location anchor = new Location(null, 100, 64, 200);
        // Width 3 (x: 0..2), height 3 (y: 1..3), depth 2 (z: 0..1).
        BlockVector3 relMin = BlockVector3.at(0, 1, 0);
        BlockVector3 relMax = BlockVector3.at(2, 3, 1);

        double[] box = BlueprintOutline.worldBounds(anchor, relMin, relMax);

        assertArrayEquals(new double[] { 100, 65, 200, 103, 68, 202 }, box);
    }

    @Test
    void worldBoundsIsOrderIndependent() {
        Location anchor = new Location(null, 0, 0, 0);
        BlockVector3 relMin = BlockVector3.at(0, 1, 0);
        BlockVector3 relMax = BlockVector3.at(2, 3, 1);

        double[] forward = BlueprintOutline.worldBounds(anchor, relMin, relMax);
        double[] reversed = BlueprintOutline.worldBounds(anchor, relMax, relMin);

        assertArrayEquals(forward, reversed);
    }

    @Test
    void worldBoundsAtTheOriginWithNoOffset() {
        Location anchor = new Location(null, 0, 0, 0);
        BlockVector3 relMin = BlockVector3.at(0, 0, 0);
        BlockVector3 relMax = BlockVector3.at(0, 0, 0);

        double[] box = BlueprintOutline.worldBounds(anchor, relMin, relMax);

        // A single block still yields a real (non-degenerate) 1x1x1 box.
        assertArrayEquals(new double[] { 0, 0, 0, 1, 1, 1 }, box);
    }

    @Test
    void edgePointsCoversAllEightCornersOfAUnitCube() {
        List<double[]> points = BlueprintOutline.edgePoints(0, 0, 0, 1, 1, 1, 1.0);
        Set<String> corners = points.stream().map(BlueprintOutlineTest::key).collect(Collectors.toSet());

        for (double x : new double[] { 0, 1 }) {
            for (double y : new double[] { 0, 1 }) {
                for (double z : new double[] { 0, 1 }) {
                    assertTrue(corners.contains(key(new double[] { x, y, z })),
                            "missing corner " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void edgePointsNeverProducesAnInteriorPoint() {
        List<double[]> points = BlueprintOutline.edgePoints(0, 0, 0, 4, 3, 2, 0.5);

        for (double[] p : points) {
            long boundaryAxes = 0;
            if (p[0] == 0 || p[0] == 4) boundaryAxes++;
            if (p[1] == 0 || p[1] == 3) boundaryAxes++;
            if (p[2] == 0 || p[2] == 2) boundaryAxes++;

            assertTrue(boundaryAxes >= 2,
                    "point " + key(p) + " is not on an edge (needs >= 2 boundary axes)");
        }
    }

    @Test
    void edgePointsCountMatchesTheFourEdgesPerAxisFormula() {
        // step 1.0 over [0,2]x[0,1]x[0,1] -> 3 x-samples, 2 y-samples, 2 z-samples.
        List<double[]> points = BlueprintOutline.edgePoints(0, 0, 0, 2, 1, 1, 1.0);

        assertEquals(4 * 3 + 4 * 2 + 4 * 2, points.size());
    }

    private static String key(double[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }
}
