package me.vertex.core.zone;

import me.vertex.core.booster.BoosterCategory;
import me.vertex.core.booster.BoosterContribution;
import me.vertex.core.booster.BoosterSource;
import org.bukkit.entity.Player;

import java.util.List;

/** Makes Arena KOTH and Outpost bonuses visible to the existing booster system. */
public final class ArenaControlBoosterSource implements BoosterSource {
    private final ArenaControlManager controls;
    public ArenaControlBoosterSource(ArenaControlManager controls) { this.controls = controls; }
    @Override public String id() { return "arena-control"; }
    @Override public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
        if (category != BoosterCategory.MOB_DROP && category != BoosterCategory.EXP
                && category != BoosterCategory.SELL && category != BoosterCategory.BUY_DISCOUNT) return List.of();
        double percent = controls.bonusFor(player, category);
        return percent <= 0D ? List.of() : List.of(BoosterContribution.active(id(), category, percent));
    }
}
