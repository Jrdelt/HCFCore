package me.vertex.core.enchant.binds;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A tiny shared coordinator so the Bind HUD can temporarily take over a
 * player's BossBar display space without touching any other feature's own
 * timer/state. No shared BossBar manager exists in this codebase --
 * {@code RallyManager}, {@code CaptureEventManager}, {@code MineManager},
 * and {@code ZoneManager}'s event bar each manage their own bars
 * independently -- so each of those four gets one guard line added to its
 * existing per-tick update ("if suppressed, hide and skip this tick"
 * instead of updating), added deliberately as the only way to satisfy
 * "suppress other bossbars while the Bind HUD is open" without rewriting
 * any of those systems or building a new BossBar manager.
 */
public final class BossBarSuppression {
    private static final Set<UUID> suppressed = ConcurrentHashMap.newKeySet();

    private BossBarSuppression() {
    }

    public static boolean isSuppressed(UUID uuid) {
        return uuid != null && suppressed.contains(uuid);
    }

    public static void suppress(UUID uuid) {
        if (uuid != null) {
            suppressed.add(uuid);
        }
    }

    public static void release(UUID uuid) {
        if (uuid != null) {
            suppressed.remove(uuid);
        }
    }
}
