package me.vertex.core.teleport;

import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

public final class SpawnCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final GlobalLocationManager locations;
    private final TeleportManager teleports;
    private final Messages messages;
    private volatile Predicate<UUID> zoneExitDispatch = ignored -> false;

    public SpawnCommand(Plugin plugin, GlobalLocationManager locations, TeleportManager teleports, Messages messages) {
        this.plugin = plugin; this.locations = locations; this.teleports = teleports; this.messages = messages;
    }

    /**
     * Allows the zone exit flow to dispatch the real /spawn command without
     * starting the normal spawn countdown a second time.
     */
    public void setZoneExitDispatch(Predicate<UUID> zoneExitDispatch) {
        this.zoneExitDispatch = zoneExitDispatch == null ? ignored -> false : zoneExitDispatch;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.get(sender, "general.players-only")); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("set")) {
            if (!player.hasPermission("vertex.spawn.set")) { player.sendMessage(messages.get(player, "general.no-permission")); return true; }
            locations.setSpawn(player.getLocation().clone()).thenAccept(saved -> plugin.getServer().getScheduler()
                    .runTask(plugin, () -> player.sendMessage(messages.get(player, saved ? "spawn.set" : "spawn.set-failed"))));
            return true;
        }
        int seconds = zoneExitDispatch.test(player.getUniqueId())
                ? 0
                : Math.max(0, plugin.getConfig().getInt("teleports.countdown-seconds", 5));
        teleports.request(player, "spawn", () -> locations.spawnTarget(player), seconds, 0L, true);
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 && sender.hasPermission("vertex.spawn.set") && "set".startsWith(args[0].toLowerCase())
                ? List.of("set") : List.of();
    }
}
