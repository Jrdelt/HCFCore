package me.vertex.core.teleport;

import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class WarpCommand implements CommandExecutor, TabCompleter {
    private final GlobalLocationManager locations;
    private final TeleportManager teleports;
    private final WarpMenu menu;
    private final Messages messages;
    private final int countdown;

    public WarpCommand(GlobalLocationManager locations, TeleportManager teleports, WarpMenu menu,
            Messages messages, int countdown) {
        this.locations = locations; this.teleports = teleports; this.menu = menu;
        this.messages = messages; this.countdown = countdown;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.get(sender, "general.players-only")); return true; }
        if (args.length == 0) { menu.open(player); return true; }
        String name = args[0];
        if (locations.warpTarget(name) == null) { player.sendMessage(messages.get(player, "warps.not-found")); return true; }
        teleports.request(player, "warp", () -> locations.warpTarget(name), countdown, 0L, true);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        return locations.warps().stream().map(me.vertex.core.network.NetworkStorage.LocationRow::name)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).toList();
    }
}
