package me.hcfcore.core.staff;

import me.hcfcore.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class StaffBuildCommand implements CommandExecutor {

    private final StaffManager staffManager;
    private final Messages messages;

    public StaffBuildCommand(StaffManager staffManager, Messages messages) {
        this.staffManager = staffManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!player.hasPermission("hcfcore.staff.staffbuild")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }

        boolean now = staffManager.toggleStaffBuild(player);
        player.sendMessage(messages.get(player, now ? "staff.staffbuild-enabled" : "staff.staffbuild-disabled"));
        return true;
    }
}
