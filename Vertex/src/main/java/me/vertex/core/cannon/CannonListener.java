package me.vertex.core.cannon;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;

/**
 * Hooks the three moments a redstone/dispenser-triggered TNT chain passes
 * through: entity spawn (rate limiting + tagging), fuse-end priming (fire
 * suppression), and the explosion itself (block-damage control + velocity
 * clamp). Ordinary hand-lit TNT never gets tagged, so none of this touches
 * it -- see {@link #onTntSpawn} for the exact split.
 */
public final class CannonListener implements Listener {

    private final CannonManager manager;

    public CannonListener(CannonManager manager) {
        this.manager = manager;
    }

    /**
     * Fires for every newly-created entity, including a TNT block's
     * conversion into a primed entity -- the earliest point a plugin can
     * see "one more TNT entity is about to exist," which is also the
     * actual cost driver for a redstone-clock lag exploit (a ticking
     * entity, whether or not it's exploded yet).
     *
     * <p>{@link TNTPrimed#getSource()} is the entity that caused this TNT
     * to prime, populated by the server before this event fires:
     * null means redstone power or a dispenser lit it directly (no entity
     * involved) -- a built mechanism, by construction. A {@link Player}
     * source means it was lit by hand with flint and steel -- left
     * vanilla. Chain-reaction TNT inherits its parent's tag/tick, one hop
     * at a time, rather than walking the full source chain on every spawn.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTntSpawn(EntitySpawnEvent event) {
        if (!manager.isEnabled() || !(event.getEntity() instanceof TNTPrimed tnt)) {
            return;
        }

        Entity source = tnt.getSource();
        boolean cannon;
        long startTick;
        if (source == null) {
            cannon = true;
            startTick = Bukkit.getCurrentTick();
            if (manager.isBlockedLocation(tnt.getLocation())) {
                event.setCancelled(true);
                return;
            }
        } else if (source instanceof Player) {
            cannon = false;
            startTick = 0L;
        } else if (source instanceof TNTPrimed parent && manager.isCannonTagged(parent)) {
            cannon = true;
            startTick = manager.getStartTick(parent);
        } else {
            cannon = false;
            startTick = 0L;
        }

        if (!cannon) {
            return;
        }

        if (!manager.tryConsumeIgnition(tnt.getWorld(), tnt.getLocation())) {
            // Cap reached for this tick -- cancel outright rather than drop
            // silently. A real redstone clock pulses again within a tick or
            // two on its own, so this reads as a brief stutter, not a
            // vanished shot.
            event.setCancelled(true);
            return;
        }

        manager.tagCannon(tnt, startTick);
    }

    /** Fires once this TNT's fuse reaches zero, just before it explodes. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (!manager.isEnabled() || !(event.getEntity() instanceof TNTPrimed tnt) || !manager.isCannonTagged(tnt)) {
            return;
        }
        if (manager.suppressFireSpread() && manager.isWithinLaunchWindow(manager.getStartTick(tnt))) {
            event.setFire(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!manager.isEnabled() || !(event.getEntity() instanceof TNTPrimed tnt) || !manager.isCannonTagged(tnt)) {
            return;
        }

        long startTick = manager.getStartTick(tnt);
        if (manager.isWithinLaunchWindow(startTick)) {
            // Launch stage: this explosion is part of the firing mechanism
            // itself, not a payload landing somewhere else.
            if (manager.protectLaunchStructure()) {
                event.blockList().clear();
            }
        } else {
            // Landing stage: a payload TNT detonating away from the barrel.
            // Re-check claim rules independently at THIS location -- firing
            // from an allowed spot must not bypass protection on a
            // different one.
            if (manager.isBlockedLocation(event.getLocation())) {
                event.blockList().clear();
            }
            // Otherwise leave the block list as vanilla computed it: full
            // weaponized damage at the landing point.
        }

        manager.clampNearbyVelocities(event.getLocation());
    }
}
