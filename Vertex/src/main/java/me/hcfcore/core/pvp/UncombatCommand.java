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

public final class UncombatCommand implements CommandExecutor, TabCompleter {

    private final CombatManager combatManager;

    public UncombatCommand(CombatManager combatManager) {
        this.combatManager = combatManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.combat.uncombat")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /uncombat <player>", NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }

        if (!combatManager.isTagged(target.getUniqueId())) {
            sender.sendMessage(Component.text(target.getName() + " isn't in combat.", NamedTextColor.YELLOW));
            return true;
        }

        combatManager.clear(target.getUniqueId());
        sender.sendMessage(Component.text("Cleared " + target.getName() + "'s combat tag.", NamedTextColor.GREEN));
        target.sendMessage(Component.text("Your combat tag was cleared by staff.", NamedTextColor.GREEN));
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
