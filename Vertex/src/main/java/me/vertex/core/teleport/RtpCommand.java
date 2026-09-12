package me.vertex.core.teleport;

import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RtpCommand implements CommandExecutor {
    private final RtpManager manager;private final Messages messages;
    public RtpCommand(RtpManager manager,Messages messages){this.manager=manager;this.messages=messages;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){if(!(sender instanceof Player player)){sender.sendMessage(messages.get(sender,"general.players-only"));return true;}manager.open(player);return true;}
}
