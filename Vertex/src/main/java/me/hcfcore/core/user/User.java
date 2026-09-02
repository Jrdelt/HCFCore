package me.hcfcore.core.user;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class User {

    private final UUID uniqueId;
    private final Map<String, Long> kitCooldowns = new ConcurrentHashMap<>();

    public User(UUID uniqueId, Map<String, Long> kitCooldowns) {
        this.uniqueId = uniqueId;
        if (kitCooldowns != null) {
            this.kitCooldowns.putAll(kitCooldowns);
        }
    }

    public UUID getUniqueId() {
        return uniqueId;
    }

    public long getCooldownExpiry(String kitName) {
        return kitCooldowns.getOrDefault(kitName, 0L);
    }

    public void setCooldownExpiry(String kitName, long expiresAtEpochMillis) {
        kitCooldowns.put(kitName, expiresAtEpochMillis);
    }
}
