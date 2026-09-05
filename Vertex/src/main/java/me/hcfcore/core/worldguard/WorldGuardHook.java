package me.hcfcore.core.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * Thin wrapper around WorldGuard's region query API. Every check first
 * confirms WorldGuard is actually enabled, so referencing its classes below
 * that guard never happens -- and never throws NoClassDefFoundError -- on a
 * server that doesn't have it installed.
 */
public final class WorldGuardHook {

    private WorldGuardHook() {
    }

    public static boolean isInDisabledRegion(Player player, Set<String> disabledRegionIds) {
        return isInDisabledRegion(player.getLocation(), disabledRegionIds);
    }

    public static boolean isInDisabledRegion(Location location, Set<String> disabledRegionIds) {
        if (disabledRegionIds.isEmpty() || !Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            return false;
        }
        return isInAnyRegion(location, disabledRegionIds);
    }

    private static boolean isInAnyRegion(Location location, Set<String> regionIds) {
        RegionQuery query = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
        Iterable<ProtectedRegion> regions = query.getApplicableRegions(BukkitAdapter.adapt(location));
        if (regions == null) {
            return false;
        }
        for (ProtectedRegion region : regions) {
            if (regionIds.contains(region.getId())) {
                return true;
            }
        }
        return false;
    }
}
