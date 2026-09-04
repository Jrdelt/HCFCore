package me.hcfcore.core.ability;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped fall-damage immunity granted by abilities that launch a
 * player into the air (Leap, Grappling Hook). Granted with an expiry
 * rather than indefinitely -- a one-shot flag with no expiry risks
 * silently cancelling a LATER, unrelated fall if the ability's own flight
 * never actually produced a fall-damage event to consume it (landed in
 * water, teleported away mid-air, etc).
 */
public final class FallDamageImmunity {

    private static final Map<UUID, Long> immuneUntil = new ConcurrentHashMap<>();

    private FallDamageImmunity() {
    }

    public static void grant(UUID uuid, long seconds) {
        immuneUntil.put(uuid, System.currentTimeMillis() + seconds * 1000L);
    }

    /** True (and consumes the grant) if `uuid` is currently immune. */
    public static boolean consume(UUID uuid) {
        Long expiry = immuneUntil.remove(uuid);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    public static void forget(UUID uuid) {
        immuneUntil.remove(uuid);
    }
}
