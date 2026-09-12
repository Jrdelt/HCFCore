package me.vertex.core.claims;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.function.Predicate;

/** Applies Grace and Shield explosion protection only at protected claim locations. */
public final class ExplosionProtectionListener implements Listener {
    private final Predicate<Location> protectedLocation;

    public ExplosionProtectionListener(Predicate<Location> protectedLocation) {
        this.protectedLocation = protectedLocation;
    }

    public ExplosionProtectionListener(Predicate<Location> graceProtected, Predicate<Location> shieldProtected) {
        this(location -> graceProtected.test(location) || shieldProtected.test(location));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> protectedLocation.test(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> protectedLocation.test(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if ((cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)
                && protectedLocation.test(event.getEntity().getLocation())) event.setCancelled(true);
    }
}
