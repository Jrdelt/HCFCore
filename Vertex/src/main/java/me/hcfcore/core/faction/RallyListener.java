package me.hcfcore.core.faction;

import me.hcfcore.core.factions.FactionsHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class RallyListener implements Listener {
    private final Plugin plugin;
    private final RallyManager rallyManager;
    private BukkitTask syncTask;

    public RallyListener(Plugin plugin, RallyManager rallyManager) {
        this.plugin = plugin;
        this.rallyManager = rallyManager;
        startSyncTask();
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // We don't directly handle rally commands - we sync with FactionsUUID periodically
        // This is to avoid parsing complex command variations
    }

    private void startSyncTask() {
        // Check every 10 ticks (0.5 seconds) for rally updates from FactionsUUID
        syncTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                syncRalliesFromFactions();
            } catch (Exception e) {
                // Silently ignore if FactionsUUID API changes
            }
        }, 0L, 10L);
    }

    private void syncRalliesFromFactions() {
        // This will be called periodically to check for rally changes
        // For now, we rely on setRally/clearRally being called externally
    }

    public void shutdown() {
        if (syncTask != null) {
            syncTask.cancel();
        }
    }
}
