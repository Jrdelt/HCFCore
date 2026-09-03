package me.hcfcore.core.faction;

import me.hcfcore.core.factions.FactionsHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

public final class RallyListener implements Listener {
    private final Plugin plugin;
    private final RallyManager rallyManager;

    public RallyListener(Plugin plugin, RallyManager rallyManager) {
        this.plugin = plugin;
        this.rallyManager = rallyManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRallyCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase();

        // Match /f rally, /faction rally, /factions rally
        if (!message.matches("^/(f|faction|factions)\\s+rally.*")) {
            return;
        }

        Player player = event.getPlayer();
        int factionId = FactionsHook.getFactionId(player);

        if (factionId == FactionsHook.NO_FACTION) {
            return;
        }

        // Check if it's a rally SET command (not clear or info)
        if (message.contains(" set") || (!message.contains(" clear") && message.matches("^/(f|faction|factions)\\s+rally\\s*$"))) {
            // Track this rally with 4-minute expiry
            rallyManager.setRallyExpiry(factionId);
        } else if (message.contains(" clear")) {
            // Clear the rally - just remove the expiry timer
            // FactionsUUID will handle the actual clearing
        }
    }

    public void shutdown() {
        // Nothing to clean up
    }
}
