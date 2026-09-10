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

/**
 * Two independent, unconditional explosion rules from the TNT / Explosion /
 * Wither spec:
 *
 * <ul>
 *   <li><b>Block damage</b> from any explosion (TNT, beds/respawn anchors,
 *   creepers, etc.) is stripped out of the block list wherever a block
 *   sits inside a Base Claim -- regardless of Faction Shield state. Base
 *   Claims disable explosion block damage on their own; Shield only ever
 *   gates combat (see {@code ShieldCombatListener}), never block damage.
 *   Raid Claims and wilderness are deliberately left untouched here: this
 *   class only ever <em>removes</em> blocks from an explosion's block
 *   list, never adds any back, so whatever FactionsUUID's own territory
 *   protection already allows for TNT raiding outside a Base Claim is
 *   never loosened <em>or</em> further restricted by this listener.</li>
 *   <li><b>Entity damage</b> from an explosion is cancelled for every
 *   player and every mob, everywhere, unconditionally -- not gated by
 *   claim type at all, per spec ("must never damage players ... must
 *   never damage mobs"). This is handled via a plain
 *   {@link EntityDamageEvent} cause check (mirroring
 *   {@code FallDamageImmunityListener}'s shape) rather than by trying to
 *   filter entity lists out of the explosion events themselves --
 *   {@code EntityExplodeEvent}/{@code BlockExplodeEvent} only expose a
 *   block list, not the set of entities the game is about to hurt, so the
 *   damage event is the only hook fine-grained enough to cancel "this
 *   specific entity's explosion damage" while leaving block damage
 *   decisions (above) alone.</li>
 * </ul>
 *
 * <p>The Base Claim query is injected as a plain {@link Predicate} --
 * mirroring {@link BaseClaimManager}'s own {@code shieldActiveQuery}
 * decoupling pattern -- rather than this class holding a
 * {@link BaseClaimManager} field directly. {@code BaseClaimManager.isBaseClaim}
 * calls into {@code FactionsHook}, which reaches FactionsUUID's live
 * {@code Board} singleton; that singleton is never initialized in a unit
 * test (see {@code BaseClaimManagerTest}'s class doc), so injecting the
 * predicate lets tests exercise both branches of the block-damage rule
 * without touching FactionsUUID at all. Production wiring in
 * {@code VertexPlugin} passes {@code baseClaimManager::isBaseClaim}.
 *
 * <p>No existing "admin bypasses claim protection" hook applies here:
 * {@code StaffBuildListener} un-cancels already-cancelled events for
 * players in staff-build mode, but every one of its covered events
 * carries a {@code Player} to check -- {@code EntityExplodeEvent} and
 * {@code BlockExplodeEvent} do not (vanilla Bukkit records no igniting
 * player on a primed TNT entity), so there is no player to check
 * staff-build against without inventing new state this phase's spec
 * never asked for. Left unbypassed deliberately -- see the Phase 3
 * report for detail.
 */
public final class ExplosionProtectionListener implements Listener {

    private final Predicate<Location> isBaseClaim;

    public ExplosionProtectionListener(Predicate<Location> isBaseClaim) {
        this.isBaseClaim = isBaseClaim;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> isBaseClaim.test(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> isBaseClaim.test(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            event.setCancelled(true);
        }
    }
}
