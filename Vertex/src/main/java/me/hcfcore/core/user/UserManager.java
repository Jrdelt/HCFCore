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

    public UserManager(Plugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    /**
     * Must be called off the main thread, e.g. from AsyncPlayerPreLoginEvent.
     */
    public void load(UUID uuid) {
        try {
            Map<String, Long> cooldowns = new HashMap<>(storage.loadCooldowns(uuid));
            // Ability cooldowns live in their own table but share this same
            // map at runtime, namespaced so they can't collide with a kit
            // name -- see AbilityManager.
            for (Map.Entry<String, Long> entry : storage.loadAbilityCooldowns(uuid).entrySet()) {
                cooldowns.put("ability:" + entry.getKey(), entry.getValue());
            }
            users.put(uuid, new User(uuid, cooldowns));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load user data for " + uuid, e);
            users.put(uuid, new User(uuid, Map.of()));
        }
    }

    public void unload(UUID uuid) {
        users.remove(uuid);
    }

    public User get(UUID uuid) {
        return users.get(uuid);
    }
}
