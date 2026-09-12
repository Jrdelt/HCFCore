package me.vertex.core.faction;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import java.util.List;

/** Dedicated root `/pvptop` command. */
public final class PvpTopCommand implements CommandExecutor, TabCompleter {
    private final PvpTopManager manager;
    private final Messages messages;

    public PvpTopCommand(PvpTopManager manager, Messages messages) {
        this.manager = manager; this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 0) {
            sender.sendMessage(messages.get(sender, "pvptop.usage"));
            return true;
        }
        sendLeaderboard(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }

    private void sendLeaderboard(CommandSender sender) {
        sender.sendMessage(messages.get(sender, "pvptop.header"));
        var entries = manager.leaderboard();
        if (entries.isEmpty()) { sender.sendMessage(messages.get(sender, "pvptop.empty")); return; }
        int rank = 1;
        for (PvpTopManager.Entry entry : entries) {
            sender.sendMessage(messages.get(sender, "pvptop.entry", "rank", String.valueOf(rank++),
                    "faction", FactionsHook.getFactionName(entry.factionId()), "points", String.valueOf(entry.points())));
        }
    }
}
