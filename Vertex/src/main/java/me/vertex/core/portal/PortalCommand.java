package me.vertex.core.portal;

import me.vertex.core.mine.MineManager;
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

    public PortalCommand(PortalManager portals, MineManager mines) {
        this.portals = portals;
        this.mines = mines;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("This command must be run by a player."); return true; }
        if (!player.hasPermission("vertex.portals.admin")) { player.sendMessage("§cYou do not have permission for portal setup."); return true; }
        if (args.length == 0) { usage(player); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "wand" -> player.getInventory().addItem(portals.selectorItem()).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            case "list" -> {
                player.sendMessage("§6Portals (§e" + portals.portals().size() + "§6):");
                portals.portals().forEach(portal -> player.sendMessage("§7 - §f" + portal.id() + " §7→ §b" + portal.target().displayName()));
            }
            case "create" -> create(player, args);
            case "delete" -> {
                if (args.length < 2) usage(player);
                else player.sendMessage(portals.deletePortal(args[1]) ? "§aPortal deleted." : "§cNo portal has that ID.");
            }
            case "route" -> route(player, args);
            default -> usage(player);
        }
        return true;
    }

    private void create(Player player, String[] args) {
        if (args.length < 3) { usage(player); return; }
        String result = portals.beginPortalSelection(player, args[1], target(args[2]));
        player.sendMessage("ok".equals(result) ? "§aSelect two portal corners, then sneak-air click to save." : "§cUnknown or unconfigured destination.");
    }

    private void route(Player player, String[] args) {
        if (args.length < 2) { usage(player); return; }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                PortalTarget target = args.length >= 3 ? target(args[2]) : null;
                List<PortalRoute> rows = target == null ? List.copyOf(portals.routes()) : List.copyOf(portals.routes(target));
                player.sendMessage("§6Portal routes (§e" + rows.size() + "§6):");
                rows.forEach(route -> player.sendMessage("§7 - §f" + route.id() + " §7→ §b" + route.target().displayName() + " §7(" + route.waypoints().size() + " points)"));
            }
            case "create" -> {
                if (args.length < 4) { usage(player); return; }
                double speed = args.length >= 5 ? number(args[4]) : 0D;
                String result = portals.beginRouteSelection(player, target(args[2]), args[3], speed);
                player.sendMessage("ok".equals(result) ? "§aLeft-click route points inside the destination; right-click removes the last. Sneak-air click to save." : "§cUnknown or unconfigured destination.");
            }
            case "delete" -> {
                if (args.length < 3) usage(player);
                else player.sendMessage(portals.deleteRoute(args[2]) ? "§aPortal route deleted." : "§cNo portal route has that ID.");
            }
            case "preview" -> {
                if (args.length < 3) usage(player);
                else player.sendMessage(portals.previewRoute(player, args[2]) ? "§aRoute preview started. Click to drop." : "§cThat route is unavailable.");
            }
            default -> usage(player);
        }
    }

    private PortalTarget target(String raw) {
        PortalTarget target = PortalTarget.parse(raw);
        return target != null && (target.kind() != PortalTarget.Kind.MINE || mines.region(target.id()) != null) ? target : null;
    }

    private static double number(String raw) { try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) { return 0D; } }
    private static void usage(Player player) {
        player.sendMessage("§e/portal wand|list|create <id> <haven|riftlands|mineId>|delete <id>");
        player.sendMessage("§e/portal route create <haven|riftlands|mineId> <id> [speed]|list [target]|preview <id>|delete <id>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return complete(args[0], List.of("wand", "list", "create", "delete", "route"));
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) return complete(args[1], List.of("haven-entry", "riftlands-entry"));
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) return targets(args[2]);
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) return complete(args[1], portals.portals().stream().map(EntryPortal::id).toList());
        if (!args[0].equalsIgnoreCase("route")) return List.of();
        if (args.length == 2) return complete(args[1], List.of("create", "list", "preview", "delete"));
        if (args.length == 3 && (args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("list"))) return targets(args[2]);
        if (args.length == 3 && (args[1].equalsIgnoreCase("preview") || args[1].equalsIgnoreCase("delete"))) return complete(args[2], portals.routes().stream().map(PortalRoute::id).toList());
        if (args.length == 4 && args[1].equalsIgnoreCase("create")) return complete(args[3], List.of("haven-route", "riftlands-route", "mine-route"));
        return List.of();
    }

    private List<String> targets(String typed) {
        List<String> choices = new ArrayList<>(List.of("haven", "riftlands"));
        mines.regions().forEach(region -> choices.add(region.id()));
        return complete(typed, choices);
    }

    private static List<String> complete(String typed, List<String> choices) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        return choices.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
