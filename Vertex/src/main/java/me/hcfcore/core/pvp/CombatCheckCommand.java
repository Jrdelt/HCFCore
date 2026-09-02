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
import java.util.UUID;

public final class CombatCheckCommand implements CommandExecutor, TabCompleter {

    private final CombatManager combatManager;

    public CombatCheckCommand(CombatManager combatManager) {
        this.combatManager = combatManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.combat.check")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /combatcheck <player>", NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }

        if (!combatManager.isTagged(target.getUniqueId())) {
            sender.sendMessage(Component.text(target.getName() + " is not in combat.", NamedTextColor.GRAY));
            return true;
        }

        long remainingSeconds = (combatManager.remainingMillis(target.getUniqueId()) + 999) / 1000;
        Component message = Component.text(target.getName() + " is in combat ", NamedTextColor.YELLOW)
                .append(Component.text("(" + remainingSeconds + "s left)", NamedTextColor.GOLD));

        UUID opponentId = combatManager.getOpponentId(target.getUniqueId());
        if (CombatManager.SERVER_UUID.equals(opponentId)) {
            message = message.append(Component.text(" with Server", NamedTextColor.YELLOW));
            sender.sendMessage(message);
            return true;
        }

        Player opponent = opponentId == null ? null : Bukkit.getPlayer(opponentId);
        if (opponent != null && opponent.isOnline()) {
            message = message
                    .append(Component.text(" with ", NamedTextColor.YELLOW))
                    .append(Component.text(opponent.getName(), NamedTextColor.WHITE))
                    .append(Component.text(String.format(" (%.1f hp, %dms)", opponent.getHealth(), opponent.getPing()), NamedTextColor.GRAY));
        }

        sender.sendMessage(message);
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
