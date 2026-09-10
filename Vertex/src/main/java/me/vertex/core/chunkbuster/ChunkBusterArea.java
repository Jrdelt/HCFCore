package me.vertex.core.chunkbuster;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure area-of-effect computation for every {@link ChunkBusterType} --
 * takes plain ints in, returns plain positions out, never touches a Bukkit
 * {@code World}/{@code Location}/{@code Block}. Kept free of Bukkit for the
 * same reason {@code MineRegenQueue} is: the ordering/coverage guarantees
 * can be unit-tested directly, with no MockBukkit world required.
 *
 * <p>Every type's footprint is the single chunk containing the placement
 * point -- see {@link ChunkBusterType}'s class doc for what differs between
 * them within that chunk.
 */
public final class ChunkBusterArea {

    private ChunkBusterArea() {
    }

    /** One block position to remove, in world coordinates. */
    public record BlockPos(int x, int y, int z) {
    }

    /**
     * @param placementX        world X of the block the item was used on
     * @param placementY        world Y of the block the item was used on
     * @param placementZ        world Z of the block the item was used on
     * @param minHeight         the world's minimum buildable Y (inclusive)
     * @param maxHeightExclusive the world's maximum buildable Y, exclusive
     *                          (i.e. {@code World.getMaxHeight()})
     * @return every position this type would remove, unfiltered -- callers
     *         are responsible for skipping protected/air blocks during
     *         actual removal
     */
    public static List<BlockPos> positions(ChunkBusterType type, int placementX, int placementY, int placementZ,
            int minHeight, int maxHeightExclusive) {
        int chunkX = placementX >> 4;
        int chunkZ = placementZ >> 4;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        List<BlockPos> positions = new ArrayList<>();
        switch (type) {
            case FULL -> addColumnRange(positions, minX, maxX, minZ, maxZ, minHeight, maxHeightExclusive - 1);
            case UPWARD -> addColumnRange(positions, minX, maxX, minZ, maxZ, placementY, maxHeightExclusive - 1);
            case DOWNWARD -> addColumnRange(positions, minX, maxX, minZ, maxZ, minHeight, placementY);
            case SINGLE_COLUMN -> {
                for (int y = minHeight; y < maxHeightExclusive; y++) {
                    positions.add(new BlockPos(placementX, y, placementZ));
                }
            }
        }
        return positions;
    }

    /** The chunk footprint (inclusive block bounds) a given placement point's operation locks -- every type's area lock. */
    public static int[] chunkFootprint(int placementX, int placementZ) {
        int chunkX = placementX >> 4;
        int chunkZ = placementZ >> 4;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        return new int[] { minX, minX + 15, minZ, minZ + 15 };
    }

    private static void addColumnRange(List<BlockPos> positions, int minX, int maxX, int minZ, int maxZ,
            int minY, int maxYInclusive) {
        if (minY > maxYInclusive) {
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxYInclusive; y++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
    }
}
