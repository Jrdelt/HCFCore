package me.vertex.core.mine;

import me.vertex.core.booster.BoosterCategory;
import me.vertex.core.booster.BoosterContribution;
import me.vertex.core.booster.BoosterSource;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The extra Ore Drop bonus for mining in a world that is currently hot.
 *
 * <p>A Hot Zone covers a whole mining world, so being in that world is the
 * only condition -- there is no sub-region to stand in.
 */
public final class HotZoneBoosterSource implements BoosterSource {

    private static final String ID = "hot-zone";

    private final MineManager mines;
    private final HotZoneManager hotZones;

    public HotZoneBoosterSource(MineManager mines, HotZoneManager hotZones) {
        this.mines = mines;
        this.hotZones = hotZones;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
        if (category != BoosterCategory.ORE_DROP) {
            return List.of();
        }
        String world = player.getWorld().getName();
        boolean inAMiningWorld = mines.regions().stream()
                .anyMatch(region -> region.isDefined() && region.world().equalsIgnoreCase(world));
        if (!inAMiningWorld) {
            return List.of();
        }
        if (hotZones.activeMineInWorld(world) == null) {
            return List.of(BoosterContribution.inactive(ID, category, hotZones.oreDropPercent(), "no-hot-zone"));
        }
        return List.of(BoosterContribution.active(ID, category, hotZones.oreDropPercent()));
    }
}
