package me.vertex.core.capture;

import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Commands shared by /koth and /outpost. Staff setup is deliberately kept separate from player focus. */
public final class CaptureCommand implements CommandExecutor, TabCompleter {
    private static final List<String> STAFF_ACTIONS = List.of("create", "wand", "cancel", "start", "stop", "delete", "list", "validate");

    private final CaptureEventManager manager;
    private final Messages messages;
    private final CaptureEventType type;

    public CaptureCommand(CaptureEventManager manager, Messages messages, CaptureEventType type) {
        this.manager = manager;
        this.messages = messages;
        this.type = type;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("focus")) {
            return focus(sender, args);
        }
        if (!sender.hasPermission("vertex." + type.id() + ".admin")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(messages.get(sender, "general.players-only"));
                    return true;
                }
                if (args.length != 2) {
                    usage(sender);
                    return true;
                }
                manager.beginSelection(player, type, args[1]);
            }
            case "wand" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(messages.get(sender, "general.players-only"));
                    return true;
                }
                manager.giveWand(player, type);
                sender.sendMessage(messages.get(sender, "capture.wand-given", "type", type.display()));
            }
            case "cancel" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(messages.get(sender, "general.players-only"));
                    return true;
                }
                sender.sendMessage(messages.get(sender, manager.cancelSelection(player)
                        ? "capture.selection-cancelled" : "capture.selection-none"));
            }
            case "start", "stop", "delete" -> {
                if (args.length != 2) {
                    usage(sender);
                    return true;
                }
                boolean changed = switch (action) {
                    case "start" -> manager.start(type, args[1]);
                    case "stop" -> manager.stop(type, args[1], true);
                    default -> manager.delete(type, args[1]);
                };
                sender.sendMessage(messages.get(sender, changed ? "capture.admin-" + action : "capture.admin-failed",
                        "type", type.display(), "name", args[1]));
            }
            case "list" -> list(sender);
            case "validate" -> validate(sender);
            default -> usage(sender);
        }
        return true;
    }

    private boolean focus(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (args.length > 2) {
            usage(sender);
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("off")) {
            sender.sendMessage(messages.get(player, manager.clearFocus(player)
                    ? "capture.focus-cleared" : "capture.focus-none"));
            return true;
        }
        String requested = args.length == 2 ? args[1] : null;
        CaptureEventManager.FocusOutcome outcome = manager.toggleFocus(player, type, requested);
        String key = switch (outcome) {
            case FOCUSED -> "capture.focused";
            case CLEARED -> "capture.focus-cleared";
            case NO_ACTIVE -> "capture.focus-no-active";
            case NOT_FOUND -> "capture.focus-not-found";
            case WRONG_WORLD -> "capture.focus-wrong-world";
        };
        String world = "";
        if (outcome == CaptureEventManager.FocusOutcome.WRONG_WORLD && requested != null) {
            CaptureDefinition definition = manager.definition(type, requested);
            world = definition == null ? "" : definition.worldName();
        }
        player.sendMessage(messages.get(player, key, "type", type.display(), "name", requested == null ? "" : requested,
                "world", world));
        return true;
    }

    private void list(CommandSender sender) {
        List<CaptureDefinition> definitions = manager.definitions(type);
        if (definitions.isEmpty()) {
            sender.sendMessage(messages.get(sender, "capture.list-empty", "type", type.display()));
            return;
        }
        sender.sendMessage(messages.get(sender, "capture.list-heading", "type", type.display()));
        for (CaptureDefinition definition : definitions) {
            sender.sendMessage(messages.get(sender, "capture.list-entry", "name", definition.displayName(),
                    "id", definition.id(), "world", definition.worldName(),
                    "state", manager.isActive(type, definition.id()) ? "active" : "idle"));
        }
    }

    private void validate(CommandSender sender) {
        List<CaptureEventManager.ValidationIssue> issues = manager.validate(type);
        if (issues.isEmpty()) {
            sender.sendMessage(messages.get(sender, "capture.validate-valid", "type", type.display()));
            return;
        }
        sender.sendMessage(messages.get(sender, "capture.validate-heading", "type", type.display(),
                "count", String.valueOf(issues.size())));
        for (CaptureEventManager.ValidationIssue issue : issues) {
            sender.sendMessage(messages.get(sender, "capture.validate-error", "event", issue.eventId(),
                    "path", issue.path()).append(messages.get(sender, issue.reasonKey(), "value", issue.value())));
        }
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(messages.get(sender, "capture.command-usage", "command", type.id()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>();
            if ("focus".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                choices.add("focus");
            }
            if (sender.hasPermission("vertex." + type.id() + ".admin")) {
                STAFF_ACTIONS.stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).forEach(choices::add);
            }
            return choices;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("focus")) {
            List<String> choices = new ArrayList<>();
            choices.add("off");
            manager.definitions(type).stream().map(CaptureDefinition::id).forEach(choices::add);
            return choices.stream().filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("start", "stop", "delete").contains(args[0].toLowerCase(Locale.ROOT))
                && sender.hasPermission("vertex." + type.id() + ".admin")) {
            return manager.definitions(type).stream().map(CaptureDefinition::id)
                    .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
