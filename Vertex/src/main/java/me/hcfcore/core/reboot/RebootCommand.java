package me.hcfcore.core.reboot;

import me.hcfcore.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class RebootCommand implements CommandExecutor {

    private final RebootManager rebootManager;
    private final Messages messages;

    public RebootCommand(RebootManager rebootManager, Messages messages) {
        this.rebootManager = rebootManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.reboot.start")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length > 1 || (args.length == 1 && !args[0].equalsIgnoreCase("cancel"))) {
            sender.sendMessage(messages.get(sender, "reboot.usage"));
            return true;
        }
        if (args.length == 1) {
            if (!rebootManager.cancel()) {
                sender.sendMessage(messages.get(sender, "reboot.not-scheduled"));
            }
            return true;
        }
        if (!rebootManager.schedule()) {
            sender.sendMessage(messages.get(sender, "reboot.already-scheduled"));
        }
        return true;
    }
}