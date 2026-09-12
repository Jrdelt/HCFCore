package me.vertex.core.enchant;

import me.vertex.core.booster.BoosterCategory;
import me.vertex.core.booster.BoosterContribution;
import me.vertex.core.booster.BoosterSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Applies live Arena Rune effects after the shared Rune listener adds them to equipment. */
public final class ArenaRuneListener implements Listener {
    private final ArenaRuneManager manager;

    public ArenaRuneListener(ArenaRuneManager manager) {
        this.manager = manager;
    }

    public BoosterSource boosterSource() {
        return new BoosterSource() {
            @Override public String id() { return "arena-runes"; }
            @Override public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
                if (category != BoosterCategory.MOB_DROP || !manager.inArena(player)) {
                    return List.of();
                }
                return List.of(BoosterContribution.active(id(), category,
                        manager.equippedValue(player, ArenaRuneManager.Effect.ABYSSAL_SCAVENGER)));
            }
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getDamager() instanceof Player attacker) {
            if (!manager.inArena(attacker) || !manager.inArena(victim)) return;
            double strike = manager.equippedValue(attacker, ArenaRuneManager.Effect.ECLIPSE_STRIKE);
            double ward = manager.equippedValue(victim, ArenaRuneManager.Effect.ECLIPSE_WARD);
            event.setDamage(event.getDamage() * (1D + strike / 100D) * Math.max(0D, 1D - ward / 100D));
            return;
        }
        if (manager.inArena(victim) && manager.inArena(event.getDamager().getLocation())) {
            double shield = manager.equippedValue(victim, ArenaRuneManager.Effect.VOID_SHIELD);
            event.setDamage(event.getDamage() * Math.max(0D, 1D - shield / 100D));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player player = event.getEntity().getKiller();
        if (player == null || !manager.inArena(player) || !manager.inArena(event.getEntity().getLocation())) return;
        double insight = manager.equippedValue(player, ArenaRuneManager.Effect.ABYSSAL_INSIGHT);
        if (insight > 0D) {
            event.setDroppedExp(event.getDroppedExp() + (int) Math.floor(event.getDroppedExp() * insight / 100D));
        }
        double feast = manager.equippedValue(player, ArenaRuneManager.Effect.ABYSSAL_FEAST);
        if (feast > 0D) {
            player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + player.getMaxHealth() * feast / 100D));
        }
        double detonation = manager.detonationPercent(player);
        if (detonation <= 0D || ThreadLocalRandom.current().nextDouble(100D) >= 30D) return;
        double damage = event.getEntity().getMaxHealth() * detonation / 100D;
        for (Entity entity : event.getEntity().getNearbyEntities(manager.detonationRadius(), manager.detonationRadius(),
                manager.detonationRadius())) {
            if (!(entity instanceof LivingEntity target) || target instanceof Player || !manager.inArena(target.getLocation())) continue;
            target.damage(damage, player);
        }
    }
}
