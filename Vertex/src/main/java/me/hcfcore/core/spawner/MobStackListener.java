package me.hcfcore.core.spawner;

import me.hcfcore.core.lang.MessageFormatter;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Merges nearby same-type mobs into a single tracked entity (PDC-tagged
 * with a stack count) instead of letting entity count balloon once a
 * stacked spawner is running -- this is deliberately independent of
 * SpawnerMobListener, which only cares about AI-stripping/drop-tables for
 * spawner-sourced mobs specifically, while stacking here applies to any
 * stackable-types mob regardless of how it spawned.
 *
 * <p>Damage/kill rules: a direct player attack peels exactly one mob off
 * the stack (its own reward, the rest of the stack survives unharmed);
 * anything else that would be lethal (fire, lava, ...) is left to kill the
 * entity for real, and onDeath (running after SpawnerMobListener's own
 * onDeath has already decided the correct single-mob drop template)
 * multiplies that template by the stack size and zeroes the exp, per the
 * "no EXP unless it dies directly to a player" rule.
 */
public final class MobStackListener implements Listener {

    private final Plugin plugin;
    private final SpawnerManager spawnerManager;
    private final NamespacedKey stackCountKey;
    private final NamespacedKey spawnerMobKey;
    private final Random random = new Random();

    public MobStackListener(Plugin plugin, SpawnerManager spawnerManager) {
        this.plugin = plugin;
        this.spawnerManager = spawnerManager;
        this.stackCountKey = new NamespacedKey(plugin, "mob_stack_count");
        this.spawnerMobKey = new NamespacedKey(plugin, "spawner_mob_type");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!spawnerManager.isMobStackingEnabled()) {
            return;
        }
        EntityType type = event.getEntityType();
        if (!spawnerManager.stackableTypes().contains(type) || !(event.getEntity() instanceof Mob spawned)) {
            return;
        }

        Mob target = findMergeTarget(event.getLocation(), type);
        if (target != null) {
            event.setCancelled(true);
            int newCount = currentStackCount(target) + 1;
            setStackCount(target, newCount);
            updateDisplay(target, newCount);
            return;
        }

