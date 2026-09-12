package me.vertex.core.portal;

import me.vertex.core.mine.MineManager;
import me.vertex.core.mine.MineRegion;
import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Staff setup command for physical entry portal volumes and guided arrival routes. */
public final class PortalCommand implements CommandExecutor, TabCompleter {
    private final PortalManager portals;
    private final MineManager mines;
    private final Messages messages;

    public PortalCommand(PortalManager portals, MineManager mines, Messages messages) {
        this.portals = portals;
        this.mines = mines;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.get(sender, "general.players-only")); return true; }
        if (!player.hasPermission("vertex.portals.admin")) { player.sendMessage(messages.get(player, "portals.admin-no-permission")); return true; }
        if (args.length == 0) { usage(player); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "wand" -> {
                if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                        portals.plugin(), player, java.util.List.of(portals.selectorItem()),
                        "portal-selector-command")) {
                    player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
                }
            }
            case "list" -> {
                player.sendMessage(messages.get(player, "portals.list-header", "count", String.valueOf(portals.portals().size())));
                portals.portals().forEach(portal -> player.sendMessage(messages.get(player, "portals.list-entry", "id", portal.id(), "target", portal.target().displayName())));
            }
            case "create" -> create(player, args);
            case "delete" -> {
                if (args.length < 2) usage(player);
                else player.sendMessage(messages.get(player, portals.deletePortal(args[1]) ? "portals.deleted" : "portals.not-found"));
            }
            case "route" -> route(player, args);
            default -> usage(player);
        }
        return true;
    }

    private void create(Player player, String[] args) {
        if (args.length < 3) { usage(player); return; }
        String result = portals.beginPortalSelection(player, args[1], target(args[2]));
        player.sendMessage(messages.get(player, switch (result) {
            case "ok" -> "portals.selection-started";
            case "storage" -> "delivery.storage-unavailable";
            default -> "portals.unknown-destination";
        }));
    }

    private void route(Player player, String[] args) {
        if (args.length < 2) { usage(player); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                PortalTarget target = args.length >= 3 ? target(args[2]) : null;
                List<PortalRoute> rows = target == null ? List.copyOf(portals.routes()) : List.copyOf(portals.routes(target));
                player.sendMessage(messages.get(player, "portals.route-list-header", "count", String.valueOf(rows.size())));
                rows.forEach(route -> player.sendMessage(messages.get(player, "portals.route-list-entry", "id", route.id(), "target", route.target().displayName(), "points", String.valueOf(route.waypoints().size()))));
            }
            case "create" -> {
                if (args.length < 4) { usage(player); return; }
                double speed = args.length >= 5 ? number(args[4]) : 0D;
                String result = portals.beginRouteSelection(player, target(args[2]), args[3], speed);
                player.sendMessage(messages.get(player, switch (result) {
                    case "ok" -> "portals.route-selection-started";
                    case "storage" -> "delivery.storage-unavailable";
                    default -> "portals.unknown-destination";
                }));
            }
            case "delete" -> {
                if (args.length < 3) usage(player);
                else player.sendMessage(messages.get(player, portals.deleteRoute(args[2]) ? "portals.route-deleted" : "portals.route-not-found"));
            }
            case "preview" -> {
                if (args.length < 3) usage(player);
                else player.sendMessage(messages.get(player, portals.previewRoute(player, args[2]) ? "portals.route-preview" : "portals.route-unavailable"));
            }
            default -> usage(player);
        }
    }

    private PortalTarget target(String raw) {
        PortalTarget target = PortalTarget.parse(raw);
        return target != null && (target.kind() != PortalTarget.Kind.MINE
                || (mines.region(target.id()) != null && mines.region(target.id()).isDefined())) ? target : null;
    }

    private static double number(String raw) { try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) { return 0D; } }
    private void usage(Player player) {
        player.sendMessage(messages.get(player, "portals.admin-usage"));
        player.sendMessage(messages.get(player, "portals.admin-route-usage"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return complete(args[0], List.of("wand", "list", "create", "delete", "route"));
        // create takes <portal-id> at position 2; destination suggestions are
        // supplied at position 3. The old completion offered destination-like
        // values in the portal-id slot, which made the command easy to enter
        // incorrectly.
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) return List.of();
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) return targets(args[2]);
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) return complete(args[1], portals.portals().stream().map(EntryPortal::id).toList());
        if (!args[0].equalsIgnoreCase("route")) return List.of();
        if (args.length == 2) return complete(args[1], List.of("create", "list", "preview", "delete"));
        if (args.length == 3 && (args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("list"))) return targets(args[2]);
        if (args.length == 3 && (args[1].equalsIgnoreCase("preview") || args[1].equalsIgnoreCase("delete"))) return complete(args[2], portals.routes().stream().map(PortalRoute::id).toList());
        // route create takes <target> <route-id> [speed]. Keep the route-id
        // and speed positions free instead of suggesting fake target values.
        if (args.length == 4 && args[1].equalsIgnoreCase("create")) return List.of();
        if (args.length == 5 && args[1].equalsIgnoreCase("create")) return List.of();
        return List.of();
    }

    private List<String> targets(String typed) {
        List<String> choices = new ArrayList<>(List.of("haven", "riftlands"));
        mines.regions().stream().filter(MineRegion::isDefined).map(MineRegion::id).forEach(choices::add);
        return complete(typed, choices);
    }

    private static List<String> complete(String typed, List<String> choices) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
