package me.vertex.core.grace;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

/** Player-facing `/f grace` status; the audited `/fa` tree owns administration. */
public final class GraceCommand implements Listener {
    private final Plugin plugin;
    private final GraceManager grace;
    private final Messages messages;
    public GraceCommand(Plugin plugin, GraceManager grace, Messages messages) {
        this.plugin = plugin; this.grace = grace; this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFactionCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length >= 2 && isFactionCommand(parts[0]) && parts[1].equalsIgnoreCase("grace")) {
            event.setCancelled(true); status(event.getPlayer());
        }
    }

    private void status(Player player) {
        player.sendMessage(messages.get(player, grace.isActive() ? "grace.status-active" : "grace.status-inactive",
                "remaining", DurationParser.format(grace.secondsRemaining())));
    }

    private boolean isFactionCommand(String command) {
        String normalized = command.contains(":") ? command.substring(command.indexOf(':') + 1) : command;
        return plugin.getConfig().getStringList("factions.command-aliases").stream().anyMatch(a -> a.equalsIgnoreCase(normalized));
    }
}
