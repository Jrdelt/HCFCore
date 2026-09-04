package me.hcfcore.core.staff;

import me.hcfcore.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FreezeCommand implements CommandExecutor, TabCompleter {

    private final StaffManager staffManager;
    private final Messages messages;

    public FreezeCommand(StaffManager staffManager, Messages messages) {
        this.staffManager = staffManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.staff.freeze")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(messages.get(sender, "staff.freeze-usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }

        boolean now = staffManager.toggleFreeze(target);
        sender.sendMessage(messages.get(sender, now ? "staff.freeze-enabled-sender" : "staff.freeze-disabled-sender",
                "player", target.getName()));
        target.sendMessage(messages.get(target, now ? "staff.freeze-enabled-target" : "staff.freeze-disabled-target"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        String partial = args[0].toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                matches.add(player.getName());
            }
        }
        return matches;
    }
}
