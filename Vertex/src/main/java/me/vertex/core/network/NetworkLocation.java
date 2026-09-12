package me.vertex.core.network;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** A world location that remains unambiguous when several shards use the same world name. */
public record NetworkLocation(String shardId, String world, double x, double y, double z,
                              float yaw, float pitch) {
    public NetworkLocation {
        shardId = normalize(shardId);
        if (shardId.isEmpty()) throw new IllegalArgumentException("shardId");
        world = world == null ? "" : world.trim();
        if (world.isEmpty()) throw new IllegalArgumentException("world");
    }

    public static NetworkLocation local(String shardId, Location location) {
        if (location == null || location.getWorld() == null) throw new IllegalArgumentException("location");
        return new NetworkLocation(shardId, location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch());
    }

    public Location resolveLocal(String localShardId) {
        if (!shardId.equalsIgnoreCase(normalize(localShardId))) return null;
        World resolved = Bukkit.getWorld(world);
        return resolved == null ? null : new Location(resolved, x, y, z, yaw, pitch);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
