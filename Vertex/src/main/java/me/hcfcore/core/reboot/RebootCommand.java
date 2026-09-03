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
            sender.sendMessage(messages.getChat(sender, "general.no-permission"));
            return true;
        }
        if (args.length > 1 || (args.length == 1 && !args[0].equalsIgnoreCase("cancel"))) {
            sender.sendMessage(messages.getChat(sender, "reboot.usage"));
            return true;
        }
        if (args.length == 1) {
            // On success, RebootManager.cancel() already broadcasts
            // reboot.cancelled to every online player and the console --
            // sending it again here would double it up for the sender.
            if (!rebootManager.cancel()) {
                sender.sendMessage(messages.getChat(sender, "reboot.not-scheduled"));
            }
            return true;
        }
        // Same as above: RebootManager.schedule() already broadcasts
        // reboot.started on success.
        if (!rebootManager.schedule()) {
            sender.sendMessage(messages.getChat(sender, "reboot.already-scheduled"));
        }
        return true;
    }
}