package me.vertex.core.faction;

import me.vertex.core.factions.FactionsHook;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Applies the non-FactionsUUID-native faction upgrades during normal play. */
public final class FactionUpgradeEffectsListener implements Listener {
    private final Plugin plugin;
    private final FactionUpgradeManager manager;
    /** Flight speed before Vertex touched it, so another plugin's value is restored exactly. */
    private final Map<UUID, Float> priorFlySpeeds = new ConcurrentHashMap<>();

    public FactionUpgradeEffectsListener(Plugin plugin, FactionUpgradeManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshFlightBoosts, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClaimDamage(EntityDamageByEntityEvent event) {
        Player attacker = playerDamager(event.getDamager());
        if (attacker == null || !manager.inOwnClaim(attacker, attacker.getLocation())) {
            return;
        }
        double bonus = manager.bonus(FactionsHook.getFactionId(attacker), FactionUpgrade.CLAIM_DAMAGE);
        if (bonus > 0D) {
            event.setDamage(event.getDamage() * (1D + bonus / 100D));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClaimProtection(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !manager.inOwnClaim(player, player.getLocation())) {
            return;
        }
        double reduction = manager.bonus(FactionsHook.getFactionId(player), FactionUpgrade.CLAIM_PROTECTION);
        if (reduction > 0D) {
            event.setDamage(event.getDamage() * (1D - Math.min(100D, reduction) / 100D));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallProtection(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)
                || !manager.inOwnClaim(player, player.getLocation())) {
            return;
        }
        double reduction = manager.bonus(FactionsHook.getFactionId(player), FactionUpgrade.FALL_PROTECTION);
        if (reduction > 0D) {
            event.setDamage(event.getDamage() * (1D - Math.min(100D, reduction) / 100D));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorWear(PlayerItemDamageEvent event) {
        Player player = event.getPlayer();
        if (!manager.inOwnClaim(player, player.getLocation())) {
            return;
        }
        double reduction = manager.bonus(FactionsHook.getFactionId(player), FactionUpgrade.ARMOR_WEAR);
        if (reduction > 0D) {
            // Armor normally loses one point per hit. Flooring that after a
            // 10% reduction would make every such hit cost zero instead of
            // reducing wear by 10%, so retain the fractional part randomly.
            double reduced = event.getDamage() * (1D - Math.min(100D, reduction) / 100D);
            int damage = (int) Math.floor(reduced);
            if (ThreadLocalRandom.current().nextDouble() < reduced - damage) {
                damage++;
            }
            event.setDamage(damage);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropGrow(BlockGrowEvent event) {
        Location location = event.getBlock().getLocation();
        int factionId = FactionsHook.getClaimFactionId(location);
        if (factionId == FactionsHook.NO_FACTION) {
            return;
        }
        double chance = manager.bonus(factionId, FactionUpgrade.CROP_GROWTH);
        if (chance <= 0D || ThreadLocalRandom.current().nextDouble(100D) >= Math.min(100D, chance)) {
            return;
        }
        BlockData next = event.getNewState().getBlockData();
        if (!(next instanceof Ageable ageable) || ageable.getAge() >= ageable.getMaximumAge()) {
            return;
        }
        Ageable accelerated = (Ageable) ageable.clone();
        accelerated.setAge(Math.min(accelerated.getMaximumAge(), accelerated.getAge() + 1));
        event.getNewState().setBlockData(accelerated);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobExperience(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || !manager.inOwnClaim(killer, event.getEntity().getLocation())) {
            return;
        }
        double bonus = manager.bonus(FactionsHook.getFactionId(killer), FactionUpgrade.MOB_XP);
        if (bonus > 0D && event.getDroppedExp() > 0) {
            event.setDroppedExp((int) Math.min(Integer.MAX_VALUE,
                    Math.round(event.getDroppedExp() * (1D + bonus / 100D))));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        restoreFlightSpeed(event.getPlayer());
    }

    private void refreshFlightBoosts() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                    || !player.isFlying() || !manager.inOwnClaim(player, player.getLocation())) {
                restoreFlightSpeed(player);
                continue;
            }
            double bonus = manager.bonus(FactionsHook.getFactionId(player), FactionUpgrade.FLY_BOOST);
            if (bonus <= 0D) {
                restoreFlightSpeed(player);
                continue;
            }
            priorFlySpeeds.putIfAbsent(player.getUniqueId(), player.getFlySpeed());
            float boosted = (float) Math.min(1D, priorFlySpeeds.get(player.getUniqueId()) * (1D + bonus / 100D));
            if (Math.abs(player.getFlySpeed() - boosted) > 0.0001F) {
                player.setFlySpeed(boosted);
            }
        }
    }

    private void restoreFlightSpeed(Player player) {
        Float prior = priorFlySpeeds.remove(player.getUniqueId());
        if (prior != null && Math.abs(player.getFlySpeed() - prior) > 0.0001F) {
            player.setFlySpeed(prior);
        }
    }

    private static Player playerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
