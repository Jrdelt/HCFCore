package me.vertex.core.teleport;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;

/** Small /s administrative tree reserved for shared location management. */
public final class ServerAdminCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final GlobalLocationManager locations;
    private final Messages messages;

    public ServerAdminCommand(Plugin plugin, GlobalLocationManager locations, Messages messages) {
        this.plugin = plugin; this.locations = locations; this.messages = messages;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vertex.server.warp")) { sender.sendMessage(messages.get(sender, "general.no-permission")); return true; }
        if (!(sender instanceof Player player) || args.length < 3 || !args[0].equalsIgnoreCase("warp")) {
            sender.sendMessage(messages.get(sender, "warps.admin-usage")); return true;
        }
        String action = args[1], name = args[2];
        java.util.concurrent.CompletableFuture<Boolean> operation;
        if (action.equalsIgnoreCase("set")) {
            String description = args.length <= 3 ? "" : String.join(" ", Arrays.copyOfRange(args, 3, args.length));
            operation = locations.setWarp(name, player.getLocation().clone(), description);
        } else if (action.equalsIgnoreCase("delete")) operation = locations.deleteWarp(name);
        else { sender.sendMessage(messages.get(sender, "warps.admin-usage")); return true; }
        operation.thenAccept(success -> Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(messages.get(sender,
                success ? "warps.admin-saved" : "warps.admin-failed", "name", name))));
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vertex.server.warp")) return List.of();
        if (args.length == 1) return complete(args[0], List.of("warp"));
        if (args.length == 2 && args[0].equalsIgnoreCase("warp")) return complete(args[1], List.of("set", "delete"));
        if (args.length == 3 && args[1].equalsIgnoreCase("delete")) return complete(args[2],
                locations.warps().stream().map(me.vertex.core.network.NetworkStorage.LocationRow::name).toList());
        return List.of();
    }
    private static List<String> complete(String raw,List<String> values){String p=raw.toLowerCase();return values.stream().filter(v->v.toLowerCase().startsWith(p)).toList();}
}
