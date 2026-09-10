package me.vertex.core.portal;

import me.vertex.core.zone.ZoneRoute;
import java.util.List;

/** A route belongs to a destination, rather than a source portal, so multiple portals can share safe arrivals. */
public record PortalRoute(String id, PortalTarget target, double speed, List<ZoneRoute.Waypoint> waypoints) {
    public PortalRoute { id=PortalTarget.normalize(id); speed=Math.max(.05D,Math.min(8D,speed)); waypoints=List.copyOf(waypoints); }
}
