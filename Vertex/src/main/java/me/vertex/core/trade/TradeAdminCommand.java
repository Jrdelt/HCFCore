package me.vertex.core.trade;

import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** Reloads and validates traders.yml without requiring a global plugin reload. */
public final class TradeAdminCommand implements CommandExecutor {
    private final TradeManager manager; private final Messages messages;
    public TradeAdminCommand(TradeManager manager, Messages messages) { this.manager = manager; this.messages = messages; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vertex.trade.staff.reload")) { sender.sendMessage(messages.get(sender, "general.no-permission")); return true; }
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) { sender.sendMessage(messages.get(sender, "trade.admin-usage")); return true; }
        manager.load(); sender.sendMessage(messages.get(sender, "trade.admin-reloaded")); return true;
    }
}
