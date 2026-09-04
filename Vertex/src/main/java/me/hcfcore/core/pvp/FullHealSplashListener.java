package me.hcfcore.core.pvp;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Splash potions of Healing (I or II -- the only levels Instant Health has)
 * hit everyone caught in the radius for the full effect, not the
 * distance-scaled fraction vanilla applies toward the edge of the splash.
 * Paper's {@code PotionSplashEvent#setIntensity} is exactly this override:
 * forcing 1.0 makes every affected entity receive the potion's effects at
 * full strength regardless of how far they were from the impact point.
 */
public final class FullHealSplashListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        boolean isHealingPotion = event.getPotion().getEffects().stream()
                .map(PotionEffect::getType)
                .anyMatch(PotionEffectType.INSTANT_HEALTH::equals);
        if (!isHealingPotion) {
            return;
        }
        for (LivingEntity affected : event.getAffectedEntities()) {
            event.setIntensity(affected, 1.0);
        }
    }
}
