package me.vertex.core.cannon;

import me.vertex.core.VertexPlugin;
import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class CannonCommand implements CommandExecutor, TabCompleter {

    private final VertexPlugin plugin;
    private final CannonManager manager;
    private final Messages messages;

    public CannonCommand(VertexPlugin plugin, CannonManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vertex.cannon.admin")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadCannonModule();
            sender.sendMessage(messages.get(sender, "cannon.reloaded"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("toggle")) {
            boolean nowEnabled = !manager.isEnabled();
            manager.setEnabled(nowEnabled);
            sender.sendMessage(messages.get(sender, nowEnabled ? "cannon.enabled" : "cannon.disabled"));
            return true;
        }

        sender.sendMessage(messages.get(sender, "cannon.usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("reload", "toggle")
                    .filter(sub -> sub.startsWith(partial))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
