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

/**
 * Opens the target's live inventory -- Bukkit's {@code openInventory} on
 * another player's actual {@code PlayerInventory} is a two-way live view,
 * no custom GUI needed: edits either side makes show up for both.
 */
public final class InvseeCommand implements CommandExecutor, TabCompleter {

    private final Messages messages;

    public InvseeCommand(Messages messages) {
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!viewer.hasPermission("hcfcore.staff.invsee")) {
            viewer.sendMessage(messages.get(viewer, "general.no-permission"));
            return true;
        }
        if (args.length < 1) {
            viewer.sendMessage(messages.get(viewer, "staff.invsee-usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            viewer.sendMessage(messages.get(viewer, "general.player-not-found"));
            return true;
        }

        viewer.openInventory(target.getInventory());
        viewer.sendMessage(messages.get(viewer, "staff.invsee-opened", "player", target.getName()));
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
