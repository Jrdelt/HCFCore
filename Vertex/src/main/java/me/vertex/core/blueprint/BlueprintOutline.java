package me.vertex.core.blueprint;

import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry for the Blueprint placement-preview particle box: turning a
 * template's relative bounds into world-space corners, then into a set of
 * points tracing the box's 12 edges. Kept free of any Bukkit particle/player
 * calls so the coordinate math is unit-testable on its own.
 */
final class BlueprintOutline {

    private BlueprintOutline() {
    }

    /**
     * Converts a template's relative bounds into a world-space bounding box
     * that snugly encloses every block it will place, anchored the same way
     * {@link BlueprintManager#relativeBounds} offsets are applied elsewhere
     * (min/max are treated defensively, though they're already ordered).
     * The max corner is pushed one block further out, since a block at
     * relative offset N occupies world space [N, N+1) along each axis.
     *
     * @return {minX, minY, minZ, maxX, maxY, maxZ}
     */
    static double[] worldBounds(Location anchor, BlockVector3 relMin, BlockVector3 relMax) {
        int minX = Math.min(relMin.x(), relMax.x());
        int minY = Math.min(relMin.y(), relMax.y());
        int minZ = Math.min(relMin.z(), relMax.z());
        int maxX = Math.max(relMin.x(), relMax.x());
        int maxY = Math.max(relMin.y(), relMax.y());
        int maxZ = Math.max(relMin.z(), relMax.z());

        return new double[] {
                anchor.getBlockX() + minX,
                anchor.getBlockY() + minY,
                anchor.getBlockZ() + minZ,
                anchor.getBlockX() + maxX + 1,
                anchor.getBlockY() + maxY + 1,
                anchor.getBlockZ() + maxZ + 1,
        };
    }

    /** Points tracing the 12 edges of the box, spaced {@code step} blocks apart. */
    static List<double[]> edgePoints(double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ, double step) {
        List<double[]> points = new ArrayList<>();

        for (double x = minX; x <= maxX; x += step) {
            points.add(new double[] { x, minY, minZ });
            points.add(new double[] { x, minY, maxZ });
            points.add(new double[] { x, maxY, minZ });
            points.add(new double[] { x, maxY, maxZ });
        }
        for (double y = minY; y <= maxY; y += step) {
            points.add(new double[] { minX, y, minZ });
            points.add(new double[] { minX, y, maxZ });
            points.add(new double[] { maxX, y, minZ });
            points.add(new double[] { maxX, y, maxZ });
        }
        for (double z = minZ; z <= maxZ; z += step) {
            points.add(new double[] { minX, minY, z });
            points.add(new double[] { minX, maxY, z });
            points.add(new double[] { maxX, minY, z });
            points.add(new double[] { maxX, maxY, z });
        }

        return points;
    }
}
