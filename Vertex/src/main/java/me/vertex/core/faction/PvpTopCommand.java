package me.vertex.core.faction;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Root `/pvptop`, with `/f pvptop` retained as a compatibility route. */
public final class PvpTopCommand implements Listener, CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final PvpTopManager manager;
    private final Messages messages;

    public PvpTopCommand(Plugin plugin, PvpTopManager manager, Messages messages) {
        this.plugin = plugin; this.manager = manager; this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length != 2 || !isFactionCommand(parts[0]) || !parts[1].equalsIgnoreCase("pvptop")) return;
        event.setCancelled(true);
        sendLeaderboard(event.getPlayer());
    }

    @EventHandler
    public void onFactionTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof org.bukkit.entity.Player) || !event.getBuffer().startsWith("/")) return;
        String[] parts = event.getBuffer().substring(1).split("\\s+", -1);
        if (parts.length != 2 || !isFactionCommand(parts[0])
                || !"pvptop".startsWith(parts[1].toLowerCase(Locale.ROOT))) return;
        List<String> completions = new ArrayList<>(event.getCompletions());
        if (completions.stream().noneMatch("pvptop"::equalsIgnoreCase)) completions.add("pvptop");
        event.setCompletions(completions);
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

    private boolean isFactionCommand(String command) {
        String normalized = command.contains(":") ? command.substring(command.indexOf(':') + 1) : command;
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .anyMatch(alias -> alias.equalsIgnoreCase(normalized));
    }
}
