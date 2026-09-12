package me.vertex.core.trade;

import me.vertex.core.lang.Messages;
import me.vertex.core.preferences.AnnouncementCategory;
import me.vertex.core.preferences.AnnouncementPreferenceManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Alternate command for the same persisted Trade Requests setting shown in /settings. */
public final class TradeToggleCommand implements CommandExecutor {
    private final AnnouncementPreferenceManager preferences;
    private final Messages messages;

    public TradeToggleCommand(AnnouncementPreferenceManager preferences, Messages messages) {
        this.preferences = preferences;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        boolean accepting = preferences.toggle(player.getUniqueId(), AnnouncementCategory.TRADE_REQUESTS);
        player.sendMessage(messages.get(player, "settings.updated-prefix")
                .append(messages.get(player, "settings.trade-requests"))
                .append(net.kyori.adventure.text.Component.text(": "))
                .append(messages.get(player, accepting ? "settings.enabled" : "settings.disabled")));
        return true;
    }
}
