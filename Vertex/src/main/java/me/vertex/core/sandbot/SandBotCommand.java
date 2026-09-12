package me.vertex.core.sandbot;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SandBotCommand implements CommandExecutor, TabCompleter {

    private final SandBotManager manager;
    private final Messages messages;

    public SandBotCommand(SandBotManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("vertex.sandbot.give")) {
                sender.sendMessage(messages.get(sender, "general.no-permission"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(messages.get(sender, "general.player-not-found"));
                return true;
            }
            ItemStack item = manager.createGiveItem();
            if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                    manager.plugin(), target, List.of(item), "sandbot-admin-give")) {
                sender.sendMessage(messages.get(sender, "delivery.storage-unavailable"));
                return true;
            }
            sender.sendMessage(messages.get(sender, "sandbot.given", "player", target.getName()));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messages.get(sender, "general.players-only"));
                return true;
            }
            int stopped = manager.stopOwnedBy(player.getUniqueId());
            sender.sendMessage(messages.get(sender, "sandbot.stopped", "amount", String.valueOf(stopped)));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messages.get(sender, "general.players-only"));
                return true;
            }
            if (!player.hasPermission("vertex.sandbot.debug")) {
                player.sendMessage(messages.get(player, "general.no-permission"));
                return true;
            }
            manager.toggleDebug(player);
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("stop")) {
            if (!sender.hasPermission("vertex.sandbot.admin")) {
                sender.sendMessage(messages.get(sender, "general.no-permission"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(messages.get(sender, "general.player-not-found"));
                return true;
            }
            int stopped = manager.stopOwnedBy(target.getUniqueId());
            sender.sendMessage(messages.get(sender, "sandbot.stopped-other",
                    "amount", String.valueOf(stopped), "player", target.getName()));
            return true;
        }

        sender.sendMessage(messages.get(sender, "sandbot.usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("give", "stop", "debug")
                    .filter(sub -> sub.startsWith(partial))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("stop"))) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
