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
