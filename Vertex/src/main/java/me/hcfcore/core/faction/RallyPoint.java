package me.hcfcore.core.faction;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class RallyPoint {
    private final int factionId;
    private final Location location;
    private final long timestamp;

    public RallyPoint(int factionId, Location location, long timestamp) {
        this.factionId = factionId;
        this.location = location;
        this.timestamp = timestamp;
    }

    public int getFactionId() {
        return factionId;
    }

    public Location getLocation() {
        return location;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getDistance(Player player) {
        return player.getLocation().distance(location);
    }

    public float getYawTowards(Player player) {
        Location from = player.getLocation();
        double dx = location.getX() - from.getX();
        double dz = location.getZ() - from.getZ();
        float yaw = (float) Math.atan2(-dx, dz);
        yaw = (float) Math.toDegrees(yaw);
        return yaw;
    }
}