        spawned.setRemoveWhenFarAway(false);
        setStackCount(spawned, 1);
        updateDisplay(spawned, 1);
    }

    /** The nearest existing stack of the same type within merge range that isn't already full, or null. */
    private Mob findMergeTarget(Location location, EntityType type) {
        double radius = spawnerManager.mergeRadiusBlocks();
        if (radius <= 0 || location.getWorld() == null) {
            return null;
        }
        Mob closest = null;
        double closestDistanceSquared = Double.MAX_VALUE;
        for (Entity nearby : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
            if (nearby.getType() != type || !(nearby instanceof Mob mob)) {
                continue;
            }
            Integer count = mob.getPersistentDataContainer().get(stackCountKey, PersistentDataType.INTEGER);
            if (count == null || count >= spawnerManager.maxStackLimit()) {
                continue;
            }
            double distanceSquared = nearby.getLocation().distanceSquared(location);
            if (distanceSquared < closestDistanceSquared) {
                closest = mob;
                closestDistanceSquared = distanceSquared;
            }
        }
        return closest;
    }

    /**
     * Merging only at spawn time misses the common case of a mob-farm
     * shaft: a mob spawns at the top (often well outside merge range of the
     * collection point) and is carried down to an existing stack purely by
     * gravity/water current, neither of which re-fires CreatureSpawnEvent.
     * Run periodically (see HCFCorePlugin's scheduling of this) to fold any
     * such stragglers into whatever same-type stack they've ended up near,
     * regardless of how they got there.
     */
    public void consolidateStacks() {
        if (!spawnerManager.isMobStackingEnabled()) {
            return;
        }
        double radius = spawnerManager.mergeRadiusBlocks();
        if (radius <= 0) {
            return;
        }
        double radiusSquared = radius * radius;
        int limit = spawnerManager.maxStackLimit();

        for (World world : plugin.getServer().getWorlds()) {
            List<Mob> tracked = new ArrayList<>();
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Mob mob && mob.getPersistentDataContainer().has(stackCountKey, PersistentDataType.INTEGER)) {
                    tracked.add(mob);
                }
            }
            for (int i = 0; i < tracked.size(); i++) {
                Mob a = tracked.get(i);
                if (!a.isValid()) {
                    continue;
                }
                int countA = currentStackCount(a);
                for (int j = i + 1; j < tracked.size(); j++) {
                    Mob b = tracked.get(j);
                    if (!b.isValid() || a.getType() != b.getType()) {
                        continue;
                    }
                    int available = limit - countA;
                    if (available <= 0) {
                        break;
                    }
                    if (a.getLocation().distanceSquared(b.getLocation()) > radiusSquared) {
                        continue;
                    }
                    int countB = currentStackCount(b);
                    int transfer = Math.min(countB, available);
                    countA += transfer;
                    setStackCount(a, countA);
                    updateDisplay(a, countA);

                    int remaining = countB - transfer;
                    if (remaining <= 0) {
                        b.remove();
                    } else {
                        setStackCount(b, remaining);
                        updateDisplay(b, remaining);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!spawnerManager.isMobStackingEnabled() || !(event.getEntity() instanceof Mob mob)
                || !spawnerManager.stackableTypes().contains(mob.getType())) {
            return;
        }

        Integer count = mob.getPersistentDataContainer().get(stackCountKey, PersistentDataType.INTEGER);
        if (count == null || count <= 1) {
            // Not a tracked stack, or already down to its last mob -- let it die normally.
            return;
        }
        if (event.getFinalDamage() < mob.getHealth()) {
            // Not a killing blow -- ordinary damage applies as usual.
            return;
        }

        Player attacker = event instanceof EntityDamageByEntityEvent byEntity
                ? resolveAttacker(byEntity.getDamager()) : null;
        if (attacker == null) {
            // Not a direct player attack (fire, lava, ...) -- let the whole
            // stack die for real; onDeath below multiplies what's left behind.
            return;
        }

        event.setCancelled(true);
        peelOne(mob, count, attacker);
    }

    private void peelOne(Mob mob, int count, Player attacker) {
        int newCount = count - 1;
        setStackCount(mob, newCount);
        updateDisplay(mob, newCount);

        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(mob.getType());
        boolean spawnerTagged = mob.getPersistentDataContainer().has(spawnerMobKey, PersistentDataType.STRING);
        List<ItemStack> drops = spawnerTagged && config != null && !config.drops().isEmpty()
                ? spawnerManager.rollDrops(mob.getType())
                : rollNaturalDrops(mob, attacker);

        for (ItemStack drop : drops) {
            mob.getWorld().dropItemNaturally(mob.getLocation(), drop);
        }
        int exp = mob.getPossibleExperienceReward();
        if (exp > 0) {
            ExperienceOrb orb = mob.getWorld().spawn(mob.getLocation(), ExperienceOrb.class);
            orb.setExperience(exp);
        }
    }

    private List<ItemStack> rollNaturalDrops(Mob mob, Player attacker) {
        LootTable table = mob.getLootTable();
        if (table == null) {
            return List.of();
        }
        LootContext context = new LootContext.Builder(mob.getLocation())
                .killer(attacker)
                .build();
        return new ArrayList<>(table.populateLoot(random, context));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        if (!spawnerManager.isMobStackingEnabled()) {
            return;
        }
        Integer count = event.getEntity().getPersistentDataContainer().get(stackCountKey, PersistentDataType.INTEGER);
        if (count == null || count <= 1 || event.getEntity().getKiller() != null) {
            // Untracked, already the last mob, or a genuine last-mob player
            // kill (should already have been peeled above; leaving the
            // single-mob template alone here is the safe fallback either way).
            return;
        }

        List<ItemStack> template = new ArrayList<>(event.getDrops());
        event.getDrops().clear();
        int batchCap = spawnerManager.dropBatchSize();
        for (ItemStack item : template) {
            long remaining = (long) item.getAmount() * count;
            int maxStack = Math.min(batchCap, item.getMaxStackSize());
            while (remaining > 0) {
                int take = (int) Math.min(maxStack, remaining);
                ItemStack batch = item.clone();
                batch.setAmount(take);
                event.getDrops().add(batch);
                remaining -= take;
            }
        }
        event.setDroppedExp(0);
    }

    private int currentStackCount(Mob mob) {
        Integer count = mob.getPersistentDataContainer().get(stackCountKey, PersistentDataType.INTEGER);
        return count == null ? 1 : count;
    }

    private void setStackCount(Mob mob, int count) {
        mob.getPersistentDataContainer().set(stackCountKey, PersistentDataType.INTEGER, count);
    }

    private void updateDisplay(Mob mob, int count) {
        if (count <= 1) {
            mob.customName(null);
            mob.setCustomNameVisible(false);
            return;
        }
        String formatted = spawnerManager.stackDisplayFormat()
                .replace("{count}", String.valueOf(count))
                .replace("{name}", friendlyName(mob.getType()));
        mob.customName(MessageFormatter.deserialize(formatted));
        mob.setCustomNameVisible(true);
    }

    private static String friendlyName(EntityType type) {
        String[] parts = type.name().split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    /**
     * Manual entity-clear for stacked mobs -- removes every tracked stack
     * across every loaded chunk in every world. Used by /hcfcore
     * clearmobstacks; there is deliberately no automatic scheduled version
     * of this, only manual and (by virtue of a restart just not reloading
     * these entities specially) server-reboot cleanup.
     *
     * @return how many stacked mobs (not individual virtual-stack-count) were removed.
     */
    public int clearAll() {
        int removed = 0;
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Mob mob)) {
                    continue;
                }
                if (mob.getPersistentDataContainer().has(stackCountKey, PersistentDataType.INTEGER)) {
                    mob.remove();
                    removed++;
                }
            }
        }
        return removed;
    }
}
