package me.vertex.core.enchant.listener;

import me.vertex.core.booster.BoosterCategory;
import me.vertex.core.booster.BoosterContribution;
import me.vertex.core.booster.BoosterSource;
import me.vertex.core.enchant.EnchantDefinition;
import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RuneCooldownStore;
import me.vertex.core.enchant.RuneFormatting;
import me.vertex.core.enchant.RuneProtection;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.user.User;
import me.vertex.core.user.UserManager;
import me.vertex.core.zone.ZoneManager;
import me.vertex.core.zone.ZoneRegion;
import me.vertex.core.zone.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Config-driven live effects for every Rune tier, Arena included. The
 * effect type, trigger values, priorities, proc rates, and per-level
 * settings all live in {@code customEnchants/runes.yml}; this listener
 * deliberately does not contain a second pool of hard-coded rune
 * definitions.
 *
 * <p><b>The central pipeline</b>: every effect funnels through the same
 * fixed sequence, via {@link #activate}, regardless of which Bukkit event
 * triggered it: (1) context/protection -- satisfied structurally by {@code
 * @EventHandler(priority = HIGH, ignoreCancelled = true)} for anything
 * riding on an existing Bukkit event (an earlier claim/region listener
 * cancels it first), or explicitly via {@link RuneProtection} for a new
 * action an effect creates itself (e.g. {@link #detonate}'s per-target AOE
 * damage); (2) active-rune snapshot -- {@link #active}, read once per
 * trigger; (3) trigger validation -- the effect-id filter plus world/zone
 * gating, both inside {@link #active}; (4) activation chance -- {@link
 * #procs}; (5) cooldown validation -- {@link #onCooldown}, backed by the
 * persisted {@link RuneCooldownStore} so a cooldown survives logout; (6)
 * execute the effect -- the caller's {@code action}; (7) reward/drop
 * modifiers -- e.g. {@link #duplicateDrops} or the Mob Drop Boost hook in
 * {@link #boosterSource}; (8) messages/sounds/particles -- a hook point,
 * unused so far; (9) register cooldown -- {@link #startCooldown}, only
 * once {@code action} reports it actually fired.
 */
public final class RuneEffectListener implements Listener {
    private static final List<String> MOVEMENT_EFFECTS = List.of("SKY_STEPPER", "DASHER", "RIFTWALKER");
    /**
     * Effect id -> the fixed potion effect it grants for as long as at
     * least one currently-eligible equipped item carries it (Bat Vision
     * now; Aquatic/Speed/Jump/Haste/Obsidian Shield reuse this exact same
     * map for the Expanded Rune Module's other "permanent while equipped"
     * runes). Level only ever changes the amplifier; there is no proc, no
     * cooldown, and {@link #recalculatePassiveAuras} is event-driven
     * (equip change, join, respawn) rather than a per-tick scan, per spec.
     */
    private static final Map<String, PotionEffectType> PASSIVE_AURA_EFFECTS = Map.of(
            "BAT_VISION", PotionEffectType.NIGHT_VISION);

    private final EnchantManager manager;
    private final CombatManager combat;
    private final UserManager users;
    private final RuneCooldownStore cooldowns;
    private volatile ZoneManager zones;
    private volatile me.vertex.core.lang.Messages messages;
    private volatile me.vertex.core.preferences.AnnouncementPreferenceManager announcementPreferences;
    private volatile me.vertex.core.backpack.BackpackAutoStoreListener backpacks;
    private volatile me.vertex.core.enchant.binds.BindManager binds;
    /** Ghost's active-invisibility state, one per player -- checked to cancel it the instant its holder deals an attack, and cleaned up on quit/death. */
    private final Set<UUID> ghostActive = ConcurrentHashMap.newKeySet();

    /** Talon Rend / Crown Breaker's "next qualifying melee hit" empowerment, one per player. */
    private final Map<UUID, ArmedHit> armedHits = new ConcurrentHashMap<>();
    /** Talon Rend's active incoming-healing-reduction debuff, one per wounded entity (player or mob). */
    private final Map<UUID, Wound> wounds = new ConcurrentHashMap<>();
    /** Huntmaster's Call's active mark, one per marked target. */
    private final Map<UUID, Mark> marks = new ConcurrentHashMap<>();
    /** Slipstream's active trail, one per casting owner. */
    private final Map<UUID, Trail> trails = new ConcurrentHashMap<>();
    /** Rally Arrow's "next shot" empowerment, one per shooter, consumed on {@link EntityShootBowEvent}. */
    private final Map<UUID, ArmedHit> armedShots = new ConcurrentHashMap<>();
    /** Rally Arrow's active healing circles, keyed by the tagged projectile's entity id so impact resolves in O(1). */
    private final List<HealCircle> healCircles = new ArrayList<>();
    private final NamespacedKey shotEffectKey;
    /** Block metadata (not PDC -- plain ore blocks aren't PersistentDataHolders) marking a block as player-placed, for Golden Vein's anti-exploit guard. */
    private static final String PLACED_BLOCK_METADATA = "vertex_player_placed";
    private volatile BukkitTask seasonalTask;

    /** Tags on a Gilded Catch booster potion identifying which category it grants, its rolled percent, and its rolled duration -- read back on {@link #onConsume}. */
    private final NamespacedKey boosterCategoryKey;
    private final NamespacedKey boosterPercentKey;
    private final NamespacedKey boosterDurationKey;
    /** Gilded Catch's active personal EXP/Sell booster grants, one slot per category per player -- lazily expired on read, never swept by a task. */
    private final Map<UUID, Map<BoosterCategory, PersonalBoost>> personalBoosts = new ConcurrentHashMap<>();

    public record PersonalBoost(double percent, long expiresAt) {
    }

    private record ArmedHit(EnchantDefinition definition, EnchantDefinition.Level level, long expiresAt) {
    }

    private record Wound(double healingReductionPercent, long expiresAt) {
    }

    private record Mark(UUID casterUuid, double damageBonusPercent, double supportRangeBlocks, long expiresAt) {
    }

    private record Trail(UUID ownerUuid, int wearerLevel, int friendlyLevel, int enemyLevel,
            double friendlyContactSeconds, double enemyContactSeconds, double contactRadiusBlocks, long expiresAt) {
    }

    private record HealCircle(UUID ownerUuid, Location center, double radiusBlocks, double maxHealthPerPlayer,
            long expiresAt, long pulseCount, Map<UUID, Double> healedSoFar) {
    }

    public RuneEffectListener(EnchantManager manager, CombatManager combat, UserManager users, RuneCooldownStore cooldowns) {
        this.manager = manager;
        this.combat = combat;
        this.users = users;
        this.cooldowns = cooldowns;
        this.shotEffectKey = new NamespacedKey(manager.plugin(), "seasonal_shot_effect");
        this.boosterCategoryKey = new NamespacedKey(manager.plugin(), "gilded_catch_booster_category");
        this.boosterPercentKey = new NamespacedKey(manager.plugin(), "gilded_catch_booster_percent");
        this.boosterDurationKey = new NamespacedKey(manager.plugin(), "gilded_catch_booster_duration_seconds");
    }

    /** Wired in once Haven/Riftlands are initialized -- zone-gated runes are simply inert until then. */
    public void setZoneManager(ZoneManager zones) {
        this.zones = zones;
    }

    /**
     * Wired in alongside {@link me.vertex.core.enchant.RunePrefCommand}'s
     * announcement toggles -- optional, like {@link #setZoneManager}, so a
     * caller that never sets these simply gets no crouch-cooldown feedback
     * message instead of a null-dereference.
     */
    public void setMessages(me.vertex.core.lang.Messages messages) {
        this.messages = messages;
    }

    public void setAnnouncementPreferences(me.vertex.core.preferences.AnnouncementPreferenceManager announcementPreferences) {
        this.announcementPreferences = announcementPreferences;
    }

    /**
     * Wired in alongside the others above -- optional, so a caller that
     * never sets this simply gets every bonus-drop rune (Ore Fortune,
     * Golden Vein, Crop Bounty, Golden Harvest) dropping on the ground the
     * way they always did, instead of a null-dereference. Once set, {@link
     * #duplicateDrops} routes its bonus items through the player's equipped
     * Backpack exactly like their real drop already is (via {@code
     * BackpackAutoStoreListener#onMine}), instead of unconditionally
     * dropping them at the block regardless of what's equipped.
     */
    public void setBackpackRouter(me.vertex.core.backpack.BackpackAutoStoreListener backpacks) {
        this.backpacks = backpacks;
    }

    /**
     * Wired in alongside the others above -- optional, so a caller that
     * never sets this simply gets Ghost/Rocket Escape defaulting to their
     * automatic low-health trigger (as if never bound), since {@link
     * #isBound} degrades to {@code false} without it. Once set, assigning
     * either rune to any {@code /binds} slot switches it to bind-only
     * activation, per the Expanded Rune Module spec.
     */
    public void setBindManager(me.vertex.core.enchant.binds.BindManager binds) {
        this.binds = binds;
    }

    /** Whether {@code player} has assigned {@code enchantId} to any of their {@code /binds} slots right now. */
    private boolean isBound(Player player, String enchantId) {
        me.vertex.core.enchant.binds.BindManager currentBinds = binds;
        if (currentBinds == null) {
            return false;
        }
        me.vertex.core.enchant.binds.PlayerBinds playerBinds = currentBinds.get(player.getUniqueId());
        return playerBinds != null && playerBinds.binds().values().stream().anyMatch(bound -> bound.contains(enchantId));
    }

    /**
     * Whether {@code uuid} currently wants rune activation/proc chat
     * messages at all -- every self/target proc message in this class
     * (Crown Breaker, Raptor's Reversal, Gilded Catch) gates on this so
     * turning rune messages off in {@code /runeprefs} actually silences
     * them, not just the bind-queue's own activation/cooldown lines.
     * Defaults to enabled when no preference manager is wired, matching
     * {@link #announceMark}'s existing fallback.
     */
    private boolean runeActivationMessagesEnabled(UUID uuid) {
        me.vertex.core.preferences.AnnouncementPreferenceManager preferences = announcementPreferences;
        return preferences == null || preferences.isEnabled(uuid,
                me.vertex.core.preferences.AnnouncementCategory.RUNE_ACTIVATION_MESSAGES);
    }

    /** Backs a Mob Drop Boost rune (e.g. Abyssal Scavenger) the same way any other booster source contributes. */
    public BoosterSource boosterSource() {
        return new BoosterSource() {
            @Override
            public String id() {
                return "arena-runes";
            }

            @Override
            public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
                if (category != BoosterCategory.MOB_DROP) {
                    return List.of();
                }
                double value = manager.equippedAbilityValue(player, "MOB_DROP_BOOST", zoneAt(player));
                return value <= 0D ? List.of() : List.of(BoosterContribution.active(id(), category, value));
            }
        };
    }

    /** Backs the personal EXP/Sell boosters a Gilded Catch potion grants when drunk -- see {@link #onConsume}. */
    public BoosterSource gildedCatchBoosterSource() {
        return new BoosterSource() {
            @Override
            public String id() {
                return "gilded-catch";
            }

            @Override
            public List<BoosterContribution> contribute(Player player, BoosterCategory category) {
                if (category != BoosterCategory.EXP && category != BoosterCategory.SELL) {
                    return List.of();
                }
                PersonalBoost boost = activePersonalBoost(player.getUniqueId(), category);
                return boost == null || boost.percent() <= 0D
                        ? List.of()
                        : List.of(BoosterContribution.activeTimed(id(), category, boost.percent(), boost.expiresAt()));
            }
        };
    }

    private double personalBoostPercent(UUID uuid, BoosterCategory category) {
        PersonalBoost boost = activePersonalBoost(uuid, category);
        return boost == null ? 0D : boost.percent();
    }

    public PersonalBoost activePersonalBoost(UUID uuid, BoosterCategory category) {
        Map<BoosterCategory, PersonalBoost> perPlayer = personalBoosts.get(uuid);
        if (perPlayer == null) {
            return null;
        }
        PersonalBoost boost = perPlayer.get(category);
        if (boost == null) {
            return null;
        }
        if (System.currentTimeMillis() > boost.expiresAt()) {
            perPlayer.remove(category);
            return null;
        }
        return boost;
    }

    public void applyPersonalBoost(UUID uuid, BoosterCategory category, double percent, double durationSeconds) {
        long expiresAt = System.currentTimeMillis() + Math.round(durationSeconds * 1000D);
        personalBoosts.computeIfAbsent(uuid, ignored -> new HashMap<>())
                .put(category, new PersonalBoost(percent, expiresAt));
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
        List<ActiveRune> movementRunes = active(player, MOVEMENT_EFFECTS);
        for (ActiveRune rune : movementRunes) {
            if (activate(player, rune, () -> activateMovement(player, rune))) {
                return;
            }
        }
        notifyMovementRuneCooldown(player, movementRunes);
    }

    /**
     * Every equipped movement rune failed to activate above -- if that's
     * because at least one of them is specifically on cooldown (not
     * blocked-in-combat or a proc miss), tell the player exactly how much
     * longer, matching the same cooldown-message convention {@code
     * /binds} already uses for its own queue.
     */
    private void notifyMovementRuneCooldown(Player player, List<ActiveRune> movementRunes) {
        if (movementRunes.isEmpty() || messages == null || users == null) {
            return;
        }
        User user = users.get(player.getUniqueId());
        if (user == null) {
            return;
        }
        for (ActiveRune rune : movementRunes) {
            long remaining = cooldowns.remainingMillis(user, rune.definition().id());
            if (remaining <= 0L) {
                continue;
            }
            if (announcementPreferences != null && !announcementPreferences.isEnabled(player.getUniqueId(),
                    me.vertex.core.preferences.AnnouncementCategory.RUNE_COOLDOWN_MESSAGES)) {
                return;
            }
            player.sendMessage(messages.get(player, "rune.movement-on-cooldown",
                    "enchant", RuneFormatting.coloredNameRaw(manager.tierOf(rune.definition().id()), rune.definition().displayName()),
                    "seconds", String.format(java.util.Locale.ROOT, "%.1f", remaining / 1000D)));
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();
        if (isOre(type)) {
            for (ActiveRune rune : active(player, List.of("ORE_FORTUNE"))) {
                activate(player, rune, () -> duplicateDrops(event, rune.level().abilityValue()));
            }
            if (!isPlacedBlock(block) && !hasSilkTouch(player)) {
                for (ActiveRune rune : active(player, List.of("GOLDEN_VEIN"))) {
                    activate(player, rune, () -> duplicateDrops(event, rune.level().abilityValue()));
                }
            }
        }
        if (isCrop(type)) {
            for (ActiveRune rune : active(player, List.of("CROP_BOUNTY"))) {
                activate(player, rune, () -> duplicateDrops(event, rune.level().abilityValue()));
            }
            boolean mature = isMatureCrop(block);
            if (mature) {
                for (ActiveRune rune : active(player, List.of("GOLDEN_HARVEST"))) {
                    activate(player, rune, () -> duplicateDrops(event, rune.level().abilityValue()));
                }
            }
            // Auto-replant runs once per break regardless of that break's proc roll
            // outcome above -- Golden Harvest's replant is unconditional at every
            // level, per spec, not itself a chance-driven mechanic.
            if (mature && !active(player, List.of("GOLDEN_HARVEST")).isEmpty()) {
                replantIfAffordable(player, block, type);
            }
        }
    }

    private static boolean hasSilkTouch(Player player) {
        return player.getInventory().getItemInMainHand().containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH);
    }

    private static boolean isMatureCrop(Block block) {
        return block.getBlockData() instanceof org.bukkit.block.data.Ageable ageable
                && ageable.getAge() >= ageable.getMaximumAge();
    }

    /** Golden Harvest: replants at initial growth, consuming exactly one planting resource, only when the player actually has one. */
    private void replantIfAffordable(Player player, Block block, Material cropType) {
        Material seed = switch (cropType) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            default -> null;
        };
        if (seed == null || !player.getInventory().containsAtLeast(new ItemStack(seed), 1)) {
            return;
        }
        player.getInventory().removeItem(new ItemStack(seed, 1));
        Bukkit.getScheduler().runTask(manager.plugin(), () -> {
            if (block.getType().isAir()) {
                block.setType(cropType);
                if (block.getBlockData() instanceof org.bukkit.block.data.Ageable ageable) {
                    ageable.setAge(0);
                    block.setBlockData(ageable);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Entity damager = event instanceof EntityDamageByEntityEvent byEntity ? byEntity.getDamager() : null;
        boolean fromPlayer = attacker(damager) != null;
        boolean fromMob = isMobDamage(damager);

        if (event.getDamage() > 0D && damager != null && !(damager instanceof Projectile)) {
            applyRaptorsReversal(victim, damager, event);
        }

        Player enemyAttacker = attacker(damager);
        if (enemyAttacker != null && event.getDamage() > 0D && !RuneProtection.isTeamOf(enemyAttacker, victim)) {
            // Ghost and Rocket Escape default to this automatic low-health
            // trigger; assigning either to a /binds slot disables it
            // entirely in favor of manual activation (see manuallyActivate
            // and BindQueue, which re-checks eligibility at the queue's
            // own execution time, not here).
            for (ActiveRune rune : active(victim, List.of("GHOST"))) {
                if (!isBound(victim, rune.definition().id())) {
                    activate(victim, rune, () -> activateGhost(victim, rune));
                }
            }
            for (ActiveRune rune : active(victim, List.of("ROCKET_ESCAPE"))) {
                if (!isBound(victim, rune.definition().id())) {
                    activate(victim, rune, () -> activateRocketEscape(victim, rune));
                }
            }
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            for (ActiveRune rune : active(victim, List.of("FALL_REDUCTION"))) {
                activate(victim, rune, () -> {
                    event.setDamage(event.getDamage() * reductionMultiplier(rune.level().abilityValue()));
                    return true;
                });
            }
        }
        for (ActiveRune rune : active(victim, List.of("DAMAGE_REDUCTION"))) {
            if (!matchesDamageScope(rune.definition(), fromMob, fromPlayer)) {
                continue;
            }
            activate(victim, rune, () -> {
                event.setDamage(event.getDamage() * reductionMultiplier(rune.level().abilityValue()));
                return true;
            });
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Projectile) {
            for (ActiveRune rune : active(victim, List.of("PROJECTILE_REDUCTION"))) {
                activate(victim, rune, () -> {
                    event.setDamage(event.getDamage() * reductionMultiplier(rune.level().abilityValue()));
                    return true;
                });
            }
        }
        for (ActiveRune rune : active(victim, List.of("DEATH_GUARD"))) {
            boolean fired = activate(victim, rune, () -> {
                if (event.getFinalDamage() < victim.getHealth()) {
                    return false;
                }
                event.setCancelled(true);
                victim.setHealth(Math.min(victim.getMaxHealth(), Math.max(1D,
                        rune.level().setting("restore-health", 4D))));
                victim.setFireTicks(0);
                victim.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION,
                        (int) Math.max(1D, rune.level().setting("regeneration-seconds", 5D)) * 20, 1));
                return true;
            });
            if (fired) {
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        boolean projectile = event.getDamager() instanceof Projectile;
        boolean targetIsPlayer = event.getEntity() instanceof Player;
        if (event.getDamage() > 0D) {
            cancelGhostOnAttack(attacker);
        }
        if (!projectile && event.getDamage() > 0D && event.getEntity() instanceof LivingEntity target) {
            consumeArmedHit(attacker, target);
        }
        if (projectile && targetIsPlayer && event.getDamage() > 0D && event.getEntity() instanceof Player zeusTarget
                && !RuneProtection.isTeamOf(attacker, zeusTarget)) {
            for (ActiveRune rune : active(attacker, List.of("ZEUS"))) {
                activate(attacker, rune, () -> {
                    // Visual-only strike (no fire, no block damage, no real
                    // lightning entity) folded into this same damage event
                    // as extra damage -- never a second damage() call, so it
                    // can never recursively trigger another offensive rune.
                    // Wrapped like detonate()'s particles: the bonus damage
                    // must land even in a headless/mock environment without
                    // lightning-effect support.
                    try {
                        zeusTarget.getWorld().strikeLightningEffect(zeusTarget.getLocation());
                    } catch (Throwable ignored) {
                        // Headless / mock environments without lightning-effect support
                    }
                    event.setDamage(event.getDamage() + Math.max(0D, rune.level().abilityValue()) * 2D);
                    return true;
                });
            }
        }
        if (event.getDamage() > 0D && event.getEntity() instanceof Player markedTarget) {
            applyHuntmastersCallBonus(attacker, markedTarget, event);
        }
        for (ActiveRune rune : active(attacker, projectile ? List.of("PROJECTILE_DAMAGE") : List.of("MELEE_DAMAGE"))) {
            if (!matchesTargetFilter(rune.definition(), targetIsPlayer)) {
                continue;
            }
            activate(attacker, rune, () -> {
                event.setDamage(event.getDamage() * (1D + Math.max(0D, rune.level().abilityValue()) / 100D));
                return true;
            });
        }
        if (!projectile) {
            for (ActiveRune rune : active(attacker, List.of("EXECUTE"))) {
                activate(attacker, rune, () -> {
                    if (!(event.getEntity() instanceof LivingEntity target)) {
                        return false;
                    }
                    double threshold = rune.level().setting("health-threshold-percent", 25D);
                    if (target.getHealth() > target.getMaxHealth() * threshold / 100D) {
                        return false;
                    }
                    event.setDamage(event.getDamage() * (1D + Math.max(0D, rune.level().abilityValue()) / 100D));
                    return true;
                });
            }
            for (ActiveRune rune : active(attacker, List.of("LIFESTEAL"))) {
                activate(attacker, rune, () -> {
                    attacker.setHealth(Math.min(attacker.getMaxHealth(), attacker.getHealth()
                            + event.getFinalDamage() * Math.max(0D, rune.level().abilityValue()) / 100D));
                    return true;
                });
            }
            for (ActiveRune rune : active(attacker, List.of("FIRE_STRIKE"))) {
                activate(attacker, rune, () -> {
                    if (!(event.getEntity() instanceof LivingEntity target)) {
                        return false;
                    }
                    target.setFireTicks(Math.max(target.getFireTicks(),
                            (int) Math.max(1D, rune.level().setting("fire-seconds", 2D)) * 20));
                    return true;
                });
            }
            for (ActiveRune rune : active(attacker, List.of("OBLITERATE"))) {
                activate(attacker, rune, () -> {
                    if (!(event.getEntity() instanceof Player targetPlayer) || RuneProtection.isTeamOf(attacker, targetPlayer)) {
                        return false;
                    }
                    Vector push = targetPlayer.getLocation().toVector().subtract(attacker.getLocation().toVector());
                    if (push.lengthSquared() < 0.0001D) {
                        push = new Vector(1D, 0D, 0D);
                    }
                    push.setY(0D).normalize().multiply(rune.level().setting("horizontal-velocity", 0.2D))
                            .setY(rune.level().setting("vertical-velocity", 0.05D));
                    // Away from the attacker, no fall-damage immunity -- a
                    // plain velocity shove, nothing else.
                    targetPlayer.setVelocity(targetPlayer.getVelocity().add(push));
                    return true;
                });
            }
            for (ActiveRune rune : active(attacker, List.of("FEATHERWEIGHT"))) {
                if (!matchesTargetFilter(rune.definition(), targetIsPlayer)) {
                    continue;
                }
                activate(attacker, rune, () -> {
                    // Reactivation refreshes the effect at this same rune's
                    // own fixed amplifier -- addPotionEffect always
                    // overwrites, so no separate "don't downgrade" merge is
                    // needed the way FlightEffects-style helpers use
                    // elsewhere for effects that can come from other sources.
                    int amplifier = Math.max(0, rune.level().level() - 1);
                    int durationTicks = (int) Math.round(rune.level().setting("haste-duration-seconds", 3D) * 20D);
                    attacker.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, durationTicks, amplifier, true, false));
                    return true;
                });
            }
        }
        for (ActiveRune rune : active(attacker, List.of("LIGHTNING_STRIKE"))) {
            activate(attacker, rune, () -> {
                if (!(event.getEntity() instanceof LivingEntity target)) {
                    return false;
                }
                try {
                    target.getWorld().strikeLightningEffect(target.getLocation());
                } catch (Throwable ignored) {
                    // Headless / mock environments without lightning-effect support
                }
                // Add the configured bonus to this one damage event rather
                // than calling target.damage again, which would recursively
                // re-enter this listener and could proc Lightning Strike a
                // second time.
                event.setDamage(event.getDamage() + Math.max(0D, rune.level().abilityValue()));
                return true;
            });
        }
    }

    /**
     * Consumes one player's armed Talon Rend/Crown Breaker empowerment on
     * their next qualifying melee hit (already filtered to non-projectile,
     * positive-damage hits by the caller, matching Raptor's Reversal's
     * "cancelled, protected, zero-damage, and projectile hits do not
     * trigger it" rule). Revalidates the source is still equipped at hit
     * time -- an expired or no-longer-equipped empowerment is silently
     * dropped rather than applied.
     */
    private void consumeArmedHit(Player attacker, LivingEntity target) {
        ArmedHit armed = armedHits.get(attacker.getUniqueId());
        if (armed == null) {
            return;
        }
        armedHits.remove(attacker.getUniqueId());
        if (System.currentTimeMillis() > armed.expiresAt()) {
            return;
        }
        boolean stillEquipped = false;
        for (ItemStack item : equipped(attacker)) {
            if (manager.enchantsOf(item).containsKey(armed.definition().id())) {
                stillEquipped = true;
                break;
            }
        }
        if (!stillEquipped) {
            return;
        }
        switch (armed.definition().effect()) {
            case "TALON_REND" -> {
                double reduction = Math.max(0D, Math.min(100D, armed.level().abilityValue()));
                double durationSeconds = armed.level().setting("wound-duration-seconds", 4D);
                wounds.put(target.getUniqueId(), new Wound(reduction,
                        System.currentTimeMillis() + Math.round(Math.max(0D, durationSeconds) * 1000D)));
            }
            case "CROWN_BREAKER" -> {
                double heartsToRemove = Math.max(0D, armed.level().abilityValue());
                double currentAbsorption = target.getAbsorptionAmount();
                double removed = Math.min(currentAbsorption, heartsToRemove * 2D);
                target.setAbsorptionAmount(Math.max(0D, currentAbsorption - removed));
                me.vertex.core.lang.Messages currentMessages = messages;
                if (removed > 0D && target instanceof Player victim && currentMessages != null
                        && runeActivationMessagesEnabled(victim.getUniqueId())) {
                    String coloredName = RuneFormatting.coloredNameRaw(
                            me.vertex.core.enchant.RuneTier.SEASONAL, armed.definition().displayName());
                    victim.sendMessage(currentMessages.get(victim, "rune.crown-breaker-proc",
                            "enchant", coloredName, "attacker", attacker.getName()));
                }
            }
            default -> {
            }
        }
    }

    /**
     * Raptor's Reversal: fully passive now -- no activation, no bind. Every
     * equipped copy gets its own {@link #activate} roll (proc-chance +
     * cooldown, exactly like any other passive effect in this file) on each
     * incoming melee hit; a hit is never reduced by more than one copy since
     * {@link #active} only returns the highest level per enchant id.
     */
    private void applyRaptorsReversal(Player victim, Entity attackerEntity, EntityDamageEvent event) {
        for (ActiveRune rune : active(victim, List.of("RAPTORS_REVERSAL"))) {
            activate(victim, rune, () -> {
                double minPercent = rune.level().setting("min-reduction-percent", 15D);
                double maxPercent = Math.max(minPercent, rune.level().setting("max-reduction-percent", 45D));
                double reduction = minPercent + ThreadLocalRandom.current().nextDouble() * (maxPercent - minPercent);
                event.setDamage(event.getDamage() * reductionMultiplier(reduction));
                if (attackerEntity instanceof LivingEntity livingAttacker) {
                    Vector push = livingAttacker.getLocation().toVector().subtract(victim.getLocation().toVector());
                    if (push.lengthSquared() < 0.0001D) {
                        push = new Vector(1D, 0D, 0D);
                    }
                    push.setY(0D).normalize().multiply(rune.level().setting("knockback-strength", 1.0D)).setY(0.35D);
                    livingAttacker.setVelocity(livingAttacker.getVelocity().add(push));
                }
                me.vertex.core.lang.Messages currentMessages = messages;
                if (attackerEntity instanceof Player attackerPlayer && currentMessages != null) {
                    String coloredName = RuneFormatting.coloredNameRaw(
                            me.vertex.core.enchant.RuneTier.SEASONAL, rune.definition().displayName());
                    if (runeActivationMessagesEnabled(victim.getUniqueId())) {
                        victim.sendMessage(currentMessages.get(victim, "rune.raptors-reversal-proc-self",
                                "enchant", coloredName, "player", attackerPlayer.getName()));
                    }
                    if (runeActivationMessagesEnabled(attackerPlayer.getUniqueId())) {
                        attackerPlayer.sendMessage(currentMessages.get(attackerPlayer, "rune.raptors-reversal-proc-target",
                                "enchant", coloredName, "player", victim.getName()));
                    }
                }
                return true;
            });
        }
    }

    /** How often (in ticks) an active Golden Bastion field re-checks for intruders and redraws its boundary while it's up. */
    private static final long BASTION_FIELD_PERIOD_TICKS = 4L;

    /**
     * Golden Bastion: a standing field around the caster, not a single
     * instant pulse -- for {@code duration-seconds} (4 at level 1, up to 8 at
     * level 5) it keeps re-checking every {@link #BASTION_FIELD_PERIOD_TICKS}
     * and knocking away anyone caught in the box who isn't the caster and
     * isn't {@link RuneProtection#isTeamOf} them (own faction or an ally),
     * following the caster if they move so the field always stays centered
     * on them. The box is the same size in every direction ("5x5" at level
     * 1, wider at higher levels, including straight down), so nobody
     * standing above or below the caster is safe either. A ring of particles
     * traces the current radius every tick of the field so both the caster
     * and anyone nearby can see exactly where the "can't walk in" boundary
     * is -- wrapped in try/catch since headless/mock environments don't
     * support particle rendering (see {@link #detonate} for the same guard).
     * Never procs from inside a protected spawn/safezone (the same check
     * {@link me.vertex.core.ability.NoPearlSpawnListener#isProtected} and
     * {@link me.vertex.core.pvp.CombatSafezoneListener} already gate on) --
     * shoving people around inside a zone meant to be a fight-free area is
     * exactly the kind of abuse those two exist to prevent elsewhere.
     */
    private boolean pushBastion(Player player, ActiveRune rune) {
        if (me.vertex.core.ability.NoPearlSpawnListener.isProtected(manager.plugin(), player.getLocation())) {
            return false;
        }
        double radius = Math.max(1D, rune.level().setting("radius-blocks", 5D));
        double strength = Math.max(0D, rune.level().abilityValue());
        double durationSeconds = Math.max(0D, rune.level().setting("duration-seconds", 4D));
        long durationTicks = Math.round(durationSeconds * 20D);
        if (durationTicks <= 0L || !manager.plugin().isEnabled()) {
            return true;
        }
        new org.bukkit.scheduler.BukkitRunnable() {
            long elapsedTicks = 0L;

            @Override
            public void run() {
                if (elapsedTicks >= durationTicks || !player.isOnline() || player.isDead()) {
                    this.cancel();
                    return;
                }
                org.bukkit.Location center = player.getLocation();
                // The caster can walk into a safezone mid-duration since the
                // field follows them -- if they do, stop pushing (but keep
                // ticking down the duration) until they leave it again.
                boolean casterInSafezone = me.vertex.core.ability.NoPearlSpawnListener.isProtected(manager.plugin(), center);
                for (Entity entity : casterInSafezone ? List.<Entity>of() : player.getNearbyEntities(radius, radius, radius)) {
                    if (!(entity instanceof Player target) || RuneProtection.isTeamOf(player, target)) {
                        continue;
                    }
                    Vector push = target.getLocation().toVector().subtract(center.toVector());
                    if (push.lengthSquared() < 0.0001D) {
                        push = new Vector(1D, 0D, 0D);
                    }
                    push.setY(0D).normalize().multiply(strength).setY(0.4D);
                    target.setVelocity(target.getVelocity().add(push));
                }
                try {
                    int particleCount = Math.max(12, (int) (radius * 10D));
                    for (int i = 0; i < particleCount; i++) {
                        double angle = 2D * Math.PI * i / particleCount;
                        double x = Math.cos(angle) * radius;
                        double z = Math.sin(angle) * radius;
                        center.getWorld().spawnParticle(org.bukkit.Particle.FLAME,
                                center.clone().add(x, 0.2D, z), 1, 0D, 0D, 0D, 0D);
                    }
                } catch (Throwable ignored) {
                    // Headless / mock environments without particle support
                }
                elapsedTicks += BASTION_FIELD_PERIOD_TICKS;
            }
        }.runTaskTimer(manager.plugin(), 0L, BASTION_FIELD_PERIOD_TICKS);
        // A defensive panic-button field always consumes its cooldown once
        // cast, whether or not anyone was actually in range to push --
        // unlike a targeted ability (Huntmaster's Call), there's no single
        // "target" whose absence means the cast did nothing.
        return true;
    }

    /** Huntmaster's Call: the caster or any teammate within the caster's support range deals bonus damage to the marked target. */
    private void applyHuntmastersCallBonus(Player attacker, Player markedTarget, EntityDamageByEntityEvent event) {
        Mark mark = marks.get(markedTarget.getUniqueId());
        if (mark == null) {
            return;
        }
        if (System.currentTimeMillis() > mark.expiresAt()) {
            marks.remove(markedTarget.getUniqueId());
            return;
        }
        Player caster = Bukkit.getPlayer(mark.casterUuid());
        if (caster == null) {
            return;
        }
        boolean eligible = attacker.getUniqueId().equals(mark.casterUuid())
                || (RuneProtection.isTeamOf(caster, attacker) && caster.getWorld().equals(attacker.getWorld())
                        && caster.getLocation().distanceSquared(attacker.getLocation()) <= mark.supportRangeBlocks() * mark.supportRangeBlocks());
        if (!eligible) {
            return;
        }
        event.setDamage(event.getDamage() * (1D + mark.damageBonusPercent() / 100D));
    }

    /** Rally Arrow: consumes the caster's armed shot and tags exactly one fired projectile, so a multishot volley still produces only one effect. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player shooter) || !(event.getProjectile() instanceof Entity projectile)) {
            return;
        }
        ArmedHit armed = armedShots.remove(shooter.getUniqueId());
        if (armed == null || System.currentTimeMillis() > armed.expiresAt()) {
            return;
        }
        if (!(projectile instanceof org.bukkit.persistence.PersistentDataHolder holder)) {
            return;
        }
        holder.getPersistentDataContainer().set(shotEffectKey, PersistentDataType.STRING,
                armed.definition().id() + ":" + armed.level().level() + ":" + shooter.getUniqueId());
    }

    /** Resolves Rally Arrow's healing circle at the tagged projectile's impact point. */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.persistence.PersistentDataHolder holder)) {
            return;
        }
        String tag = holder.getPersistentDataContainer().get(shotEffectKey, PersistentDataType.STRING);
        if (tag == null) {
            return;
        }
        String[] parts = tag.split(":", 3);
        if (parts.length != 3) {
            return;
        }
        EnchantDefinition definition = manager.definition(parts[0]);
        int level;
        UUID casterUuid;
        try {
            level = Integer.parseInt(parts[1]);
            casterUuid = UUID.fromString(parts[2]);
        } catch (IllegalArgumentException e) {
            return;
        }
        EnchantDefinition.Level levelConfig = definition == null ? null : definition.level(level);
        Player caster = Bukkit.getPlayer(casterUuid);
        if (levelConfig == null || caster == null) {
            return;
        }
        Location impact = event.getHitEntity() != null ? event.getHitEntity().getLocation() : event.getEntity().getLocation();
        switch (definition.effect()) {
            case "RALLY_ARROW" -> createHealCircle(caster, levelConfig, impact);
            default -> {
            }
        }
    }

    private void createHealCircle(Player caster, EnchantDefinition.Level level, Location impact) {
        double radius = Math.max(0.5D, level.setting("radius-blocks", 4D));
        double duration = Math.max(0.2D, level.setting("duration-seconds", 3D));
        long pulses = Math.max(1L, Math.round(duration / SEASONAL_TICK_SECONDS));
        healCircles.add(new HealCircle(caster.getUniqueId(), impact.clone(), radius, Math.max(0D, level.abilityValue()) * 2D,
                System.currentTimeMillis() + Math.round(duration * 1000D), pulses, new HashMap<>()));
        ensureSeasonalTask();
    }

    private static final double SEASONAL_TICK_SECONDS = 0.25D;

    private void ensureSeasonalTask() {
        if (seasonalTask == null) {
            seasonalTask = Bukkit.getScheduler().runTaskTimer(manager.plugin(), this::seasonalTick,
                    Math.round(SEASONAL_TICK_SECONDS * 20D), Math.round(SEASONAL_TICK_SECONDS * 20D));
        }
    }

    /** Single shared timer for Slipstream's trail and Rally Arrow's healing pulses, matching the codebase's one-timer-for-all-active-state idiom. Stops itself once both are empty. */
    private void seasonalTick() {
        long now = System.currentTimeMillis();
        tickTrails(now);
        tickHealCircles(now);
        if (trails.isEmpty() && healCircles.isEmpty() && seasonalTask != null) {
            seasonalTask.cancel();
            seasonalTask = null;
        }
    }

    private void tickTrails(long now) {
        for (java.util.Iterator<Map.Entry<UUID, Trail>> it = trails.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Trail> entry = it.next();
            Trail trail = entry.getValue();
            Player owner = Bukkit.getPlayer(entry.getKey());
            if (owner == null || now > trail.expiresAt()) {
                it.remove();
                continue;
            }
            double r = trail.contactRadiusBlocks();
            for (Entity nearby : owner.getWorld().getNearbyEntities(owner.getLocation(), r, r, r)) {
                if (!(nearby instanceof Player nearbyPlayer) || nearbyPlayer.getUniqueId().equals(owner.getUniqueId())) {
                    continue;
                }
                if (RuneProtection.isTeamOf(owner, nearbyPlayer)) {
                    applyPotionIfStronger(nearbyPlayer, PotionEffectType.SPEED, trail.friendlyLevel() - 1,
                            (int) Math.round(trail.friendlyContactSeconds() * 20D));
                } else if (!FactionsHook.isInstalled() || FactionsHook.service().canPvp(owner, nearbyPlayer)) {
                    applyPotionIfStronger(nearbyPlayer, PotionEffectType.SLOWNESS, trail.enemyLevel() - 1,
                            (int) Math.round(trail.enemyContactSeconds() * 20D));
                }
            }
        }
    }

    private void tickHealCircles(long now) {
        Set<UUID> healedThisTick = new HashSet<>();
        for (java.util.Iterator<HealCircle> it = healCircles.iterator(); it.hasNext(); ) {
            HealCircle circle = it.next();
            if (now > circle.expiresAt()) {
                it.remove();
                continue;
            }
            Player owner = Bukkit.getPlayer(circle.ownerUuid());
            double r = circle.radiusBlocks();
            for (Entity nearby : circle.center().getWorld().getNearbyEntities(circle.center(), r, r, r)) {
                if (!(nearby instanceof Player player) || !healedThisTick.add(player.getUniqueId())) {
                    continue;
                }
                if (owner == null || !RuneProtection.isTeamOf(owner, player)) {
                    healedThisTick.remove(player.getUniqueId());
                    continue;
                }
                double alreadyHealed = circle.healedSoFar().getOrDefault(player.getUniqueId(), 0D);
                if (alreadyHealed >= circle.maxHealthPerPlayer()) {
                    continue;
                }
                double increment = Math.min(circle.maxHealthPerPlayer() / circle.pulseCount(),
                        circle.maxHealthPerPlayer() - alreadyHealed);
                if (increment <= 0D) {
                    continue;
                }
                EntityRegainHealthEvent regen = new EntityRegainHealthEvent(player, increment,
                        EntityRegainHealthEvent.RegainReason.CUSTOM);
                Bukkit.getPluginManager().callEvent(regen);
                if (!regen.isCancelled()) {
                    player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + regen.getAmount()));
                }
                circle.healedSoFar().put(player.getUniqueId(), alreadyHealed + increment);
            }
        }
    }

    /** Never downgrades an existing stronger effect of the same type -- shared by Slipstream's burst/trail and any future potion-granting seasonal rune. */
    private static void applyPotionIfStronger(LivingEntity target, PotionEffectType type, int amplifier, int durationTicks) {
        if (amplifier < 0 || durationTicks <= 0) {
            return;
        }
        PotionEffect existing = target.getPotionEffect(type);
        if (existing != null && existing.getAmplifier() > amplifier) {
            return;
        }
        target.addPotionEffect(new PotionEffect(type, durationTicks, amplifier));
    }

    /**
     * Gilded Catch: on a genuine successful catch, reuses the exact item the
     * player just caught (the safest possible "reuse existing loot" reward
     * -- no new economy item is invented) and rolls the level's chance to
     * grant one more of it. Every proc also announces itself in chat, and
     * separately rolls a rarer {@code booster-chance} for a personal,
     * drinkable XP or Sell booster potion -- a higher rod level makes this
     * roll both more likely to succeed at all AND, once it does, biases the
     * potion's rolled percent/duration toward the top of its configured
     * range (see {@link #createBoosterPotion} and {@link #onConsume}),
     * configurable end to end via {@code effect-settings}.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(org.bukkit.event.player.PlayerFishEvent event) {
        if (event.getState() != org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (!(event.getCaught() instanceof org.bukkit.entity.Item caughtItem)) {
            return;
        }
        Player player = event.getPlayer();
        for (ActiveRune rune : active(player, List.of("GILDED_CATCH"))) {
            activate(player, rune, () -> {
                ItemStack reward = caughtItem.getItemStack().clone();
                reward.setAmount((int) Math.max(1D, rune.level().setting("bonus-reward-amount", 1D)));
                java.util.Map<Integer, ItemStack> overflow = player.getInventory().addItem(reward);
                overflow.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                me.vertex.core.lang.Messages currentMessages = messages;
                String coloredName = RuneFormatting.coloredNameRaw(
                        me.vertex.core.enchant.RuneTier.SEASONAL, rune.definition().displayName());
                boolean messagesEnabled = runeActivationMessagesEnabled(player.getUniqueId());
                if (currentMessages != null && messagesEnabled) {
                    player.sendMessage(currentMessages.get(player, "rune.gilded-catch-proc", "enchant", coloredName));
                }
                double boosterChance = rune.level().setting("booster-chance", 0D);
                if (boosterChance > 0D && ThreadLocalRandom.current().nextDouble(100D) < boosterChance) {
                    ItemStack potion = createBoosterPotion(rune);
                    java.util.Map<Integer, ItemStack> potionOverflow = player.getInventory().addItem(potion);
                    potionOverflow.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
                    if (currentMessages != null && messagesEnabled) {
                        String boosterLabel = boosterCategory(potion) == BoosterCategory.EXP ? "XP" : "Sell";
                        player.sendMessage(currentMessages.get(player, "rune.gilded-catch-booster-proc",
                                "enchant", coloredName, "booster", boosterLabel));
                    }
                }
                return true;
            });
        }
    }

    /**
     * How far up each roll's floor gets pushed toward its ceiling at max
     * level -- 0.85 rather than 1.0 so even a level-10 rod keeps a sliver of
     * randomness (a narrow high-end band) instead of every roll collapsing
     * onto the exact same number.
     */
    private static final double BOOSTER_QUALITY_BIAS = 0.85D;

    /**
     * Rolls one of the two personal booster categories (EXP or Sell) with
     * equal odds, then a percent and a duration within that category's
     * fully configurable {@code min-*}/{@code max-*} range -- but a higher
     * rod level doesn't just proc a booster more often (see the {@code
     * booster-chance} curve in {@code runes.yml}), it also raises the
     * <em>floor</em> of that roll toward the ceiling (see {@link
     * #biasedFloor}), so a level 10 rod's boosters consistently land near
     * the top of the range instead of anywhere in it. Bakes the rolled
     * category/percent/duration onto a plain, no-vanilla-effect potion via
     * PDC tags so {@link #onConsume} can read them back without re-rolling
     * anything.
     */
    public ItemStack createBoosterPotion(BoosterCategory category, double percent, double durationSeconds) {
        ItemStack item = new ItemStack(Material.POTION);
        org.bukkit.inventory.meta.PotionMeta meta = (org.bukkit.inventory.meta.PotionMeta) item.getItemMeta();
        meta.setBasePotionType(org.bukkit.potion.PotionType.WATER);
        boolean exp = category == BoosterCategory.EXP;
        String label = exp ? "xp booster" : "sell booster";
        meta.displayName(RuneFormatting.plain(RuneFormatting.smallCaps(label) + " +" + RuneFormatting.percent(percent) + "%",
                exp ? net.kyori.adventure.text.format.NamedTextColor.AQUA : net.kyori.adventure.text.format.NamedTextColor.GOLD));
        meta.lore(List.of(RuneFormatting.plain(RuneFormatting.smallCaps("lasts " + formatDuration(durationSeconds)),
                net.kyori.adventure.text.format.NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(boosterCategoryKey, PersistentDataType.STRING, category.name());
        meta.getPersistentDataContainer().set(boosterPercentKey, PersistentDataType.DOUBLE, percent);
        meta.getPersistentDataContainer().set(boosterDurationKey, PersistentDataType.DOUBLE, durationSeconds);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBoosterPotion(ActiveRune rune) {
        EnchantDefinition.Level level = rune.level();
        double levelFraction = rune.definition().maxLevel() <= 1 ? 1D
                : (level.level() - 1D) / (rune.definition().maxLevel() - 1D);
        boolean exp = ThreadLocalRandom.current().nextBoolean();
        BoosterCategory category = exp ? BoosterCategory.EXP : BoosterCategory.SELL;
        double minPercent = level.setting(exp ? "min-exp-percent" : "min-sell-percent", exp ? 15D : 25D);
        double maxPercent = Math.max(minPercent, level.setting(exp ? "max-exp-percent" : "max-sell-percent", exp ? 200D : 124D));
        double minDuration = level.setting(exp ? "min-exp-duration-seconds" : "min-sell-duration-seconds", exp ? 300D : 15D);
        double maxDuration = Math.max(minDuration, level.setting(exp ? "max-exp-duration-seconds" : "max-sell-duration-seconds", exp ? 1800D : 60D));
        double percentFloor = biasedFloor(minPercent, maxPercent, levelFraction);
        double durationFloor = biasedFloor(minDuration, maxDuration, levelFraction);
        double percent = percentFloor + ThreadLocalRandom.current().nextDouble() * (maxPercent - percentFloor);
        double durationSeconds = durationFloor + ThreadLocalRandom.current().nextDouble() * (maxDuration - durationFloor);
        return createBoosterPotion(category, percent, durationSeconds);
    }

    /** @return a roll floor between {@code min} and {@code max}, pushed up by {@code levelFraction} (0 at level 1, 1 at max level) times {@link #BOOSTER_QUALITY_BIAS}. */
    private static double biasedFloor(double min, double max, double levelFraction) {
        return min + (max - min) * levelFraction * BOOSTER_QUALITY_BIAS;
    }

    private BoosterCategory boosterCategory(ItemStack potion) {
        if (!potion.hasItemMeta()) {
            return null;
        }
        String raw = potion.getItemMeta().getPersistentDataContainer().get(boosterCategoryKey, PersistentDataType.STRING);
        try {
            return raw == null ? null : BoosterCategory.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String formatDuration(double seconds) {
        long totalSeconds = Math.round(seconds);
        return totalSeconds >= 60L ? (totalSeconds / 60L) + "m " + (totalSeconds % 60L) + "s" : totalSeconds + "s";
    }

    /** Drinking a Gilded Catch booster potion grants its baked-in personal EXP or Sell boost immediately; a plain potion with none of these tags is left untouched. */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        BoosterCategory category = boosterCategory(item);
        if (category == null) {
            return;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        Double percent = pdc.get(boosterPercentKey, PersistentDataType.DOUBLE);
        Double durationSeconds = pdc.get(boosterDurationKey, PersistentDataType.DOUBLE);
        if (percent == null || durationSeconds == null || percent <= 0D || durationSeconds <= 0D) {
            return;
        }
        Player player = event.getPlayer();
        long expiresAt = System.currentTimeMillis() + Math.round(durationSeconds * 1000D);
        personalBoosts.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .put(category, new PersonalBoost(percent, expiresAt));
        me.vertex.core.lang.Messages currentMessages = messages;
        if (currentMessages != null && runeActivationMessagesEnabled(player.getUniqueId())) {
            EnchantDefinition gildedCatch = manager.definition("gilded_catch");
            String coloredName = RuneFormatting.coloredNameRaw(me.vertex.core.enchant.RuneTier.SEASONAL,
                    gildedCatch == null ? "Gilded Catch" : gildedCatch.displayName());
            player.sendMessage(currentMessages.get(player, "rune.gilded-catch-booster-consumed",
                    "enchant", coloredName,
                    "booster", category == BoosterCategory.EXP ? "XP" : "Sell",
                    "percent", RuneFormatting.percent(percent),
                    "duration", formatDuration(durationSeconds)));
        }
    }

    /** Golden Vein's anti-exploit guard: a block a player placed this session never counts as a natural/arena-regenerated ore. */
    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        event.getBlock().setMetadata(PLACED_BLOCK_METADATA, new org.bukkit.metadata.FixedMetadataValue(manager.plugin(), true));
    }

    private static boolean isPlacedBlock(Block block) {
        return block.hasMetadata(PLACED_BLOCK_METADATA);
    }

    /** Talon Rend's incoming-healing reduction -- reused by any healing source, including a future Rally Arrow. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        UUID id = event.getEntity().getUniqueId();
        Wound wound = wounds.get(id);
        if (wound == null) {
            return;
        }
        if (System.currentTimeMillis() > wound.expiresAt()) {
            wounds.remove(id);
            return;
        }
        event.setAmount(event.getAmount() * (1D - wound.healingReductionPercent() / 100D));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        armedHits.remove(uuid);
        trails.remove(uuid);
        armedShots.remove(uuid);
        ghostActive.remove(uuid);
    }

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) {
        recalculatePassiveAuras(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        recalculatePassiveAuras(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        recalculatePassiveAuras(event.getPlayer());
    }

    /** See {@link #PASSIVE_AURA_EFFECTS}. */
    private void recalculatePassiveAuras(Player player) {
        for (Map.Entry<String, PotionEffectType> entry : PASSIVE_AURA_EFFECTS.entrySet()) {
            PotionEffectType type = entry.getValue();
            List<ActiveRune> matches = active(player, List.of(entry.getKey()));
            if (matches.isEmpty()) {
                if (player.hasPotionEffect(type)) {
                    player.removePotionEffect(type);
                }
                continue;
            }
            int amplifier = matches.getFirst().level().level() - 1;
            PotionEffect current = player.getPotionEffect(type);
            if (current == null || current.getAmplifier() != amplifier) {
                player.addPotionEffect(new PotionEffect(type, Integer.MAX_VALUE, amplifier, true, false, false));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        ghostActive.remove(event.getEntity().getUniqueId());
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        for (ActiveRune rune : active(killer, List.of("XP_BONUS"))) {
            activate(killer, rune, () -> {
                event.setDroppedExp(event.getDroppedExp() + (int) Math.floor(event.getDroppedExp()
                        * Math.max(0D, rune.level().abilityValue()) / 100D));
                return true;
            });
        }
        for (ActiveRune rune : active(killer, List.of("KILL_HEAL"))) {
            activate(killer, rune, () -> {
                killer.setHealth(Math.min(killer.getMaxHealth(), killer.getHealth() + killer.getMaxHealth()
                        * Math.max(0D, rune.level().abilityValue()) / 100D));
                return true;
            });
        }
        for (ActiveRune rune : active(killer, List.of("CORRUPTED_DETONATION"))) {
            activate(killer, rune, () -> detonate(event, killer, rune));
        }
    }

    private boolean detonate(EntityDeathEvent event, Player killer, ActiveRune rune) {
        double damage = event.getEntity().getMaxHealth() * Math.max(0D, rune.level().abilityValue()) / 100D;
        if (damage <= 0D) {
            return false;
        }
        double maxRadius = Math.max(1D, rune.level().setting("radius", 5D));
        org.bukkit.Location center = event.getEntity().getLocation();
        
        boolean hitAny = false;
        for (Entity entity : event.getEntity().getNearbyEntities(maxRadius, maxRadius, maxRadius)) {
            if (!(entity instanceof LivingEntity target) || target instanceof Player || !RuneProtection.canDamage(killer, target)) {
                continue;
            }
            target.damage(damage, killer);
            hitAny = true;
        }

        if (maxRadius > 1.0 && manager.plugin().isEnabled()) {
            new org.bukkit.scheduler.BukkitRunnable() {
                double currentRadius = 0.5;

                @Override
                public void run() {
                    if (currentRadius > maxRadius) {
                        this.cancel();
                        return;
                    }
                    try {
                        int particleCount = (int) (currentRadius * 15);
                        for (int i = 0; i < particleCount; i++) {
                            double angle = 2 * Math.PI * i / particleCount;
                            double x = Math.cos(angle) * currentRadius;
                            double z = Math.sin(angle) * currentRadius;
                            center.getWorld().spawnParticle(org.bukkit.Particle.WITCH, 
                                    center.clone().add(x, 0.5, z), 1, 0, 0, 0, 0);
                        }
                    } catch (Throwable ignored) {
                        // Headless / mock environments without particle support
                    }
                    currentRadius += 1.0;
                }
            }.runTaskTimer(manager.plugin(), 0L, 2L);
        }

        return hitAny;
    }

    /**
     * The {@code /binds} ability queue's entry point into this exact same
     * pipeline -- {@link #activate} still checks blocked-in-combat,
     * cooldown, and activation chance, and still registers the cooldown on
     * success, so a bound activation can never bypass any rule a passive
     * proc is subject to. Only movement effects are bindable today; a
     * bindable, non-movement effect added later needs a branch here too.
     *
     * @param level the rune's currently-available level, already resolved
     *              by the caller (e.g. {@link me.vertex.core.enchant.RuneEquipment#highestAvailableLevel})
     * @return whether the activation actually fired
     */
    public boolean manuallyActivate(Player player, EnchantDefinition definition, EnchantDefinition.Level level) {
        if (player == null || definition == null || level == null) {
            return false;
        }
        ActiveRune rune = new ActiveRune(definition, level);
        return activate(player, rune, () -> switch (definition.effect()) {
            case "SKY_STEPPER", "DASHER", "RIFTWALKER" -> activateMovement(player, rune);
            case "TALON_REND", "CROWN_BREAKER" -> armNextHit(player, rune);
            case "HUNTMASTERS_CALL" -> markTarget(player, rune);
            case "GOLDEN_BASTION" -> pushBastion(player, rune);
            case "SLIPSTREAM" -> activateSlipstream(player, rune);
            case "RALLY_ARROW" -> armNextShot(player, rune);
            case "GHOST" -> activateGhost(player, rune);
            case "ROCKET_ESCAPE" -> activateRocketEscape(player, rune);
            default -> false;
        });
    }

    /**
     * Huntmaster's Call: ray-traces from the caster's eye line for the
     * nearest valid hostile player within {@code target-range-blocks}.
     * Fails (no cooldown consumed, per {@link #activate}'s gate running
     * first) when no valid target is found -- "a required target that is
     * invalid before activation does not consume a cooldown."
     */
    private boolean markTarget(Player player, ActiveRune rune) {
        double range = rune.level().setting("target-range-blocks", 24D);
        Player target = RuneProtection.rayTraceHostilePlayer(player, range);
        if (target == null) {
            return false;
        }
        double supportRange = rune.level().setting("support-range-blocks", 16D);
        double duration = rune.level().setting("mark-duration-seconds", 6D);
        marks.put(target.getUniqueId(), new Mark(player.getUniqueId(), Math.max(0D, rune.level().abilityValue()),
                Math.max(0D, supportRange), System.currentTimeMillis() + Math.round(Math.max(0D, duration) * 1000D)));
        announceMark(rune.definition(), target);
        return true;
    }

    /** Broadcasts every Huntmaster's Call mark to chat, so a caster's teammates know who's about to take bonus damage. */
    private void announceMark(EnchantDefinition definition, Player target) {
        me.vertex.core.lang.Messages currentMessages = messages;
        if (currentMessages == null) {
            return;
        }
        String coloredName = RuneFormatting.coloredNameRaw(me.vertex.core.enchant.RuneTier.SEASONAL, definition.displayName());
        me.vertex.core.preferences.AnnouncementPreferenceManager preferences = announcementPreferences;
        if (preferences != null) {
            preferences.broadcast(me.vertex.core.preferences.AnnouncementCategory.RUNE_ACTIVATION_MESSAGES,
                    "rune.mark-proc-broadcast", "enchant", coloredName, "player", target.getName());
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(currentMessages.get(online, "rune.mark-proc-broadcast",
                    "enchant", coloredName, "player", target.getName()));
        }
    }

    /** Slipstream: an immediate wearer speed burst, plus a trail the shared seasonal tick follows for its duration. */
    private boolean activateSlipstream(Player player, ActiveRune rune) {
        double durationSeconds = rune.level().setting("activation-duration-seconds", 4D);
        int wearerLevel = (int) rune.level().setting("wearer-potion-level", 1D);
        applyPotionIfStronger(player, PotionEffectType.SPEED, wearerLevel - 1, (int) Math.round(durationSeconds * 20D));
        trails.put(player.getUniqueId(), new Trail(player.getUniqueId(), wearerLevel,
                (int) rune.level().setting("friendly-potion-level", 1D), (int) rune.level().setting("enemy-potion-level", 1D),
                rune.level().setting("friendly-contact-seconds", 4D), rune.level().setting("enemy-contact-seconds", 2D),
                Math.max(0.25D, rune.level().setting("trail-contact-radius-blocks", 1.25D)),
                System.currentTimeMillis() + Math.round(Math.max(0D, durationSeconds) * 1000D)));
        ensureSeasonalTask();
        return true;
    }

    /**
     * Rally Arrow: empowers the caster's next bow shot
     * rather than an immediate hit. Consumed exactly once by {@link
     * #onShootBow}, which tags only the single fired projectile -- so a
     * multishot volley still produces only one effect on impact, per spec,
     * simply because only one arrow ever carries the tag.
     */
    private boolean armNextShot(Player player, ActiveRune rune) {
        double windowSeconds = rune.level().setting("arm-window-seconds", 6D);
        armedShots.put(player.getUniqueId(), new ArmedHit(rune.definition(), rune.level(),
                System.currentTimeMillis() + Math.round(Math.max(0D, windowSeconds) * 1000D)));
        return true;
    }

    /**
     * Seasonal "empower the next qualifying hit" mechanic shared by Talon
     * Rend and Crown Breaker. The per-projectile equivalent (Rally Arrow)
     * uses its own arming map ({@link #armedShots}) keyed by the fired
     * projectile, not this one. Arming itself always succeeds once {@link
     * #activate}'s cooldown/combat/proc gate has already passed -- "a
     * required target that is invalid before activation does not consume a
     * cooldown" is handled by that gate running first, not by this method
     * failing.
     */
    private boolean armNextHit(Player player, ActiveRune rune) {
        double windowSeconds = rune.level().setting("arm-window-seconds", 6D);
        armedHits.put(player.getUniqueId(), new ArmedHit(rune.definition(), rune.level(),
                System.currentTimeMillis() + Math.round(Math.max(0D, windowSeconds) * 1000D)));
        return true;
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

    /**
     * Ghost's shared eligibility check -- combat-tagged and at/below the
     * level's health threshold -- re-run both by the automatic trigger
     * ({@link #onDamage}) and, when bound, by {@link #manuallyActivate} at
     * the moment the queued rune actually executes (never cached from
     * queue time, per the Expanded Rune Module spec).
     */
    private boolean ghostEligible(Player player, ActiveRune rune) {
        if (combat == null || !combat.isTagged(player.getUniqueId())) {
            return false;
        }
        double thresholdPercent = rune.level().setting("health-threshold-percent", 25D);
        return player.getHealth() <= player.getMaxHealth() * thresholdPercent / 100D;
    }

    /**
     * Invisibility + Speed for the level's configured duration. Damage
     * taken never cancels it (nothing here reacts to that); dealing an
     * attack does, immediately, via {@link #cancelGhostOnAttack}. Hiding
     * worn armor needs no packet work of its own -- a real Invisibility
     * potion effect already renders a player's entire model, armor
     * included, invisible to everyone else, and restores it the instant
     * the effect ends, which is exactly the visibility contract asked for.
     */
    private boolean activateGhost(Player player, ActiveRune rune) {
        if (!ghostEligible(player, rune)) {
            return false;
        }
        int speedAmplifier = (int) Math.round(rune.level().setting("speed-level", 1D)) - 1;
        int durationTicks = (int) Math.round(rune.level().setting("duration-seconds", 2D) * 20D);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, durationTicks, 0, true, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, durationTicks, Math.max(0, speedAmplifier), true, false));
        ghostActive.add(player.getUniqueId());
        return true;
    }

    /** Dealing an attack cancels Ghost immediately -- called from {@link #onDamageByEntity} before any other attacker-side rune runs. */
    private void cancelGhostOnAttack(Player attacker) {
        if (ghostActive.remove(attacker.getUniqueId())) {
            attacker.removePotionEffect(PotionEffectType.INVISIBILITY);
            attacker.removePotionEffect(PotionEffectType.SPEED);
        }
    }

    /**
     * Rocket Escape: launches the wearer in their facing direction. No
     * fall-damage immunity is granted, per spec -- landing still deals
     * normal fall damage if the launch doesn't clear it.
     */
    private boolean activateRocketEscape(Player player, ActiveRune rune) {
        double thresholdPercent = rune.level().setting("health-threshold-percent", 20D);
        if (player.getHealth() > player.getMaxHealth() * thresholdPercent / 100D) {
            return false;
        }
        Vector direction = player.getLocation().getDirection();
        Vector horizontal = direction.clone().setY(0D);
        if (horizontal.lengthSquared() < 0.0001D) {
            horizontal = new Vector(0D, 0D, 1D);
        }
        horizontal.normalize();
        double forward = rune.level().setting("forward-velocity", 0.65D);
        double upward = rune.level().setting("upward-velocity", 0.55D);
        player.setVelocity(horizontal.multiply(forward).setY(upward));
        return true;
    }

    /**
     * The pipeline entry point: checks blocked-in-combat, cooldown, and
     * activation chance (in that order), then runs {@code action} -- which
     * reports whether it actually fired, since some effects have their own
     * late-stage validation (e.g. Death Guard's health check) that must
     * still skip the cooldown when it doesn't apply. Cooldown is registered
     * only once {@code action} returns true.
     */
    private boolean activate(Player player, ActiveRune rune, BooleanSupplier action) {
        User user = users == null ? null : users.get(player.getUniqueId());
        if (blockedByCombat(player, rune) || onCooldown(user, rune) || !procs(rune.level())) {
            return false;
        }
        if (!action.getAsBoolean()) {
            return false;
        }
        startCooldown(player, user, rune);
        return true;
    }

    /** Active-rune snapshot: every equipped item's matching, currently-active effect, highest level per enchant, priority order. */
    private List<ActiveRune> active(Player player, List<String> effects) {
        if (player == null || effects.isEmpty()) {
            return List.of();
        }
        ZoneType zone = zoneAt(player);
        ZoneManager zoneManager = zones;
        Set<String> blockedTags = zoneManager != null && zone != null ? zoneManager.blockedRuneTagNames(zone) : Set.of();
        Set<String> allowedTags = zoneManager != null && zone != null ? zoneManager.allowedRuneTagNames(zone) : Set.of();
        Map<String, ActiveRune> highestByEnchant = new LinkedHashMap<>();
        for (ItemStack item : equipped(player)) {
            for (Map.Entry<String, Integer> entry : manager.enchantsOf(item).entrySet()) {
                EnchantDefinition definition = manager.definition(entry.getKey());
                if (definition == null || !effects.contains(definition.effect())
                        || !manager.isEffectActive(item, entry.getKey(), player.getWorld().getName())
                        || !definition.isZoneActive(zone)
                        || !RuneProtection.tagsAllowed(definition, blockedTags, allowedTags)) {
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

    private ZoneType zoneAt(Player player) {
        ZoneManager zoneManager = zones;
        if (zoneManager == null || player == null) {
            return null;
        }
        ZoneRegion region = zoneManager.regionAt(player.getLocation());
        return region == null ? null : region.type();
    }

    private static boolean matchesDamageScope(EnchantDefinition definition, boolean fromMob, boolean fromPlayer) {
        return switch (definition.damageSource()) {
            case ANY -> true;
            case MOB -> fromMob;
            case PLAYER -> fromPlayer;
        };
    }

    private static boolean matchesTargetFilter(EnchantDefinition definition, boolean targetIsPlayer) {
        return switch (definition.targetFilter()) {
            case ANY -> true;
            case PLAYER -> targetIsPlayer;
            case MOB -> !targetIsPlayer;
        };
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

    /** Degrades to "not on cooldown" when the player's User record isn't loaded, matching how other listeners in this codebase handle that gap. */
    private boolean onCooldown(User user, ActiveRune rune) {
        return user != null && cooldowns.isOnCooldown(user, rune.definition().id());
    }

    private void startCooldown(Player player, User user, ActiveRune rune) {
        if (user == null) {
            return;
        }
        long duration = Math.max(0L, Math.round(rune.level().setting("cooldown-seconds", 0D) * 1000D));
        if (duration > 0L) {
            cooldowns.start(player, user, rune.definition().id(), duration);
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

    private static boolean isMobDamage(Entity damager) {
        if (damager == null || attacker(damager) != null) {
            return false;
        }
        if (damager instanceof LivingEntity) {
            return true;
        }
        return damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity;
    }

    private static boolean isOre(Material material) {
        return material != null && material.name().endsWith("_ORE");
    }

    private static boolean isCrop(Material material) {
        return material == Material.WHEAT || material == Material.CARROTS || material == Material.POTATOES
                || material == Material.BEETROOTS || material == Material.NETHER_WART || material == Material.COCOA;
    }

    private boolean duplicateDrops(BlockBreakEvent event, double chancePercent) {
        if (chancePercent <= 0D || ThreadLocalRandom.current().nextDouble(100D) >= chancePercent) {
            return false;
        }
        ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack drop : event.getBlock().getDrops(tool, event.getPlayer())) {
            drops.add(drop.clone());
        }
        me.vertex.core.backpack.BackpackAutoStoreListener router = backpacks;
        List<ItemStack> leftovers = router == null ? drops : router.routePlayerMiningDrops(event.getPlayer(), drops);
        for (ItemStack leftover : leftovers) {
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), leftover);
        }
        return true;
    }

    private record ActiveRune(EnchantDefinition definition, EnchantDefinition.Level level) {
    }
}
