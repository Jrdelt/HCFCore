package me.hcfcore.core.pvp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
 * Admin-only test command: forces two players into combat against each
 * other, for exercising the action bar / uncombat / combatcheck without
 * needing to actually land a hit. With no opponent given, tags the target
 * against the synthetic "Server" opponent instead, so one admin alone can
 * still see the action bar without a second player online.
 */
public final class CombatTagCommand implements CommandExecutor, TabCompleter {

    private final CombatManager combatManager;

    public CombatTagCommand(CombatManager combatManager) {
        this.combatManager = combatManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.combat.tag")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /combattag <player> [opponent|server]", NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }

        if (args.length < 2 || args[1].equalsIgnoreCase("server")) {
            combatManager.tagAgainstServer(target);
            sender.sendMessage(Component.text(
                    "Tagged " + target.getName() + " into combat against \"Server\".", NamedTextColor.GREEN));
            return true;
        }

        Player opponent = Bukkit.getPlayerExact(args[1]);
        if (opponent == null) {
            sender.sendMessage(Component.text("Opponent not found.", NamedTextColor.RED));
            return true;
        }
        if (target.getUniqueId().equals(opponent.getUniqueId())) {
            sender.sendMessage(Component.text("A player can't be tagged against themselves. Omit the opponent to tag against \"Server\" instead.", NamedTextColor.RED));
            return true;
        }

        combatManager.tag(target, opponent);
        sender.sendMessage(Component.text(
                "Tagged " + target.getName() + " and " + opponent.getName() + " into combat.", NamedTextColor.GREEN));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length < 1 || args.length > 2) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 2 && "server".startsWith(partial)) {
            matches.add("server");
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                matches.add(player.getName());
            }
        }
        return matches;
    }
}
