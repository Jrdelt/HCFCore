package me.hcfcore.core.user;

import me.hcfcore.core.storage.Storage;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class UserManager {

    private final Plugin plugin;
    private final Storage storage;
    private final Map<UUID, User> users = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> failedLoads = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loadGenerations = new ConcurrentHashMap<>();

    public UserManager(Plugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    /**
     * Must be called off the main thread, e.g. from AsyncPlayerPreLoginEvent.
     */
    public void load(UUID uuid) {
        long generation = nextGeneration(uuid);
        try {
            Map<String, Long> cooldowns = new HashMap<>(storage.loadCooldowns(uuid));
            // Ability cooldowns live in their own table but share this same
            // map at runtime, namespaced so they can't collide with a kit
            // name -- see AbilityManager.
            for (Map.Entry<String, Long> entry : storage.loadAbilityCooldowns(uuid).entrySet()) {
                cooldowns.put("ability:" + entry.getKey(), entry.getValue());
            }
            String locale = storage.loadLocale(uuid);
            if (loadGenerations.getOrDefault(uuid, 0L) == generation) {
                users.put(uuid, new User(uuid, cooldowns, locale));
                failedLoads.remove(uuid);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load user data for " + uuid, e);
            if (loadGenerations.getOrDefault(uuid, 0L) == generation) {
                users.remove(uuid);
                failedLoads.put(uuid, Boolean.TRUE);
            }
        }
    }

    public void clearFailedLoad(UUID uuid) {
        failedLoads.remove(uuid);
    }

    public void unload(UUID uuid) {
        nextGeneration(uuid);
        users.remove(uuid);
        failedLoads.remove(uuid);
    }

    public User get(UUID uuid) {
        return users.get(uuid);
    }

    public boolean hasFailedLoad(UUID uuid) {
        return failedLoads.containsKey(uuid);
    }

    private long nextGeneration(UUID uuid) {
        return loadGenerations.compute(uuid, (id, previous) -> {
            long generation = previous == null ? 1L : previous + 1L;
            return generation;
        });
    }
}
