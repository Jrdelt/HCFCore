package me.hcfcore.core.pvp;

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

public final class UncombatCommand implements CommandExecutor, TabCompleter {

    private final CombatManager combatManager;
    private final Messages messages;

    public UncombatCommand(CombatManager combatManager, Messages messages) {
        this.combatManager = combatManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.combat.uncombat")) {
            sender.sendMessage(messages.getChat(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(messages.getChat(sender, "combat.uncombat-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(messages.getChat(sender, "general.player-not-found"));
            return true;
        }

        if (!combatManager.isTagged(target.getUniqueId())) {
            sender.sendMessage(messages.getChat(sender, "combat.uncombat-not-tagged", "player", target.getName()));
            return true;
        }

        combatManager.clear(target.getUniqueId());
        sender.sendMessage(messages.getChat(sender, "combat.uncombat-cleared-sender", "player", target.getName()));
        target.sendMessage(messages.getChat(target, "combat.uncombat-cleared-target"));
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
