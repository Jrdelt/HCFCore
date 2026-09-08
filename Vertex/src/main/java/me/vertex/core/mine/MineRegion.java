package me.vertex.core.mine;

import org.bukkit.Location;
import org.bukkit.Material;

import java.util.Set;

/**
 * One mining world's configured region and generation rules.
 *
 * <p>A mine with no {@code world} has not been placed yet -- it ships fully
 * configured but inactive until an admin selects its corners in-game.
 *
 * @param baseBlocks what ore is allowed to replace, so generation can never
 *                   overwrite arena structure
 */
public record MineRegion(
        String id,
        String displayName,
        String world,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        PvpMode pvp,
        Material icon,
        int regenDelaySeconds,
        Set<Material> baseBlocks,
        MineOreTable ores) {

    public enum PvpMode {
        /** PvP only inside the Mine KOTH capture zone. */
        KOTH_ZONE_ONLY,
        /** PvP everywhere in the world. */
        ENABLED
    }

    /** A mine that has not had its corners selected yet does nothing. */
    public boolean isDefined() {
        return world != null && !world.isBlank() && !ores.isEmpty();
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

    /** True for a block the mine generates, and therefore one players may break. */
    public boolean isMineBlock(Material material) {
        return ores.entry(material) != null || baseBlocks.contains(material);
    }

    /** True for the ore blocks specifically -- base blocks pay nothing. */
    public boolean isOre(Material material) {
        return ores.entry(material) != null && !baseBlocks.contains(material);
    }
}
