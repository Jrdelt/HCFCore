package me.hcfcore.core.staff;

import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RollbackCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final DeathManager deathManager;
    private final Messages messages;

    public RollbackCommand(Plugin plugin, DeathManager deathManager, Messages messages) {
        this.plugin = plugin;
        this.deathManager = deathManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.staff.rollback")) {
            sender.sendMessage(messages.getChat(sender, "general.no-permission"));
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("Only players can use this command").color(NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /rollback <player>").color(NamedTextColor.RED));
            return true;
        }

        Player staffPlayer = (Player) sender;
        Player targetPlayer = Bukkit.getPlayerExact(args[0]);

        if (targetPlayer == null) {
            sender.sendMessage(messages.getChat(sender, "general.player-not-found"));
            return true;
        }

        InvRestoreMenu.open(staffPlayer, targetPlayer, plugin, deathManager);
        sender.sendMessage(Component.text("Opening death history for " + targetPlayer.getName())
                .color(NamedTextColor.GREEN));
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
