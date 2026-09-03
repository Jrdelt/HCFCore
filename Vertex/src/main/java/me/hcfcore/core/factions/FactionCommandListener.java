package me.hcfcore.core.factions;

import me.hcfcore.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

public final class FactionCommandListener implements Listener {

    private final Plugin plugin;
    private final Messages messages;

    public FactionCommandListener(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("factions.prevent-leader-leave", true)) {
            return;
        }

        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        String command = parts.length == 0 ? "" : parts[0];
        int namespaceSeparator = command.indexOf(':');
        if (namespaceSeparator >= 0) {
            command = command.substring(namespaceSeparator + 1);
        }
        if (parts.length < 2 || !isFactionCommand(command) || !parts[1].equalsIgnoreCase("leave")) {
            return;
        }

        Player player = event.getPlayer();
        if (!FactionsHook.isLeader(player)) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(messages.getChat(player, "factions.leader-cannot-leave"));
    }

    private boolean isFactionCommand(String command) {
        String normalized = command.toLowerCase(Locale.ROOT);
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .map(alias -> alias.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }
}