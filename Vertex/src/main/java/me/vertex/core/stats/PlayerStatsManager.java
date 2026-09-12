package me.vertex.core.stats;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Records PvP kills and all player deaths after the death event is accepted by the server. */
public final class PlayerStatsManager implements Listener {
    private final Plugin plugin;
    private final PlayerStatsStorage storage;
    private final Map<UUID, PlayerStatsStorage.PlayerStats> values = new ConcurrentHashMap<>();

    public PlayerStatsManager(Plugin plugin, PlayerStatsStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void init() throws Exception {
        storage.init();
        values.putAll(storage.loadAll());
    }

    public PlayerStatsStorage.PlayerStats stats(UUID uuid) {
        return uuid == null ? PlayerStatsStorage.PlayerStats.EMPTY
                : values.getOrDefault(uuid, PlayerStatsStorage.PlayerStats.EMPTY);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        increment(victim.getUniqueId(), 0L, 1L);
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            increment(killer.getUniqueId(), 1L, 0L);
        }
    }

    private void increment(UUID uuid, long kills, long deaths) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerStatsStorage.PlayerStats persisted = storage.increment(uuid, kills, deaths, System.currentTimeMillis());
                values.put(uuid, persisted);
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Could not persist player kill/death statistics for " + uuid, error);
            }
        });
    }
}
