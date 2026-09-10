package me.vertex.core.portal;

import org.bukkit.Location;

/** Inclusive portal trigger cuboid. Admins normally build the visual portal inside this volume. */
public record EntryPortal(String id, PortalTarget target, String world,
                          int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public EntryPortal {
        id = PortalTarget.normalize(id);
        if (id == null) throw new IllegalArgumentException("Portal id is required.");
        if (target == null) throw new IllegalArgumentException("Portal target is required.");
        int lowX=Math.min(minX,maxX), highX=Math.max(minX,maxX), lowY=Math.min(minY,maxY), highY=Math.max(minY,maxY), lowZ=Math.min(minZ,maxZ), highZ=Math.max(minZ,maxZ);
        minX=lowX;maxX=highX;minY=lowY;maxY=highY;minZ=lowZ;maxZ=highZ;
    }
    public boolean contains(Location location) { return location != null && location.getWorld() != null && world.equals(location.getWorld().getName()) && location.getBlockX()>=minX&&location.getBlockX()<=maxX&&location.getBlockY()>=minY&&location.getBlockY()<=maxY&&location.getBlockZ()>=minZ&&location.getBlockZ()<=maxZ; }
}
