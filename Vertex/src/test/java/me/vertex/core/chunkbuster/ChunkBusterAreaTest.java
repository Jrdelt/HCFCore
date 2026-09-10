package me.vertex.core.chunkbuster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure area-of-effect coverage for every {@link ChunkBusterType}, entirely
 * decoupled from Bukkit (see the class doc on {@link ChunkBusterArea}) --
 * a small 0..3 height range keeps the enumerated position lists small
 * enough to assert on directly rather than only checking sizes.
 */
class ChunkBusterAreaTest {

    private static final int MIN_HEIGHT = 0;
    private static final int MAX_HEIGHT_EXCLUSIVE = 3; // valid Y: 0, 1, 2

    @Test
    void fullCoversEveryColumnInTheChunkAtEveryHeight() {
        List<ChunkBusterArea.BlockPos> positions =
                ChunkBusterArea.positions(ChunkBusterType.FULL, 5, 1, 5, MIN_HEIGHT, MAX_HEIGHT_EXCLUSIVE);

        assertEquals(16 * 16 * 3, positions.size());
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(0, 0, 0)), "chunk's near corner, bottom");
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(15, 2, 15)), "chunk's far corner, top");
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(5, 1, 5)), "placement point itself");
        assertFalse(positions.contains(new ChunkBusterArea.BlockPos(16, 0, 0)), "must not spill into the next chunk");
    }

    @Test
    void upwardOnlyCoversFromThePlacementBlockUpward() {
        List<ChunkBusterArea.BlockPos> positions =
                ChunkBusterArea.positions(ChunkBusterType.UPWARD, 5, 1, 5, MIN_HEIGHT, MAX_HEIGHT_EXCLUSIVE);

        assertEquals(16 * 16 * 2, positions.size(), "y in [1, 2] across the whole chunk");
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(5, 1, 5)), "placement block is included");
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(5, 2, 5)));
        assertFalse(positions.contains(new ChunkBusterArea.BlockPos(5, 0, 5)), "below the placement point must be untouched");
    }

    @Test
    void downwardOnlyCoversUpToThePlacementBlock() {
        List<ChunkBusterArea.BlockPos> positions =
                ChunkBusterArea.positions(ChunkBusterType.DOWNWARD, 5, 1, 5, MIN_HEIGHT, MAX_HEIGHT_EXCLUSIVE);

        assertEquals(16 * 16 * 2, positions.size(), "y in [0, 1] across the whole chunk");
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(5, 0, 5)));
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(5, 1, 5)), "placement block is included");
        assertFalse(positions.contains(new ChunkBusterArea.BlockPos(5, 2, 5)), "above the placement point must be untouched");
    }

    @Test
    void singleColumnOnlyCoversTheOneColumnFullHeight() {
        List<ChunkBusterArea.BlockPos> positions =
                ChunkBusterArea.positions(ChunkBusterType.SINGLE_COLUMN, 5, 1, 5, MIN_HEIGHT, MAX_HEIGHT_EXCLUSIVE);

        assertEquals(3, positions.size(), "the whole height range, but only one column -- not the whole chunk");
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(5, 0, 5)));
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(5, 1, 5)));
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(5, 2, 5)));
        assertFalse(positions.contains(new ChunkBusterArea.BlockPos(6, 1, 5)), "neighboring column must be untouched");
    }

    @Test
    void negativeCoordinatesResolveToTheCorrectChunk() {
        // Chunk containing x=-1 spans blocks [-16, -1] (Java's >> rounds toward negative infinity).
        List<ChunkBusterArea.BlockPos> positions =
                ChunkBusterArea.positions(ChunkBusterType.FULL, -1, 0, -1, MIN_HEIGHT, MAX_HEIGHT_EXCLUSIVE);

        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(-16, 0, -16)));
        assertTrue(positions.contains(new ChunkBusterArea.BlockPos(-1, 0, -1)));
        assertFalse(positions.contains(new ChunkBusterArea.BlockPos(0, 0, 0)), "must not spill into the neighboring (positive) chunk");
    }

    @Test
    void chunkFootprintMatchesThePositionsBounds() {
        int[] footprint = ChunkBusterArea.chunkFootprint(20, 20); // chunk (1, 1) -> [16,31]x[16,31]
        assertEquals(16, footprint[0]);
        assertEquals(31, footprint[1]);
        assertEquals(16, footprint[2]);
        assertEquals(31, footprint[3]);
    }
}
