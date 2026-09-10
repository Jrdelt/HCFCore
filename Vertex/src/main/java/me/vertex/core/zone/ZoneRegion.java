package me.vertex.core.zone;

import org.bukkit.Location;

import java.util.Locale;
import java.util.Objects;

/** Immutable, inclusive cuboid definition for a named Haven/Riftlands arena. */
public record ZoneRegion(String id, ZoneType type, String world,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public ZoneRegion {
        id = normalizeId(id);
        Objects.requireNonNull(type, "type");
        world = Objects.requireNonNullElse(world, "");
        int lowX = Math.min(minX, maxX), highX = Math.max(minX, maxX);
        int lowY = Math.min(minY, maxY), highY = Math.max(minY, maxY);
        int lowZ = Math.min(minZ, maxZ), highZ = Math.max(minZ, maxZ);
        minX = lowX; maxX = highX;
        minY = lowY; maxY = highY;
        minZ = lowZ; maxZ = highZ;
    }

    public boolean contains(Location location) {
        return location != null && location.getWorld() != null && world.equals(location.getWorld().getName())
                && location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockY() >= minY && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }

    public boolean overlaps(ZoneRegion other) {
        return other != null && world.equals(other.world)
                && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    public static String normalizeId(String input) {
        if (input == null) return null;
        String result = input.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return result.isBlank() ? null : result;
    }
}
