package me.hcfcore.core.user;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class User {

    private final UUID uniqueId;
    private final Map<String, Long> kitCooldowns = new ConcurrentHashMap<>();
    private volatile String locale;

    public User(UUID uniqueId, Map<String, Long> kitCooldowns, String locale) {
        this.uniqueId = uniqueId;
        if (kitCooldowns != null) {
            this.kitCooldowns.putAll(kitCooldowns);
        }
        this.locale = locale;
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

    /**
     * Null means "use the server default locale" -- see Messages.
     */
    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }
}
