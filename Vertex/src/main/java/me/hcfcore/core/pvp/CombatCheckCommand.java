package me.hcfcore.core.pvp;

import me.hcfcore.core.lang.Messages;
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
    private final Messages messages;

    public CombatCheckCommand(CombatManager combatManager, Messages messages) {
        this.combatManager = combatManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.combat.check")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(messages.get(sender, "combat.check-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }

        if (!combatManager.isTagged(target.getUniqueId())) {
            sender.sendMessage(messages.get(sender, "combat.check-not-tagged", "player", target.getName()));
            return true;
        }

        long remainingSeconds = (combatManager.remainingMillis(target.getUniqueId()) + 999) / 1000;
        Component message = messages.get(sender, "combat.check-tagged-prefix", "player", target.getName())
                .append(messages.get(sender, "combat.check-time-left", "seconds", String.valueOf(remainingSeconds)));

        UUID opponentId = combatManager.getOpponentId(target.getUniqueId());
        if (CombatManager.SERVER_UUID.equals(opponentId)) {
            message = message.append(messages.get(sender, "combat.check-with-server"));
            sender.sendMessage(message);
            return true;
        }

        Player opponent = opponentId == null ? null : Bukkit.getPlayer(opponentId);
        if (opponent != null && opponent.isOnline()) {
            message = message
                    .append(messages.get(sender, "combat.check-with-opponent-prefix"))
                    .append(Component.text(opponent.getName(), NamedTextColor.WHITE))
                    .append(messages.get(sender, "combat.check-opponent-stats",
                            "health", String.format(Locale.ROOT, "%.1f", opponent.getHealth()),
                            "ping", String.valueOf(opponent.getPing())));
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
