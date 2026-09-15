package me.vertex.core.zone;

import org.bukkit.Location;

import java.util.List;
import java.util.Objects;

/** A validated server-authoritative guided-entry route. */
public record ZoneRoute(String id, String regionId, boolean enabled, double speed, boolean autoDrop,
                        List<Waypoint> waypoints) {
    public ZoneRoute {
        id = ZoneRegion.normalizeId(id);
        regionId = ZoneRegion.normalizeId(regionId);
        if (!Double.isFinite(speed)) throw new IllegalArgumentException("Zone route speed must be finite");
        speed = Math.max(0.05D, Math.min(8D, speed));
        waypoints = List.copyOf(Objects.requireNonNullElse(waypoints, List.of()));
    }

    public record Waypoint(String world, double x, double y, double z, float yaw, float pitch) {
        public Waypoint {
            if(world==null||world.isBlank()||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                    ||!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("Invalid route waypoint");
        }
        public Location location(org.bukkit.World resolvedWorld) {
            return new Location(resolvedWorld, x, y, z, yaw, pitch);
        }
    }
}
