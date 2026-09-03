package me.hcfcore.core.ability;

import org.bukkit.Material;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds cooldowns for vanilla consumables which need to remain independent
 * even when Minecraft assigns their item types to the same client cooldown
 * group (notably normal and enchanted golden apples).
 */
public final class VanillaCooldownManager {

    private final Map<UUID, Map<Material, Long>> expiries = new ConcurrentHashMap<>();

    public long remainingMillis(UUID playerId, Material material) {
        Map<Material, Long> playerExpiries = expiries.get(playerId);
        if (playerExpiries == null) {
            return 0L;
        }
        long remaining = playerExpiries.getOrDefault(material, 0L) - System.currentTimeMillis();
        if (remaining <= 0L) {
            playerExpiries.remove(material);
            if (playerExpiries.isEmpty()) {
                expiries.remove(playerId, playerExpiries);
            }
            return 0L;
        }
        return remaining;
    }

    public void start(UUID playerId, Material material, int seconds) {
        if (seconds <= 0) {
            return;
        }
        expiries.computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
                .put(material, System.currentTimeMillis() + seconds * 1000L);
    }

    /**
     * Housekeeping for a leaving player: drops their entry, but only once
     * nothing of theirs is still running. Clearing live cooldowns on quit
     * would let a player relog to reset a pearl or enchanted-gapple
     * cooldown, which is exactly the timer this exists to enforce.
     */
    public void clearIfExpired(UUID playerId) {
        Map<Material, Long> playerExpiries = expiries.get(playerId);
        if (playerExpiries == null) {
            return;
        }
        long now = System.currentTimeMillis();
        playerExpiries.values().removeIf(expiry -> expiry <= now);
        if (playerExpiries.isEmpty()) {
            expiries.remove(playerId, playerExpiries);
        }
    }
}
