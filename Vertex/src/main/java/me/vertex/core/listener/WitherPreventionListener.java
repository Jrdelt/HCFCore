package me.vertex.core.listener;

import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Withers are disabled server-wide, per spec -- no exceptions, no
 * world/claim scoping, and deliberately no chat feedback (mirrors vanilla
 * itself, which gives no message when a spawn is silently blocked by
 * something else, e.g. a full world).
 *
 * <p>{@link CreatureSpawnEvent} fires for every path Bukkit knows a
 * {@link org.bukkit.entity.Wither} could come into existence, including
 * {@link CreatureSpawnEvent.SpawnReason#BUILD_WITHER} -- the vanilla
 * soul-sand-and-three-skulls construction, the primary way players
 * actually make one. Spawner-based spawns fire the same event with
 * {@code SpawnReason.SPAWNER}, spawn eggs with {@code SPAWNER_EGG}, and so
 * on, so a single entity-type check here uniformly covers every path
 * without needing a separate hook per spawn method.
 */
public final class WitherPreventionListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() == EntityType.WITHER) {
            event.setCancelled(true);
        }
    }
}
