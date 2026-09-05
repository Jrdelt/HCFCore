package me.hcfcore.core.spawner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
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

    public SpawnerMobListener(Plugin plugin, SpawnerManager spawnerManager) {
        this.plugin = plugin;
        this.spawnerManager = spawnerManager;
        this.mobTypeKey = new NamespacedKey(plugin, "spawner_mob_type");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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
}
