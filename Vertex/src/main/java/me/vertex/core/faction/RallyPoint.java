package me.vertex.core.faction;

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
        Location playerLoc = player.getLocation();
        if (playerLoc == null) return 0;
        return playerLoc.distance(location);
    }

}
