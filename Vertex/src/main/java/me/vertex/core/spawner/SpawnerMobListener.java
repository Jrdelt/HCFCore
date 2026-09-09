package me.vertex.core.spawner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

/**
 * Strips all AI (movement, targeting, everything) from anything a tracked
 * spawner produces -- a stack is a safe, stationary grinder, not a threat,
 * only movable by being pushed (lava, water currents, a player) -- and
 * swaps its death drops for the configured table. A spawned mob is tagged
 * with which spawner made it via PDC so onDeath can look the drop table
 * back up without re-searching for a nearby spawner a second time.
 */
public final class SpawnerMobListener implements Listener {

    /** Margin added on top of the configured spawn-range so a slightly
     * off-center spawn still matches its own spawner. */
    private static final double SEARCH_MARGIN = 2.0;

    private final Plugin plugin;
    private final SpawnerManager spawnerManager;
    private final NamespacedKey mobTypeKey;

    private MobStackListener mobStacking;

    public SpawnerMobListener(Plugin plugin, SpawnerManager spawnerManager) {
        this.plugin = plugin;
        this.spawnerManager = spawnerManager;
        this.mobTypeKey = new NamespacedKey(plugin, "spawner_mob_type");
    }

    /** Wired after construction; the two listeners are registered together. */
    public void setMobStacking(MobStackListener mobStacking) {
        this.mobStacking = mobStacking;
    }

    /**
     * Reinstates a spawn from a Vertex-tracked spawner that another plugin
     * cancelled.
     *
     * <p>Runs last and deliberately does not ignore cancelled events, so
     * Vertex has the final say over its own spawners. Another mob-limiter,
     * stacker, or region plugin suppressing them would otherwise leave a
     * player's paid-for spawner silently dead with nothing to point at.
     *
     * <p>Vertex's own stacking merge is exempt: that cancel is how a merge
     * is performed, so undoing it would produce a duplicate mob alongside
     * the stack it was just folded into.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnOverride(CreatureSpawnEvent event) {
        if (!event.isCancelled() || !spawnerManager.overrideOtherPlugins()) {
            return;
        }
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        if (mobStacking != null && mobStacking.wasMergedByStacking(event)) {
            spawnerManager.trace(event.getLocation(), "spawn merged into an existing mob stack (expected)");
            return;
        }
        if (spawnerManager.findNearby(event.getLocation(),
                spawnerManager.spawnRangeBlocks() + SEARCH_MARGIN) == null) {
            // Not one of ours; another plugin's spawner is its own business.
            return;
        }
        spawnerManager.trace(event.getLocation(), "another plugin cancelled this tracked spawn; Vertex restored it");
        event.setCancelled(false);
    }

    // This must run before generic mob stacking so a spawner-produced mob is
    // source-tagged before the stack listener decides what it may merge with.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        Location location = event.getLocation();
        Map.Entry<Location, ?> nearby = spawnerManager.findNearby(location,
                spawnerManager.spawnRangeBlocks() + SEARCH_MARGIN);
        if (nearby == null) {
            return;
        }
        if (!(event.getEntity() instanceof Mob mob)) {
            return;
        }
        mob.getPersistentDataContainer().set(mobTypeKey, PersistentDataType.STRING, mob.getType().name());
        // Strips every AI goal (movement/look/jump/targeting), not just
        // targeting -- these are meant to stand still as grinder fodder,
        // only moving when actually pushed by lava, water currents, or a
        // player, none of which are goal-driven so none of this affects them.
        Bukkit.getMobGoals().removeAllGoals(mob);
        // Undead mobs vanilla-burn in direct sunlight -- fine for a mob
        // that wanders into the open on its own, but these can't move
        // themselves out of the sun (all AI is stripped above) and a
        // stacked one burning to death drops no EXP (see MobStackListener's
        // fire/lava kill rule), so a farm built above ground would just
        // quietly incinerate its own stock for free.
        if (mob instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
        } else if (mob instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
        }
    }

    /** Records the final event state after every other listener has had a chance to veto it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onSpawnTrace(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER
                || spawnerManager.findNearby(event.getLocation(),
                spawnerManager.spawnRangeBlocks() + SEARCH_MARGIN) == null) {
            return;
        }
        spawnerManager.trace(event.getLocation(), event.isCancelled()
                ? "final spawn event: CANCELLED (check a later-priority mob limiter/region plugin)"
                : "final spawn event: allowed for " + event.getEntityType());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }
        String taggedType = entity.getPersistentDataContainer().get(mobTypeKey, PersistentDataType.STRING);
        if (taggedType == null) {
            return;
        }
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(entity.getType());
        if (config == null || config.drops().isEmpty()) {
            return;
        }
        List<ItemStack> drops = spawnerManager.rollDrops(entity.getType());
        event.getDrops().clear();
        event.getDrops().addAll(drops);
    }

    /**
     * Daylight must not burn away Vertex-produced mobs. Lava remains a normal
     * grinder kill method, along with ordinary player combat and other
     * intentional kill methods.
     */
    @EventHandler(ignoreCancelled = true)
    public void onSpawnerMobHeatDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Mob mob)
                || !mob.getPersistentDataContainer().has(mobTypeKey, PersistentDataType.STRING)) {
            return;
        }
        switch (event.getCause()) {
            case FIRE, FIRE_TICK, HOT_FLOOR -> event.setCancelled(true);
            default -> {
                // Not heat damage; a player, fall, suffocation, etc. works normally.
            }
        }
    }
}
