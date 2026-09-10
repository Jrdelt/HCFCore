package me.vertex.core.claims;

import org.bukkit.Chunk;
import org.bukkit.Location;

/** World-qualified chunk coordinate, used as the shared key for both claim tables. */
public record ChunkKey(String world, int x, int z) {

    public static ChunkKey of(Location location) {
        return new ChunkKey(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public static ChunkKey of(Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
    }

    public ChunkKey north() {
        return new ChunkKey(world, x, z - 1);
    }

    public ChunkKey south() {
        return new ChunkKey(world, x, z + 1);
    }

    public ChunkKey east() {
        return new ChunkKey(world, x + 1, z);
    }

    public ChunkKey west() {
        return new ChunkKey(world, x - 1, z);
    }

    public java.util.List<ChunkKey> neighbors() {
        return java.util.List.of(north(), south(), east(), west());
    }
}
