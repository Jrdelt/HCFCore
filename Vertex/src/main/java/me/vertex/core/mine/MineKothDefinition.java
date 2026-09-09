package me.vertex.core.mine;

import org.bukkit.Location;

/**
 * A Mine KOTH's capture zone and rules, as configured.
 *
 * <p>The zone is a sub-region of its mine, selected with the same blaze rod,
 * so it is placed in-game rather than typed as coordinates. Until it has
 * been, the KOTH is inactive.
 */
public record MineKothDefinition(
        String mineId,
        boolean enabled,
        String world,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        MineKothControl.Settings settings,
        MineKothBooster booster) {

    public boolean isDefined() {
        return enabled && world != null && !world.isBlank() && !(minX == 0 && maxX == 0 && minZ == 0 && maxZ == 0);
    }

    /**
     * Where the hologram floats: centred over the capture zone and lifted
     * clear of the floor, matching how scheduled KOTHs place theirs.
     *
     * <p>Derived from the bounds every time rather than stored, so moving the
     * zone moves the hologram with it instead of leaving one behind at the
     * old coordinates.
     */
    public Location hologramLocation() {
        if (!isDefined()) {
            return null;
        }
        org.bukkit.World bukkitWorld = org.bukkit.Bukkit.getWorld(world);
        return bukkitWorld == null ? null : new Location(bukkitWorld,
                (minX + maxX + 1D) / 2D, minY + 3.5D, (minZ + maxZ + 1D) / 2D);
    }

    /** Stable per-mine name so a restart updates its hologram rather than adding another. */
    public String hologramName() {
        return "vertex_mine_koth_" + mineId;
    }

    public boolean contains(Location location) {
        return location != null
                && location.getWorld() != null
                && isDefined()
                && location.getWorld().getName().equalsIgnoreCase(world)
                && location.getBlockX() >= minX && location.getBlockX() <= maxX
                && location.getBlockY() >= minY && location.getBlockY() <= maxY
                && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
    }
}
