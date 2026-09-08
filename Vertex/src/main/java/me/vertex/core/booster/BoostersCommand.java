package me.vertex.core.booster;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * {@code /boosters} shows your own effective bonuses;
 * {@code /boosters inspect <player>} shows someone else's, for staff.
 */
public final class BoostersCommand implements CommandExecutor, TabCompleter {

    public static final String INSPECT_PERMISSION = "vertex.boosters.inspect";

    private final BoosterService service;
    private final Messages messages;

    public BoostersCommand(BoosterService service, Messages messages) {
        this.service = service;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (args.length == 0) {
            BoostersMenu.openOverview(player, player, service, messages);
            return true;
        }
        if (!args[0].equalsIgnoreCase("inspect")) {
            sendUsage(player);
            return true;
        }
        if (!player.hasPermission(INSPECT_PERMISSION)) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sendUsage(player);
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage(messages.get(player, "general.player-not-found"));
            return true;
        }
        BoostersMenu.openOverview(player, target, service, messages);
        return true;
    }

    /**
     * The inspect syntax is only shown to those who hold its permission, so a
     * mistyped command never advertises a staff subcommand to a player.
     */
    private void sendUsage(Player player) {
        player.sendMessage(messages.get(player, "boosters.usage"));
        if (player.hasPermission(INSPECT_PERMISSION)) {
            player.sendMessage(messages.get(player, "boosters.usage-admin"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission(INSPECT_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return "inspect".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("inspect") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                    .sorted()
                    .toList();
        }
        return List.of();
    }
}
