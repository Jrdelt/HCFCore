package me.vertex.core.join;

import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /firsttimejoin kit} -- snapshots the sender's current inventory as the one-time first-join kit. */
public final class FirstJoinKitCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "vertex.firsttimejoin.admin";

    private final FirstJoinKitManager manager;
    private final Messages messages;

    public FirstJoinKitCommand(FirstJoinKitManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("kit")) {
            player.sendMessage(messages.get(player, "first-join-kit.usage"));
            return true;
        }
        manager.save(player);
        player.sendMessage(messages.get(player, "first-join-kit.saved"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission(PERMISSION)) {
            return "kit".startsWith(args[0].toLowerCase(java.util.Locale.ROOT)) ? List.of("kit") : List.of();
        }
        return List.of();
    }
}
