package me.hcfcore.core.staff;

import me.hcfcore.core.storage.Storage;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class DeathManager {

    private final Plugin plugin;
    private final Storage storage;

    public DeathManager(Plugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void saveDeath(UUID uuid, Death death) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                storage.saveDeath(uuid, death);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save death for " + uuid, e);
            }
        });
    }

    public void loadDeaths(UUID uuid, int limit, DeathLoadCallback callback) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<Death> deaths = storage.loadDeaths(uuid, limit);
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.onDeathsLoaded(deaths));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load deaths for " + uuid, e);
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.onDeathsLoaded(List.of()));
            }
        });
    }

    public interface DeathLoadCallback {
        void onDeathsLoaded(List<Death> deaths);
    }
}
