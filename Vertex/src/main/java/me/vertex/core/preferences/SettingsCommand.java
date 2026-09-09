package me.vertex.core.preferences;

import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens a player's personal, persistent announcement preferences. */
public final class SettingsCommand implements CommandExecutor {
    private final AnnouncementPreferenceManager preferences;
    private final Messages messages;

    public SettingsCommand(AnnouncementPreferenceManager preferences, Messages messages) {
        this.preferences = preferences;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.getChat(sender, "general.players-only"));
            return true;
        }
        SettingsMenu.open(player, preferences, messages);
        return true;
    }
}
