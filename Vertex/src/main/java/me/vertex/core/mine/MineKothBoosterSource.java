package me.vertex.core.mine;

import me.vertex.core.booster.BoosterCategory;
import me.vertex.core.booster.BoosterContribution;
import me.vertex.core.booster.BoosterSource;
import me.vertex.core.factions.FactionsHook;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The Ore Drop bonus a faction earns from holding a Mine KOTH.
 *
 * <p>Scoped tightly on purpose: it applies only in the mining world whose
 * point the faction holds, only while they hold it outright, and only to
 * members of that faction. Everywhere else it is reported as inactive with
 * the reason, so a player can see why they are not getting it.
 */
public final class MineKothBoosterSource implements BoosterSource {

    private static final String ID = "mine-koth";

    private final MineKothManager kothManager;

    public MineKothBoosterSource(MineKothManager kothManager) {
        this.kothManager = kothManager;
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
        MineKothDefinition definition = kothManager.definitionForWorld(player.getWorld().getName());
        if (definition == null) {
            // Not a mining world with a point, so there is nothing to report.
            return List.of();
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            return List.of(BoosterContribution.inactive(ID, category, 0D, "no-faction"));
        }
        double percent = kothManager.boosterPercent(player.getWorld().getName(), factionId);
        if (percent <= 0D) {
            return List.of(BoosterContribution.inactive(ID, category, 0D, "koth-not-held"));
        }
        return List.of(BoosterContribution.active(ID, category, percent));
    }
}
