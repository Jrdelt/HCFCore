package me.vertex.core;

import me.vertex.core.lang.Messages;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.StorageMigrator;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class VertexCommand implements CommandExecutor, TabCompleter {

    private final VertexPlugin plugin;
    private final Messages messages;

    public VertexCommand(VertexPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vertex.admin")) {
            sender.sendMessage(messages.getChat(sender, "general.no-permission"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reload();
            sender.sendMessage(messages.getChat(sender, "admin.reloaded"));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("clearmobstacks")) {
            int removed = plugin.clearMobStacks();
            sender.sendMessage(messages.getChat(sender, "admin.mobstacks-cleared", "amount", String.valueOf(removed)));
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("spawnerinfo")) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(messages.getChat(sender, "general.players-only"));
                return true;
            }
            org.bukkit.block.Block target = player.getTargetBlockExact(8);
            if (target == null) {
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        "Look at a spawner within 8 blocks and run this again.",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
                return true;
            }
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "--- Spawner report ---", net.kyori.adventure.text.format.NamedTextColor.AQUA));
            for (String line : plugin.spawnerManager().describe(target.getLocation())) {
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        line, net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("spawnerdebug")) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(messages.getChat(sender, "general.players-only"));
                return true;
            }
            boolean enabled = plugin.spawnerManager().toggleDebug(player.getUniqueId());
            player.sendMessage(net.kyori.adventure.text.Component.text(enabled
                    ? "Spawner debugging enabled. Stand within 64 blocks of the spawner; run it again to turn it off."
                    : "Spawner debugging disabled.", enabled
                    ? net.kyori.adventure.text.format.NamedTextColor.GREEN
                    : net.kyori.adventure.text.format.NamedTextColor.GRAY));
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("storage")) {
            return handleStorage(sender, args);
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("performance")) {
            handlePerformance(sender);
            return true;
        }

        sender.sendMessage(messages.getChat(sender, "admin.usage"));
        return true;
    }

    /** Reports the {@code PerformanceManager}'s current OFF/BASIC/DETAILED state -- see its class doc. */
    private void handlePerformance(CommandSender sender) {
        me.vertex.core.performance.PerformanceManager performance = plugin.performanceManager();
        if (performance == null || performance.level() == me.vertex.core.performance.PerformanceManager.Level.OFF) {
            sender.sendMessage(messages.getChat(sender, "performance.off"));
            return;
        }

        sender.sendMessage(messages.getChat(sender, "performance.header",
                "level", performance.level().name(),
                "duration", formatDuration(System.currentTimeMillis() - performance.monitoringSinceMillis())));

        List<me.vertex.core.performance.PerformanceManager.ScheduledTaskInfo> tasks = performance.scheduledTasks();
        if (tasks.isEmpty()) {
            sender.sendMessage(messages.getChat(sender, "performance.no-scheduled-tasks"));
        } else {
            for (var task : tasks) {
                sender.sendMessage(messages.getChat(sender, "performance.scheduled-task",
                        "label", task.label(), "interval", String.valueOf(task.intervalTicks())));
            }
        }

        if (performance.level() != me.vertex.core.performance.PerformanceManager.Level.DETAILED) {
            sender.sendMessage(messages.getChat(sender, "performance.detailed-hint"));
            return;
        }

        List<me.vertex.core.performance.PerformanceManager.TaskStatsSnapshot> stats = performance.taskStats();
        if (stats.isEmpty()) {
            sender.sendMessage(messages.getChat(sender, "performance.no-stats"));
            return;
        }
        for (var stat : stats) {
            sender.sendMessage(messages.getChat(sender, "performance.stat-line",
                    "label", stat.label(), "count", String.valueOf(stat.count()),
                    "avg", String.format(java.util.Locale.ROOT, "%.2f", stat.avgMillis()),
                    "last", String.format(java.util.Locale.ROOT, "%.2f", stat.lastMillis()),
                    "max", String.format(java.util.Locale.ROOT, "%.2f", stat.maxMillis())));
        }
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private boolean handleStorage(CommandSender sender, String[] args) {
        Database.Dialect current = plugin.storageDialect();

        if (args.length == 1) {
            sender.sendMessage(messages.getChat(sender, "admin.storage-current",
                    "type", nameOf(current)));
            return true;
        }

        Database.Dialect target = Database.dialectOf(args[1]);
        boolean namedLocal = args[1].equalsIgnoreCase("local") || args[1].equalsIgnoreCase("sqlite");
        boolean namedMysql = args[1].equalsIgnoreCase("mysql");
        if (!namedLocal && !namedMysql) {
            sender.sendMessage(messages.getChat(sender, "admin.storage-usage"));
            return true;
        }

        if (target == current) {
            sender.sendMessage(messages.getChat(sender, "admin.storage-already",
                    "type", nameOf(current)));
            return true;
        }

        if (!plugin.beginStorageMigration()) {
            sender.sendMessage(messages.getChat(sender, "admin.storage-requires-idle-server"));
            return true;
        }

        boolean confirmed = args.length >= 3 && args[2].equalsIgnoreCase("confirm");
        sender.sendMessage(messages.getChat(sender, "admin.storage-migrating",
                "from", nameOf(current), "to", nameOf(target)));

        // The copy touches two databases and can take a while on a large
        // history table -- it must not run on the main thread.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Database targetDatabase = null;
            try {
                plugin.awaitStorageWritesForMigration();
                targetDatabase = new Database(plugin.getConfig(), plugin.getDataFolder(), target);

                int existing = StorageMigrator.countRows(targetDatabase);
                if (existing > 0 && !confirmed) {
                    Database toClose = targetDatabase;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage(messages.getChat(sender, "admin.storage-not-empty",
                                "type", nameOf(target), "rows", String.valueOf(existing)));
                        toClose.close();
                        plugin.finishStorageMigration();
                    });
                    return;
                }

                StorageMigrator.Result result =
                        StorageMigrator.migrate(plugin.database(), targetDatabase);
                StorageMigrator.writeStorageType(new java.io.File(plugin.getDataFolder(), "config.yml"),
                        target == Database.Dialect.MYSQL ? "mysql" : "local");

                Database toClose = targetDatabase;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    toClose.close();
                    sender.sendMessage(messages.getChat(sender, "admin.storage-migrated",
                            "rows", String.valueOf(result.total()), "type", nameOf(target)));
                    plugin.finishStorageMigration();
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Storage migration failed", e);
                Database toClose = targetDatabase;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (toClose != null) {
                        toClose.close();
                    }
                    sender.sendMessage(messages.getChat(sender, "admin.storage-failed",
                            "error", String.valueOf(e.getMessage())));
                    plugin.finishStorageMigration();
                });
            }
        });
        return true;
    }

    private static String nameOf(Database.Dialect dialect) {
        return dialect == Database.Dialect.MYSQL ? "mysql" : "local";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("reload", "clearmobstacks", "storage", "spawnerinfo", "spawnerdebug", "performance")
                    .filter(sub -> sub.startsWith(partial))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("storage")) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return Stream.of("local", "mysql")
                    .filter(sub -> sub.startsWith(partial))
                    .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("storage")) {
            return "confirm".startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of("confirm") : List.of();
        }
        return List.of();
    }
}
