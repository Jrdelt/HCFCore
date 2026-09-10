package me.vertex.core.dupe;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Staff-only inspection and resolution interface for persistent dupe cases.
 * Every resolve/dismiss/confirm attempt is loud-console-logged whether or
 * not it was permitted, mirroring {@code GcCommand#logAdminAttempt} and
 * {@code PunishmentCombatListener}'s bypass logging -- a denied attempt at
 * closing a dupe case is exactly the kind of thing an investigation later
 * needs to see.
 */
public final class DupeCommand implements CommandExecutor, TabCompleter {
    private static final String INSPECT_PERMISSION = "vertex.dupe.inspect";
    private static final String RESOLVE_PERMISSION = "vertex.dupe.resolve";

    private final Plugin plugin;
    private final DupeManager manager;
    private final Messages messages;

    public DupeCommand(Plugin plugin, DupeManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(messages.get(sender, "dupe.usage"));
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "inspect" -> handleInspect(sender, args);
            case "resolve" -> handleTransition(sender, args, DupeCase.STATUS_RESOLVED, "resolve");
            case "dismiss" -> handleTransition(sender, args, DupeCase.STATUS_DISMISSED, "dismiss");
            case "confirm" -> handleTransition(sender, args, DupeCase.STATUS_CONFIRMED, "confirm");
            default -> {
                sender.sendMessage(messages.get(sender, "dupe.usage"));
                yield true;
            }
        };
    }

    private boolean handleInspect(CommandSender sender, String[] args) {
        if (!sender.hasPermission(INSPECT_PERMISSION)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(messages.get(sender, "dupe.usage"));
            return true;
        }
        if (args[1].equalsIgnoreCase("list")) {
            int page = args.length >= 3 ? positiveInt(args[2]) - 1 : 0;
            list(sender, Math.max(0, page));
            return true;
        }
        view(sender, args[1]);
        return true;
    }

    /**
     * Shared handler for {@code /dupe resolve|dismiss|confirm <id> [reason]}.
     * The permission check happens first only to decide the sent message;
     * the console log line is written unconditionally, before the early
     * return, so a denied attempt is never silent.
     */
    private boolean handleTransition(CommandSender sender, String[] args, String status, String verb) {
        if (args.length < 2) {
            sender.sendMessage(messages.get(sender, "dupe.usage"));
            return true;
        }
        String id = args[1];
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "No reason provided";
        boolean permitted = sender.hasPermission(RESOLVE_PERMISSION);
        logAttempt(sender, verb, id, reason, permitted);
        if (!permitted) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        manager.resolve(id, senderName(sender), status, reason).whenComplete((changed, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(messages.get(sender,
                        error == null && Boolean.TRUE.equals(changed) ? "dupe." + verb + "d" : "dupe.not-found",
                        "case", id))));
        return true;
    }

    private void logAttempt(CommandSender sender, String verb, String id, String reason, boolean permitted) {
        String verdict = permitted ? "used" : "attempted (denied, missing " + RESOLVE_PERMISSION + ")";
        plugin.getLogger().warning(senderName(sender) + " " + verdict + " /dupe " + verb + " on case " + id
                + (permitted ? " (" + reason + ")" : ""));
    }

    private void list(CommandSender sender, int page) {
        manager.openCases(page).whenComplete((cases, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || cases == null || cases.isEmpty()) {
                sender.sendMessage(messages.get(sender, "dupe.empty"));
                return;
            }
            sender.sendMessage(messages.get(sender, "dupe.list-header", "page", String.valueOf(page + 1)));
            for (DupeCase entry : cases) {
                sender.sendMessage(messages.get(sender, "dupe.list-line", "case", entry.id(), "item", entry.material(),
                        "player", entry.holderName() == null ? "container" : entry.holderName()));
            }
        }));
    }

    private void view(CommandSender sender, String id) {
        manager.findCase(id).whenComplete((entry, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null || entry == null) {
                sender.sendMessage(messages.get(sender, "dupe.not-found"));
                return;
            }
            sender.sendMessage(messages.get(sender, "dupe.detail-id", "case", entry.id()));
            sender.sendMessage(messages.get(sender, "dupe.detail-item", "item", entry.material(), "id", entry.itemId()));
            sender.sendMessage(messages.get(sender, "dupe.detail-holder", "player",
                    entry.holderName() == null ? "container" : entry.holderName(), "source", entry.source()));
            sender.sendMessage(messages.get(sender, "dupe.detail-time", "time", Instant.ofEpochMilli(entry.createdAt()).toString()));
            sender.sendMessage(messages.get(sender, "dupe.detail-evidence", "details", entry.details()));
            if (!entry.unresolved()) {
                sender.sendMessage(messages.get(sender, "dupe.detail-resolution", "status", entry.status(),
                        "staff", entry.resolvedBy(), "reason", entry.resolution()));
            }
        }));
    }

    private static String senderName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : sender.getName();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission(INSPECT_PERMISSION)) {
                options.add("inspect");
            }
            if (sender.hasPermission(RESOLVE_PERMISSION)) {
                options.add("resolve");
                options.add("dismiss");
                options.add("confirm");
            }
            return prefix(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("inspect") && sender.hasPermission(INSPECT_PERMISSION)) {
            return prefix(List.of("list"), args[1]);
        }
        return List.of();
    }

    private static int positiveInt(String raw) {
        try {
            return Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static List<String> prefix(List<String> options, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).toList();
    }
}
