package me.vertex.core.mine;

import me.vertex.core.booster.BoosterService;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
import me.vertex.core.portal.EntryPortal;
import me.vertex.core.portal.PortalManager;
import me.vertex.core.portal.PortalRoute;
import me.vertex.core.portal.PortalTarget;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /mines} for players; {@code /mines create|cancel|list} for staff. */
public final class MinesCommand implements CommandExecutor, TabCompleter {

    public static final String ADMIN_PERMISSION = "vertex.mines.admin";
    private static final List<String> ADMIN_ACTIONS = List.of("create", "fill", "cancel", "list", "portal", "spawnpoints");

    private final MineManager mines;
    private final MineKothManager koths;
    private final HotZoneManager hotZones;
    private final BoosterService boosters;
    private final Messages messages;
    private final MenuRegistry menus;
    private volatile PortalManager portals;

    public MinesCommand(MineManager mines, MineKothManager koths, HotZoneManager hotZones,
            BoosterService boosters, Messages messages, MenuRegistry menus) {
        this.mines = mines;
        this.koths = koths;
        this.hotZones = hotZones;
        this.boosters = boosters;
        this.messages = messages;
        this.menus = menus;
    }

    public void setPortalManager(PortalManager portals) {
        this.portals = portals;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!mines.isEnabled()) {
            player.sendMessage(messages.get(player, "mines.disabled"));
            return true;
        }
        if (args.length == 0) {
            sendOverview(player);
            return true;
        }
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            // Never name the staff subcommands to someone who cannot use them.
            sendOverview(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 2) {
                    player.sendMessage(messages.get(player, "mines.usage-admin"));
                    return true;
                }
                boolean kothZone = args.length >= 3 && args[2].equalsIgnoreCase("koth");
                mines.beginSelection(player, args[1], kothZone);
            }
            case "fill" -> {
                if (args.length < 2) {
                    player.sendMessage(messages.get(player, "mines.usage-admin"));
                    return true;
                }
                MineRegion region = mines.region(args[1]);
                if (region == null || !region.isDefined()) {
                    player.sendMessage(messages.get(player, "mines.not-placed", "mine", args[1]));
                    return true;
                }
                if (!mines.beginFill(region, player.getUniqueId())) {
                    // Already running: show the progress bar rather than
                    // refusing, so a long fill can be checked on.
                    mines.watchFill(region.id(), player.getUniqueId());
                    player.sendMessage(messages.get(player, "mines.fill-busy",
                            "mine", region.displayName(),
                            "percent", String.format("%.1f", Math.max(0D, mines.fillProgressPercent(region.id())))));
                    return true;
                }
                player.sendMessage(messages.get(player, "mines.fill-started", "mine", region.displayName()));
            }
            case "cancel" -> player.sendMessage(messages.get(player,
                    mines.cancelSelection(player) ? "mines.selection-cancelled" : "mines.selection-none"));
            case "list" -> {
                for (MineRegion region : mines.regions()) {
                    player.sendMessage(messages.get(player, region.isDefined()
                                    ? "mines.list-defined" : "mines.list-undefined",
                            "mine", region.id(),
                            "world", region.world() == null || region.world().isBlank() ? "-" : region.world()));
                }
            }
            case "portal" -> portal(player, args);
            case "spawnpoints" -> spawnpoints(player, args);
            default -> player.sendMessage(messages.get(player, "mines.usage-admin"));
        }
        return true;
    }

    private void sendOverview(Player player) {
        MinesMenu.openOverview(player, mines, koths, hotZones, boosters, messages, menus);
    }

    /**
     * Physical mine-entry portal setup lives under /mines rather than a
     * second generic command root.  Its selector uses the same left/right +
     * sneak-air commit contract as mine and KOTH regions.
     */
    private void portal(Player player, String[] args) {
        PortalManager manager = portals;
        if (manager == null || args.length < 2) {
            player.sendMessage(messages.get(player, "mines.portal-usage"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length != 4) {
                    player.sendMessage(messages.get(player, "mines.portal-usage"));
                    return;
                }
                String result = manager.beginPortalSelection(player, args[3], mineTarget(args[2]));
                player.sendMessage(messages.get(player, "ok".equals(result)
                        ? "portals.selection-started" : "portals.unknown-destination",
                        "name", args[3], "target", mineTarget(args[2]).displayName()));
            }
            case "delete" -> player.sendMessage(messages.get(player, args.length == 3 && manager.deletePortal(minePortalForId(manager, args[2]), args[2])
                    ? "portals.deleted" : "portals.not-found"));
            case "list" -> {
                List<EntryPortal> configured = manager.portals().stream()
                        .filter(portal -> portal.target().kind() == PortalTarget.Kind.MINE).toList();
                player.sendMessage(messages.get(player, "portals.list-header", "count", String.valueOf(configured.size())));
                for (EntryPortal portal : configured) {
                    player.sendMessage(messages.get(player, "portals.list-entry", "id", portal.id(),
                            "target", portal.target().displayName()));
                }
            }
            default -> player.sendMessage(messages.get(player, "mines.portal-usage"));
        }
    }

    /** Ordered mine arrival paths. Every clicked point is followed in order. */
    private void spawnpoints(Player player, String[] args) {
        PortalManager manager = portals;
        if (manager == null || args.length < 2) {
            player.sendMessage(messages.get(player, "mines.spawnpoints-usage"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 4 || args.length > 5) {
                    player.sendMessage(messages.get(player, "mines.spawnpoints-usage"));
                    return;
                }
                double speed = 0D;
                if (args.length == 5) {
                    try {
                        speed = Double.parseDouble(args[4]);
                    } catch (NumberFormatException ignored) {
                        player.sendMessage(messages.get(player, "mines.spawnpoints-usage"));
                        return;
                    }
                }
                String result = manager.beginRouteSelection(player, mineTarget(args[2]), args[3], speed);
                player.sendMessage(messages.get(player, "ok".equals(result)
                        ? "portals.route-selection-started" : "portals.unknown-destination"));
            }
            case "delete" -> player.sendMessage(messages.get(player, args.length == 3 && manager.deleteRoute(mineRouteTargetForId(manager, args[2]), args[2])
                    ? "portals.route-deleted" : "portals.route-not-found"));
            case "preview" -> player.sendMessage(messages.get(player, args.length == 3
                    && mineRouteTargetForId(manager, args[2]) != null && manager.previewRoute(player, args[2])
                    ? "portals.route-preview" : "portals.route-unavailable"));
            case "list" -> {
                List<PortalRoute> configured = manager.routes().stream()
                        .filter(route -> route.target().kind() == PortalTarget.Kind.MINE).toList();
                player.sendMessage(messages.get(player, "portals.route-list-header", "count", String.valueOf(configured.size())));
                for (PortalRoute route : configured) {
                    player.sendMessage(messages.get(player, "portals.route-list-entry", "id", route.id(),
                            "target", route.target().displayName(), "points", String.valueOf(route.waypoints().size())));
                }
            }
            default -> player.sendMessage(messages.get(player, "mines.spawnpoints-usage"));
        }
    }

    private static PortalTarget mineTarget(String mineId) {
        return new PortalTarget(PortalTarget.Kind.MINE, mineId);
    }

    private static PortalTarget minePortalForId(PortalManager manager, String id) {
        return manager.portals().stream().filter(portal -> portal.id().equalsIgnoreCase(id))
                .map(EntryPortal::target).filter(target -> target.kind() == PortalTarget.Kind.MINE).findFirst().orElse(null);
    }

    private static PortalTarget mineRouteTargetForId(PortalManager manager, String id) {
        return manager.routes().stream().filter(route -> route.id().equalsIgnoreCase(id))
                .map(PortalRoute::target).filter(target -> target.kind() == PortalTarget.Kind.MINE).findFirst().orElse(null);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String action : ADMIN_ACTIONS) {
                if (action.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(action);
                }
            }
            return options;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("create") || args[0].equalsIgnoreCase("fill"))) {
            return mines.regions().stream()
                    .map(MineRegion::id)
                    .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return "koth".startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of("koth") : List.of();
        }
        PortalManager manager = portals;
        if (args[0].equalsIgnoreCase("portal")) {
            if (args.length == 2) return complete(args[1], List.of("create", "delete", "list"));
            if (args.length == 3 && args[1].equalsIgnoreCase("create")) return mineIds(args[2]);
            if (args.length == 3 && args[1].equalsIgnoreCase("delete") && manager != null) {
                return complete(args[2], manager.portals().stream().filter(portal -> portal.target().kind() == PortalTarget.Kind.MINE)
                        .map(EntryPortal::id).toList());
            }
        }
        if (args[0].equalsIgnoreCase("spawnpoints")) {
            if (args.length == 2) return complete(args[1], List.of("create", "delete", "preview", "list"));
            if (args.length == 3 && args[1].equalsIgnoreCase("create")) return mineIds(args[2]);
            if (args.length == 3 && (args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("preview"))
                    && manager != null) {
                return complete(args[2], manager.routes().stream().filter(route -> route.target().kind() == PortalTarget.Kind.MINE)
                        .map(PortalRoute::id).toList());
            }
        }
        return List.of();
    }

    private List<String> mineIds(String prefix) {
        return mines.regions().stream().map(MineRegion::id)
                .filter(id -> id.startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }

    private static List<String> complete(String prefix, List<String> choices) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
