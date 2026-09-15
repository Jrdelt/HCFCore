package me.vertex.core.resetvault;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.sql.SQLException;
import java.util.logging.Level;

public final class ResetVaultPlayerListener implements Listener {

    private final ResetVaultManager manager;

    public ResetVaultPlayerListener(ResetVaultManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            ResetVaultData data = manager.storage().loadPlayerData(event.getUniqueId());
            if (data != null) {
                manager.updateCache(data);
            }
        } catch (SQLException e) {
            manager.plugin().getLogger().log(Level.WARNING, "Failed to preload Reset Vault data for " + event.getName(), e);
        }
    }
}
