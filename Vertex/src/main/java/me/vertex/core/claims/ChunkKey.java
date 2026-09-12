package me.vertex.core.claims;

import org.bukkit.Chunk;
import org.bukkit.Location;

/** Shard/world-qualified chunk coordinate, used as the shared key for both claim tables. */
public record ChunkKey(String world, int x, int z) {
    private static final String SEPARATOR = "::";
    private static volatile String localShard = "";

    public ChunkKey {
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world");
    }

    /** Configured once before claim storage is loaded. Blank keeps legacy standalone keys. */
    public static void configureShard(String shardId) {
        localShard = normalize(shardId);
    }

    public static ChunkKey of(Location location) {
        return new ChunkKey(qualify(location.getWorld().getName()), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public static ChunkKey of(Chunk chunk) {
        return new ChunkKey(qualify(chunk.getWorld().getName()), chunk.getX(), chunk.getZ());
    }

    /** Reads old single-server rows as belonging to the shard performing the migration. */
    public static ChunkKey fromStorage(String storedWorld, int x, int z) {
        return new ChunkKey(storedWorld != null && storedWorld.contains(SEPARATOR)
                ? storedWorld : qualify(storedWorld), x, z);
    }

    public static ChunkKey at(String shardId, String worldName, int x, int z) {
        String shard = normalize(shardId);
        return new ChunkKey(shard.isBlank() ? qualify(worldName) : shard + SEPARATOR + worldName, x, z);
    }

    public String shardId() {
        int split = world.indexOf(SEPARATOR);
        return split < 0 ? localShard : world.substring(0, split);
    }

    public String localWorld() {
        int split = world.indexOf(SEPARATOR);
        return split < 0 ? world : world.substring(split + SEPARATOR.length());
    }

    public boolean isLocalShard() {
        return localShard.isBlank() || shardId().equalsIgnoreCase(localShard);
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

    private static String qualify(String worldName) {
        if (worldName == null || worldName.isBlank()) throw new IllegalArgumentException("world");
        return localShard.isBlank() || worldName.contains(SEPARATOR)
                ? worldName : localShard + SEPARATOR + worldName;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
