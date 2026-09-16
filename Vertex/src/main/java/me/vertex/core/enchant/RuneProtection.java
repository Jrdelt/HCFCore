package me.vertex.core.enchant;

import me.vertex.core.factions.FactionsHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.Set;

/**
 * The shared entry point every rune effect that performs a NEW world- or
 * entity-affecting action outside an already-occurring Bukkit event must
 * route through, instead of re-deriving claim/zone rules itself. This is a
 * thin composition of existing protection oracles -- mirroring {@code
 * me.vertex.core.claims.ExplosionProtectionListener}'s {@code
 * Predicate<Location>} composition idiom -- not a new protection engine.
 *
 * <p>Every rune effect that only mutates a value on an event that already
 * exists (damage %, XP, healing, movement) needs no change and no call
 * here: that event was already subject to whatever earlier-priority claim
 * listener runs before {@link me.vertex.core.enchant.listener.RuneEffectListener}'s
 * {@code @EventHandler(priority = HIGH, ignoreCancelled = true)} -- e.g.
 * {@code FactionProtectionListener.onPvp} already cancels player-vs-player
 * damage before this package ever sees it, for free. This class exists for
 * the effects that create a brand-new action with no such prior event, like
 * Corrupted Detonation's per-target {@code LivingEntity#damage} calls.
 */
public final class RuneProtection {

    private RuneProtection() {
    }

    /**
     * Whether {@code actor} may damage {@code target} right now. For a
     * player target, this defers entirely to the same faction PvP rules and
     * system-protected-zone check {@code FactionProtectionListener} already
     * enforces on ordinary combat. For a non-player target, this codebase's
     * claim system does not gate mob damage by claim ownership at all, so
     * this returns {@code true} -- the check still exists as the one place
     * to add such a rule later, per the AOE "skip the protected target,
     * don't cancel the whole effect" requirement in the /binds and
     * Infrastructure Cleanup specs.
     */
    public static boolean canDamage(Player actor, LivingEntity target) {
        if (actor == null || target == null) {
            return false;
        }
        if (target instanceof Player victim) {
            if (FactionsHook.isInstalled()) {
                if (!FactionsHook.service().canPvp(actor, victim)) {
                    return false;
                }
                if (FactionsHook.service().isSystemProtectedZone(actor.getLocation())
                        || FactionsHook.service().isSystemProtectedZone(victim.getLocation())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether {@code other} is a recognized teammate of {@code self} for the
     * seasonal set's team-support runes (Huntmaster's Call, Golden Bastion,
     * Slipstream, Rally Arrow): the same player, the same faction, or an
     * allied faction. Absent Factions entirely, only self counts.
     */
    public static boolean isTeamOf(Player self, Player other) {
        if (self == null || other == null) {
            return false;
        }
        if (self.getUniqueId().equals(other.getUniqueId())) {
            return true;
        }
        if (!FactionsHook.isInstalled()) {
            return false;
        }
        if (FactionsHook.isSameFaction(self, other)) {
            return true;
        }
        return FactionsHook.isAllyFaction(FactionsHook.getFactionId(self), FactionsHook.getFactionId(other));
    }

    /**
     * Ray-traces from {@code caster}'s eye line for the nearest player who is
     * NOT a recognized teammate (see {@link #isTeamOf}) within {@code range}
     * blocks -- the single source of truth for every target-lock rune
     * (Huntmaster's Call) and its live HUD readout ({@code BindHudService}),
     * so the HUD can never show a lock the actual activation wouldn't honor,
     * and neither ever locks onto the caster's own faction or an ally.
     */
    public static Player rayTraceHostilePlayer(Player caster, double range) {
        RayTraceResult trace = caster.getWorld().rayTraceEntities(caster.getEyeLocation(),
                caster.getEyeLocation().getDirection(), range, 0.4D,
                candidate -> candidate instanceof Player candidatePlayer && !isTeamOf(caster, candidatePlayer));
        return trace != null && trace.getHitEntity() instanceof Player hit ? hit : null;
    }

    /**
     * Whether {@code definition} is allowed to proc/activate given a zone's
     * raw {@code blocked-tags}/{@code allowed-tags} name sets ({@link
     * me.vertex.core.zone.ZoneManager#blockedRuneTagNames} / {@code
     * allowedRuneTagNames}). Blocked always wins over allowed on overlap.
     * An empty allow-list means no whitelist restriction is in effect. Raw
     * names are parsed against {@link RuneTag} here -- not in {@code
     * ZoneManager} -- so the {@code zone} package never needs to depend on
     * {@code enchant}; an unparseable name is ignored rather than rejected,
     * since it likely means a tag was renamed/removed after config was written.
     */
    public static boolean tagsAllowed(EnchantDefinition definition, Set<String> blockedTagNames, Set<String> allowedTagNames) {
        if (definition == null) {
            return true;
        }
        Set<RuneTag> tags = definition.tags();
        if (tags == null || tags.isEmpty()) {
            return allowedTagNames == null || allowedTagNames.isEmpty();
        }
        if (blockedTagNames != null && !blockedTagNames.isEmpty()) {
            for (RuneTag tag : tags) {
                if (blockedTagNames.contains(tag.name())) {
                    return false;
                }
            }
        }
        if (allowedTagNames != null && !allowedTagNames.isEmpty()) {
            for (RuneTag tag : tags) {
                if (allowedTagNames.contains(tag.name())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}
