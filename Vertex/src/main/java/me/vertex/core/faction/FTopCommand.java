package me.vertex.core.faction;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Owns Vertex's `/f top` view while preserving every other FactionsUUID command. */
public final class FTopCommand implements CommandExecutor, Listener {

    private final Plugin plugin;
    private final FTopManager manager;
    private final Messages messages;

    public FTopCommand(Plugin plugin, FTopManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFactionTop(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length != 2 || !isFactionCommand(event.getPlayer(), parts[0]) || !parts[1].equalsIgnoreCase("top")) {
            return;
        }
        event.setCancelled(true);
        sendTop(event.getPlayer());
    }

    @EventHandler
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player player) || !event.getBuffer().startsWith("/")) {
            return;
        }
        String[] parts = event.getBuffer().substring(1).split("\\s+", -1);
        if (parts.length != 2 || !isFactionCommand(player, parts[0]) || !"top".startsWith(parts[1].toLowerCase(Locale.ROOT))) {
            return;
        }
        List<String> completions = new ArrayList<>(event.getCompletions());
        if (completions.stream().noneMatch("top"::equalsIgnoreCase)) {
            completions.add("top");
            event.setCompletions(completions);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vertex.ftop.forcecheck")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        manager.forceCheck();
        plugin.getLogger().info(sender.getName() + " ran /ftopforcecheck.");
        sender.sendMessage(messages.get(sender, "ftop.forcecheck"));
        return true;
    }

    private void sendTop(Player player) {
        player.sendMessage(messages.get(player, "ftop.header", "time", formatDuration(manager.remainingSeconds())));
        List<FTopManager.Entry> entries = manager.leaderboard();
        if (entries.isEmpty()) {
            player.sendMessage(messages.get(player, "ftop.empty"));
            return;
        }
        for (FTopManager.Entry entry : entries) {
            int delta = entry.score().previousRank() - entry.score().currentRank();
            String movement = delta > 0 ? "▲" + delta : delta < 0 ? "▼" + -delta : "—";
            player.sendMessage(messages.get(player, "ftop.entry", "rank", String.valueOf(entry.score().currentRank()),
                    "faction", FactionsHook.getFactionName(entry.factionId()),
                    "value", EconomyHook.format(entry.score().currentValue()), "movement", movement));
        }
    }

    private static String formatDuration(long seconds) {
        return (seconds / 60L) + "m " + (seconds % 60L) + "s";
    }

    private boolean isFactionCommand(Player player, String command) {
        String normalized = command.contains(":") ? command.substring(command.indexOf(':') + 1) : command;
        return plugin.getConfig()
                .getStringList("factions.command-aliases").stream()
                .anyMatch(alias -> alias.equalsIgnoreCase(normalized));
    }
}
