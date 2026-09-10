package me.vertex.core.zone;

import me.vertex.core.booster.BoosterCategory;
import me.vertex.core.booster.BoosterContribution;
import me.vertex.core.booster.BoosterSource;
import org.bukkit.entity.Player;

import java.util.List;

/** Surfaces the active zone's progression/event bonus to the shared booster service. */
public final class ZoneBoosterSource implements BoosterSource {
    private final ZoneManager zones;
    public ZoneBoosterSource(ZoneManager zones) { this.zones = zones; }
    @Override public String id() { return "zone"; }
    @Override public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
        if (category != BoosterCategory.MOB_DROP) return List.of();
        ZoneType type = zones.isIn(player, ZoneType.RIFTLANDS) ? ZoneType.RIFTLANDS
                : zones.isIn(player, ZoneType.HAVEN) ? ZoneType.HAVEN : null;
        if (type == null) return List.of(BoosterContribution.inactive(id(), category, 0D, "outside-zone"));
        double percent = zones.progressionBoost(player, type) + zones.winnerBoost(player);
        return List.of(BoosterContribution.active(id(), category, percent));
    }
}
