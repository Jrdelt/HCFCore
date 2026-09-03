package me.hcfcore.core.reboot;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class NextRebootCommand implements CommandExecutor {

    private final RebootManager rebootManager;

    public NextRebootCommand(RebootManager rebootManager) {
        this.rebootManager = rebootManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        rebootManager.sendNextReboot(sender);
        return true;
    }
}