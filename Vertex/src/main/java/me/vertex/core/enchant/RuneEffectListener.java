package me.vertex.core.enchant;

import me.vertex.core.pvp.CombatManager;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Config-driven live effects for normal Rune enchants. The effect type,
 * trigger values, priorities, proc rates, and per-level settings all live in
 * {@code enchants.yml}; this listener deliberately does not contain a second
 * pool of hard-coded Rune definitions.
 */
public final class RuneEffectListener implements Listener {
    private static final List<String> MOVEMENT_EFFECTS = List.of("SKY_STEPPER", "DASHER", "RIFTWALKER");

    private final EnchantManager manager;
    private final CombatManager combat;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

    public RuneEffectListener(EnchantManager manager, CombatManager combat) {
        this.manager = manager;
        this.combat = combat;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking() || player.isOnGround() || player.isFlying() || player.isGliding()
                || player.isInsideVehicle() || player.isInWater()) {
            return;
        }
        // The configured priority decides the order. Sky Stepper ships at a
        // higher priority than Dasher, so it always wins while ready; Dasher
        // remains a fallback if Sky Stepper is absent, blocked, or cooling down.
        for (ActiveRune rune : active(player, MOVEMENT_EFFECTS)) {
            if (blockedByCombat(player, rune) || onCooldown(player, rune)) {
                continue;
            }
            if (!procs(rune.level())) {
                continue;
            }
            if (activateMovement(player, rune)) {
                startCooldown(player, rune);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Material block = event.getBlock().getType();
        if (isOre(block)) {
            for (ActiveRune rune : active(player, List.of("ORE_FORTUNE"))) {
                if (procs(rune.level())) {
                    duplicateDrops(event, rune.level().abilityValue());
                }
            }
        }
        if (isCrop(block)) {
            for (ActiveRune rune : active(player, List.of("CROP_BOUNTY"))) {
                if (procs(rune.level())) {
                    duplicateDrops(event, rune.level().abilityValue());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            for (ActiveRune rune : active(victim, List.of("FALL_REDUCTION"))) {
                if (procs(rune.level())) {
                    event.setDamage(event.getDamage() * reductionMultiplier(rune.level().abilityValue()));
                }
            }
        }
        for (ActiveRune rune : active(victim, List.of("DAMAGE_REDUCTION"))) {
            if (procs(rune.level())) {
                event.setDamage(event.getDamage() * reductionMultiplier(rune.level().abilityValue()));
            }
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Projectile) {
            for (ActiveRune rune : active(victim, List.of("PROJECTILE_REDUCTION"))) {
                if (procs(rune.level())) {
                    event.setDamage(event.getDamage() * reductionMultiplier(rune.level().abilityValue()));
                }
            }
        }
        for (ActiveRune rune : active(victim, List.of("DEATH_GUARD"))) {
            if (onCooldown(victim, rune) || !procs(rune.level()) || event.getFinalDamage() < victim.getHealth()) {
                continue;
            }
            event.setCancelled(true);
            victim.setHealth(Math.min(victim.getMaxHealth(), Math.max(1D,
                    rune.level().setting("restore-health", 4D))));
            victim.setFireTicks(0);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                    (int) Math.max(1D, rune.level().setting("regeneration-seconds", 5D)) * 20, 1));
            startCooldown(victim, rune);
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        boolean projectile = event.getDamager() instanceof Projectile;
        for (ActiveRune rune : active(attacker, projectile ? List.of("PROJECTILE_DAMAGE") : List.of("MELEE_DAMAGE"))) {
            if (procs(rune.level())) {
                event.setDamage(event.getDamage() * (1D + Math.max(0D, rune.level().abilityValue()) / 100D));
            }
        }
        if (!projectile) {
            for (ActiveRune rune : active(attacker, List.of("EXECUTE"))) {
                if (!(event.getEntity() instanceof LivingEntity target) || !procs(rune.level())) {
                    continue;
                }
                double threshold = rune.level().setting("health-threshold-percent", 25D);
                if (target.getHealth() <= target.getMaxHealth() * threshold / 100D) {
                    event.setDamage(event.getDamage() * (1D + Math.max(0D, rune.level().abilityValue()) / 100D));
                }
            }
            for (ActiveRune rune : active(attacker, List.of("LIFESTEAL"))) {
                if (procs(rune.level())) {
                    attacker.setHealth(Math.min(attacker.getMaxHealth(), attacker.getHealth()
                            + event.getFinalDamage() * Math.max(0D, rune.level().abilityValue()) / 100D));
                }
            }
            for (ActiveRune rune : active(attacker, List.of("FIRE_STRIKE"))) {
                if (procs(rune.level()) && event.getEntity() instanceof LivingEntity target) {
                    target.setFireTicks(Math.max(target.getFireTicks(),
                            (int) Math.max(1D, rune.level().setting("fire-seconds", 2D)) * 20));
                }
            }
        }
        for (ActiveRune rune : active(attacker, List.of("LIGHTNING_STRIKE"))) {
            if (procs(rune.level()) && event.getEntity() instanceof LivingEntity target) {
                target.getWorld().strikeLightningEffect(target.getLocation());
                // Add the configured bonus to this one damage event rather
                // than calling target.damage again, which would recursively
                // re-enter this listener and could proc Lightning Strike a
                // second time.
                event.setDamage(event.getDamage() + Math.max(0D, rune.level().abilityValue()));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        for (ActiveRune rune : active(killer, List.of("XP_BONUS"))) {
            if (procs(rune.level())) {
                event.setDroppedExp(event.getDroppedExp() + (int) Math.floor(event.getDroppedExp()
                        * Math.max(0D, rune.level().abilityValue()) / 100D));
            }
        }
        for (ActiveRune rune : active(killer, List.of("KILL_HEAL"))) {
            if (procs(rune.level())) {
                killer.setHealth(Math.min(killer.getMaxHealth(), killer.getHealth() + killer.getMaxHealth()
                        * Math.max(0D, rune.level().abilityValue()) / 100D));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.remove(event.getPlayer().getUniqueId());
    }

    private boolean activateMovement(Player player, ActiveRune rune) {
        Vector horizontal = player.getLocation().getDirection().setY(0D);
        if (horizontal.lengthSquared() < 0.0001D) {
            horizontal = new Vector(0D, 0D, 1D);
        }
        horizontal.normalize();
        return switch (rune.definition().effect()) {
            case "SKY_STEPPER" -> {
                player.setVelocity(horizontal.multiply(rune.level().setting("forward-velocity", 0.25D))
                        .setY(rune.level().setting("upward-velocity", rune.level().abilityValue())));
                yield true;
            }
            case "DASHER" -> {
                player.setVelocity(horizontal.multiply(rune.level().setting("forward-velocity", rune.level().abilityValue()))
                        .setY(rune.level().setting("upward-velocity", 0.1D)));
                yield true;
            }
            case "RIFTWALKER" -> {
                player.setVelocity(horizontal.multiply(rune.level().setting("forward-velocity", rune.level().abilityValue()))
                        .setY(rune.level().setting("upward-velocity", 0.2D)));
                yield true;
            }
            default -> false;
        };
    }

    private List<ActiveRune> active(Player player, List<String> effects) {
        if (player == null || effects.isEmpty()) {
            return List.of();
        }
        Map<String, ActiveRune> highestByEnchant = new LinkedHashMap<>();
        for (ItemStack item : equipped(player)) {
            for (Map.Entry<String, Integer> entry : manager.enchantsOf(item).entrySet()) {
                EnchantDefinition definition = manager.definition(entry.getKey());
                if (definition == null || !effects.contains(definition.effect())
                        || !manager.isEffectActive(item, entry.getKey(), player.getWorld().getName())) {
                    continue;
                }
                EnchantDefinition.Level level = definition.level(entry.getValue());
                if (level == null) {
                    continue;
                }
                ActiveRune next = new ActiveRune(definition, level);
                ActiveRune existing = highestByEnchant.get(definition.id());
                if (existing == null || next.level().level() > existing.level().level()) {
                    highestByEnchant.put(definition.id(), next);
                }
            }
        }
        return highestByEnchant.values().stream()
                .sorted(Comparator.comparingInt((ActiveRune rune) -> rune.definition().priority()).reversed()
                        .thenComparing(rune -> rune.definition().id()))
                .toList();
    }

    private static List<ItemStack> equipped(Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (main != null && !main.getType().isAir()) items.add(main);
        if (off != null && !off.getType().isAir()) items.add(off);
        return items;
    }

    private boolean blockedByCombat(Player player, ActiveRune rune) {
        return rune.definition().blockedInCombat() && combat != null && combat.isTagged(player.getUniqueId());
    }

    private boolean onCooldown(Player player, ActiveRune rune) {
        return cooldowns.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(rune.definition().id(), 0L)
                > System.currentTimeMillis();
    }

    private void startCooldown(Player player, ActiveRune rune) {
        long duration = Math.max(0L, Math.round(rune.level().setting("cooldown-seconds", 0D) * 1000D));
        if (duration > 0L) {
            cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                    .put(rune.definition().id(), System.currentTimeMillis() + duration);
        }
    }

    private static boolean procs(EnchantDefinition.Level level) {
        return level.procChance() >= 100D || ThreadLocalRandom.current().nextDouble(100D) < level.procChance();
    }

    private static double reductionMultiplier(double percent) {
        return Math.max(0D, 1D - Math.max(0D, Math.min(100D, percent)) / 100D);
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player player) return player;
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player ? player : null;
    }

    private static boolean isOre(Material material) {
        return material != null && material.name().endsWith("_ORE");
    }

    private static boolean isCrop(Material material) {
        return material == Material.WHEAT || material == Material.CARROTS || material == Material.POTATOES
                || material == Material.BEETROOTS || material == Material.NETHER_WART || material == Material.COCOA;
    }

    private static void duplicateDrops(BlockBreakEvent event, double chancePercent) {
        if (chancePercent <= 0D || ThreadLocalRandom.current().nextDouble(100D) >= chancePercent) {
            return;
        }
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        for (ItemStack drop : event.getBlock().getDrops(tool, event.getPlayer())) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), drop.clone());
        }
    }

    private record ActiveRune(EnchantDefinition definition, EnchantDefinition.Level level) {
    }
}
