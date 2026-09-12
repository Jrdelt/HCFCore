package me.vertex.core.booster;

import me.vertex.core.faction.FactionUpgrade;
import me.vertex.core.faction.FactionUpgradeManager;
import me.vertex.core.factions.FactionsHook;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Surfaces the faction upgrades that already grant a percentage bonus, read
 * straight from {@code FactionUpgradeManager} so the tracker quotes the same
 * number the upgrade effects apply. The upgrades themselves are unchanged.
 *
 * <p>Only the upgrades that map onto a booster category appear here. The
 * rest (claim damage, armour wear, warps, TNT bank capacity, and so on) are
 * not percentage bonuses of these kinds and are deliberately left out rather
 * than forced into a category they do not belong to.
 */
public final class FactionUpgradeBoosterSource implements BoosterSource {

    private static final String ID = "faction-upgrade";

    private final FactionUpgradeManager upgrades;

    public FactionUpgradeBoosterSource(FactionUpgradeManager upgrades) {
        this.upgrades = upgrades;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
        FactionUpgrade upgrade = upgradeFor(category);
        if (upgrade == null || !upgrades.isEnabled() || !upgrades.definition(upgrade).enabled()) {
            return List.of();
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            return List.of(BoosterContribution.inactive(ID, category, 0D, "no-faction"));
        }
        double bonus = upgrades.bonus(factionId, upgrade);
        if (bonus <= 0D) {
            return List.of(BoosterContribution.inactive(ID, category, 0D, "not-upgraded"));
        }
        return List.of(BoosterContribution.active(ID, category, bonus));
    }

    private static FactionUpgrade upgradeFor(BoosterCategory category) {
        return switch (category) {
            case MOB_SPAWN_RATE -> FactionUpgrade.SPAWNER_RATE;
            case EXP -> FactionUpgrade.MOB_XP;
            case ORE_DROP, MOB_DROP, SELL, BUY_DISCOUNT -> null;
        };
    }
}
