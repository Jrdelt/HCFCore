package me.hcfcore.core.pvp;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Egg;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Restores a handful of pre-1.9 PvP mechanics that got reworked away over
 * the years: fixed attack speed (no cooldown bar), no sweeping-edge damage,
 * a configurable weapon-damage table, armor without the modern toughness/
 * damage-penetration mechanic, no offhand, tunable knockback (including on
 * normally-harmless snowball/egg/fishing-rod hits), flat slow health regen
 * instead of saturation-boosted fast regen, and admin-defined golden apple
 * effects instead of vanilla's current ones. Every sub-feature has its own
 * on/off switch under {@code pvp.legacy-combat} and only applies in the
 * configured worlds (empty list = everywhere).
 */
public final class LegacyCombatManager implements Listener {

    private static final double VANILLA_ATTACK_SPEED = 4.0;
    private static final double VANILLA_ARMOR_TOUGHNESS_BASE = 0.0;
    private static final long HEALTH_REGEN_INTERVAL_TICKS = 80L; // 4 seconds
    private static final double HEALTH_REGEN_AMOUNT = 2.0; // 1 heart

    private final Plugin plugin;
    private boolean enabled;
    private boolean disableSweepingAttacks;
    private double attackSpeed;
    private Set<String> worlds = Set.of();

    private boolean legacyWeaponDamage;
    private Map<Material, Double> weaponDamage = Map.of();

    private boolean legacyArmorCalculations;

    private boolean disableOffhand;

    private double knockbackHorizontal;
    private double knockbackVertical;
    private double knockbackSprintBonus;

    private boolean projectileKnockbackFishingRod;
    private boolean projectileKnockbackSnowball;
    private boolean projectileKnockbackEgg;

    private boolean legacyHealthRegen;
    private BukkitTask healthRegenTask;

    private boolean legacyGoldenApples;
    private List<PotionEffect> goldenAppleEffects = List.of();
    private List<PotionEffect> enchantedGoldenAppleEffects = List.of();

    public LegacyCombatManager(Plugin plugin) {
        this.plugin = plugin;
        reconfigure();
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
        restartHealthRegenTask();
    }

    public void reconfigure() {
        enabled = plugin.getConfig().getBoolean("pvp.legacy-combat.enabled", true);
        disableSweepingAttacks = plugin.getConfig().getBoolean("pvp.legacy-combat.disable-sweeping-attacks", true);
        attackSpeed = Math.max(VANILLA_ATTACK_SPEED,
                plugin.getConfig().getDouble("pvp.legacy-combat.attack-speed", 1024.0));
        List<String> configuredWorlds = plugin.getConfig().getStringList("pvp.legacy-combat.worlds");
        Set<String> worldSet = new HashSet<>();
        for (String world : configuredWorlds) {
            worldSet.add(world.toLowerCase(Locale.ROOT));
        }
        worlds = worldSet;

        legacyWeaponDamage = plugin.getConfig().getBoolean("pvp.legacy-combat.legacy-weapon-damage", false);
        weaponDamage = readWeaponDamage();

        legacyArmorCalculations = plugin.getConfig().getBoolean("pvp.legacy-combat.legacy-armor-calculations", false);

        disableOffhand = plugin.getConfig().getBoolean("pvp.legacy-combat.disable-offhand", false);

        knockbackHorizontal = plugin.getConfig().getDouble("pvp.legacy-combat.knockback.horizontal", 0.4);
        knockbackVertical = plugin.getConfig().getDouble("pvp.legacy-combat.knockback.vertical", 0.4);
        knockbackSprintBonus = plugin.getConfig().getDouble("pvp.legacy-combat.knockback.sprint-bonus", 0.1);

        projectileKnockbackFishingRod =
                plugin.getConfig().getBoolean("pvp.legacy-combat.projectile-knockback.fishing-rod", false);
        projectileKnockbackSnowball =
                plugin.getConfig().getBoolean("pvp.legacy-combat.projectile-knockback.snowball", false);
        projectileKnockbackEgg =
                plugin.getConfig().getBoolean("pvp.legacy-combat.projectile-knockback.egg", false);

        legacyHealthRegen = plugin.getConfig().getBoolean("pvp.legacy-combat.legacy-health-regen", false);

        legacyGoldenApples = plugin.getConfig().getBoolean("pvp.legacy-combat.legacy-golden-apples", false);
        goldenAppleEffects = readPotionEffects("pvp.legacy-combat.golden-apple-effects");
        enchantedGoldenAppleEffects = readPotionEffects("pvp.legacy-combat.enchanted-golden-apple-effects");

        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
        restartHealthRegenTask();
    }

