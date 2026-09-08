package me.vertex.core.booster;

import me.vertex.core.backpack.BackpackManager;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Surfaces the Backpack drop bonus that {@code BackpackManager} already
 * applies. It reads that manager's own figure rather than recomputing it, so
 * this cannot drift from the bonus a player actually receives, and the
 * Backpack's existing behaviour is untouched.
 *
 * <p>One Backpack feeds both drop categories: the same configured bonus is
 * what the manager applies to routed mining drops and mob drops alike.
 */
public final class BackpackBoosterSource implements BoosterSource {

    private static final String ID = "backpack";

    private final BackpackManager backpacks;

    public BackpackBoosterSource(BackpackManager backpacks) {
        this.backpacks = backpacks;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
        if (category != BoosterCategory.ORE_DROP && category != BoosterCategory.MOB_DROP) {
            return List.of();
        }
        if (!backpacks.isEnabled()) {
            return List.of();
        }
        double bonus = backpacks.equippedDropBonusPercent(player);
        if (bonus < 0D) {
            return List.of(BoosterContribution.inactive(ID, category, 0D, "no-backpack"));
        }
        return List.of(BoosterContribution.active(ID, category, bonus));
    }
}
