package me.vertex.core.booster;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;

/**
 * Applies active EXP boosters from {@link BoosterService} to all experience gained by players.
 * Stacks on top of all vanilla experience sources, mob drops, and custom enchants.
 */
public final class BoosterExpListener implements Listener {

    private final BoosterService boosterService;

    public BoosterExpListener(BoosterService boosterService) {
        this.boosterService = boosterService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerExpChange(PlayerExpChangeEvent event) {
        if (event.getAmount() <= 0) {
            return;
        }
        Player player = event.getPlayer();
        if (boosterService == null) {
            return;
        }
        double multiplier = boosterService.multiplier(player, BoosterCategory.EXP);
        if (multiplier > 1.0) {
            int original = event.getAmount();
            int boosted = (int) Math.min(Integer.MAX_VALUE, Math.round(original * multiplier));
            event.setAmount(boosted);
        }
    }
}