    private Map<Material, Double> readWeaponDamage() {
        Map<Material, Double> table = new EnumMap<>(Material.class);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("pvp.legacy-combat.weapon-damage");
        if (section == null) {
            return table;
        }
        for (String key : section.getKeys(false)) {
            try {
                Material material = Material.valueOf(key.toUpperCase(Locale.ROOT));
                table.put(material, section.getDouble(key));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING,
                        "Unknown material '" + key + "' in pvp.legacy-combat.weapon-damage", e);
            }
        }
        return table;
    }

    private List<PotionEffect> readPotionEffects(String path) {
        List<?> raw = plugin.getConfig().getList(path);
        if (raw == null) {
            return List.of();
        }
        List<PotionEffect> effects = new ArrayList<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object typeValue = map.get("type");
            if (typeValue == null) {
                continue;
            }
            PotionEffectType type = Registry.EFFECT.get(
                    NamespacedKey.minecraft(String.valueOf(typeValue).toLowerCase(Locale.ROOT)));
            if (type == null) {
                plugin.getLogger().warning("Unknown potion effect type '" + typeValue + "' in " + path);
                continue;
            }
            int amplifier = Math.max(0, asInt(map.get("amplifier"), 0));
            int durationSeconds = Math.max(1, asInt(map.get("duration-seconds"), 5));
            effects.add(new PotionEffect(type, durationSeconds * 20, amplifier, false, true));
        }
        return effects;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        apply(event.getPlayer());
    }

    /**
     * Armor slot changes affect the toughness-cancellation trick in
     * apply(), so it needs recomputing the moment a piece goes on or off,
     * not just on join/world-change.
     */
    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onSweepDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                && disableSweepingAttacks
                && event.getEntity().getWorld() != null
                && isEnabledIn(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (disableOffhand && isEnabledIn(event.getPlayer().getWorld())) {
            event.setCancelled(true);
        }
    }

    /**
     * Weapon-damage-table override and legacy knockback for a direct melee
     * hit. Armor-toughness cancellation lives in apply() instead, since
     * it's an attribute recomputed on gear change, not something to
     * recalculate per hit.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeleeDamage(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        if (!(event.getDamager() instanceof Player attacker) || !isEnabledIn(attacker.getWorld())) {
            return;
        }
        if (legacyWeaponDamage) {
            ItemStack weapon = attacker.getInventory().getItemInMainHand();
            Double configured = weaponDamage.get(weapon.getType());
            if (configured != null) {
                event.setDamage(EntityDamageEvent.DamageModifier.BASE, configured);
            }
        }
        if (event.getEntity() instanceof LivingEntity victim) {
            applyLegacyKnockback(attacker, victim);
        }
    }

    /**
     * Restores the pre-1.9 trick of snowball/egg/fishing-rod hits pushing a
     * player even though they deal no damage -- modern Minecraft skips
     * knockback on those hits entirely. ProjectileHitEvent fires regardless
     * of damage dealt, unlike EntityDamageByEntityEvent which a harmless
     * projectile hit may never trigger.
     */
    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player shooter) || !isEnabledIn(shooter.getWorld())) {
            return;
        }
        if (!(event.getHitEntity() instanceof LivingEntity victim) || victim.equals(shooter)) {
            return;
        }
        boolean applicable = (projectile instanceof Snowball && projectileKnockbackSnowball)
                || (projectile instanceof Egg && projectileKnockbackEgg)
                || (projectile instanceof FishHook && projectileKnockbackFishingRod);
        if (!applicable) {
            return;
        }
        applyLegacyKnockback(shooter, victim);
    }

    private void applyLegacyKnockback(Player attacker, LivingEntity victim) {
        Vector direction = victim.getLocation().toVector().subtract(attacker.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() < 1.0E-4) {
            direction = attacker.getLocation().getDirection();
        }
        direction.setY(0);
        if (direction.lengthSquared() < 1.0E-4) {
            return;
        }
        direction.normalize();
        double horizontal = knockbackHorizontal + (attacker.isSprinting() ? knockbackSprintBonus : 0);
        Vector velocity = direction.multiply(horizontal).setY(knockbackVertical);

        // Vanilla applies its own knockback within the same tick this event
        // fires in, so setting velocity here gets silently overwritten a
        // moment later; scheduling it one tick ahead is the reliable way to
        // make a custom knockback value actually stick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (victim.isValid()) {
                victim.setVelocity(velocity);
            }
        });
    }

    /**
     * Cancels vanilla's food-saturation-boosted "fast heal" so the flat,
     * slow regen task below is the only way players heal passively.
     */
    @EventHandler(ignoreCancelled = true)
    public void onNaturalRegen(EntityRegainHealthEvent event) {
        if (legacyHealthRegen
                && event.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED
                && event.getEntity() instanceof Player player
                && isEnabledIn(player.getWorld())) {
            event.setCancelled(true);
        }
    }

    private void restartHealthRegenTask() {
        if (healthRegenTask != null) {
            healthRegenTask.cancel();
            healthRegenTask = null;
        }
        if (!legacyHealthRegen) {
            return;
        }
        healthRegenTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!isEnabledIn(player.getWorld()) || player.getHealth() <= 0 || player.getFoodLevel() < 18) {
                    continue;
                }
                AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
                double maxHealth = maxHealthAttribute != null ? maxHealthAttribute.getValue() : 20.0;
                if (player.getHealth() < maxHealth) {
                    player.setHealth(Math.min(maxHealth, player.getHealth() + HEALTH_REGEN_AMOUNT));
                }
            }
        }, HEALTH_REGEN_INTERVAL_TICKS, HEALTH_REGEN_INTERVAL_TICKS);
    }

    /**
     * Replaces vanilla's own golden-apple effects entirely with the
     * configured lists -- cancelling consumption here means both the
     * default effects AND the default item-count decrement are skipped, so
     * both have to be done by hand.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!legacyGoldenApples) {
            return;
        }
        Material material = event.getItem().getType();
        List<PotionEffect> effects;
        if (material == Material.GOLDEN_APPLE) {
            effects = goldenAppleEffects;
        } else if (material == Material.ENCHANTED_GOLDEN_APPLE) {
            effects = enchantedGoldenAppleEffects;
        } else {
            return;
        }
        Player player = event.getPlayer();
        if (effects.isEmpty() || !isEnabledIn(player.getWorld())) {
            return;
        }
        event.setCancelled(true);
        consumeOne(player, event.getHand());
        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }
    }

    private static void consumeOne(Player player, EquipmentSlot hand) {
        ItemStack item = player.getInventory().getItem(hand);
        if (item == null || item.getType().isAir()) {
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItem(hand, null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private void apply(Player player) {
        boolean active = isEnabledIn(player.getWorld());

        AttributeInstance attackSpeedAttribute = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attackSpeedAttribute != null) {
            attackSpeedAttribute.setBaseValue(active ? attackSpeed : VANILLA_ATTACK_SPEED);
        }

        AttributeInstance toughness = player.getAttribute(Attribute.ARMOR_TOUGHNESS);
        if (toughness != null) {
            if (active && legacyArmorCalculations) {
                // Cancels out whatever the equipped armor's own attribute
                // modifiers are contributing, without touching those
                // modifiers directly -- forces the *effective* toughness to
                // exactly 0 no matter how much gear is stacking onto it.
                // Idempotent: recomputing this with no armor change is a
                // no-op, since it always derives from the current total.
                toughness.setBaseValue(toughness.getBaseValue() - toughness.getValue());
            } else {
                toughness.setBaseValue(VANILLA_ARMOR_TOUGHNESS_BASE);
            }
        }
    }

    private boolean isEnabledIn(World world) {
        return world != null && enabled
                && (worlds.isEmpty() || worlds.contains(world.getName().toLowerCase(Locale.ROOT)));
    }
}
