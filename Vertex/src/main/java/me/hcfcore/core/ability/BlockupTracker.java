package me.hcfcore.core.ability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-victim hit counter and block-place-deny window for the Anti-Blockup
 * Bone. Kept separate from the event listener so it's testable without a
 * running server.
 */
public final class BlockupTracker {

    private final Map<UUID, Integer> hitCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> deniedUntil = new ConcurrentHashMap<>();

    /**
     * Records a hit on the victim. Returns true exactly when this hit
     * reached the threshold and triggered the deny window (resetting the
     * counter) -- the caller only applies cooldowns/messages on that hit.
     */
    public boolean recordHit(UUID victim, int hitsRequired, long denySeconds) {
        int count = hitCounts.merge(victim, 1, Integer::sum);
        if (count < hitsRequired) {
            return false;
        }
        hitCounts.remove(victim);
        deniedUntil.put(victim, System.currentTimeMillis() + denySeconds * 1000L);
        return true;
    }

    public boolean isDenied(UUID uuid) {
        Long until = deniedUntil.get(uuid);
        return until != null && until > System.currentTimeMillis();
    }

    public void forget(UUID uuid) {
        hitCounts.remove(uuid);
        deniedUntil.remove(uuid);
    }
}
