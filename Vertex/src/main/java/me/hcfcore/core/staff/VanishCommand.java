package me.hcfcore.core.staff;

import me.hcfcore.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class VanishCommand implements CommandExecutor {

    private final StaffManager staffManager;
    private final Messages messages;

    public VanishCommand(StaffManager staffManager, Messages messages) {
        this.staffManager = staffManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!player.hasPermission("hcfcore.staff.vanish")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }

        boolean nowVanished = staffManager.toggleVanish(player);
        player.sendMessage(messages.get(player, nowVanished ? "staff.vanish-enabled" : "staff.vanish-disabled"));
        return true;
    }
}
