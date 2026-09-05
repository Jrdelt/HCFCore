package me.hcfcore.core;

import me.hcfcore.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class HCFCoreCommand implements CommandExecutor, TabCompleter {

    private final HCFCorePlugin plugin;
    private final Messages messages;

    public HCFCoreCommand(HCFCorePlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.admin")) {
            sender.sendMessage(messages.getChat(sender, "general.no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reload();
            sender.sendMessage(messages.getChat(sender, "admin.reloaded"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("clearmobstacks")) {
            int removed = plugin.clearMobStacks();
            sender.sendMessage(messages.getChat(sender, "admin.mobstacks-cleared", "amount", String.valueOf(removed)));
            return true;
        }

        sender.sendMessage(messages.getChat(sender, "admin.usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String partial = args[0].toLowerCase(Locale.ROOT);
        return Stream.of("reload", "clearmobstacks")
                .filter(sub -> sub.startsWith(partial))
                .collect(Collectors.toList());
    }
}
