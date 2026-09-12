package me.vertex.core.zone;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import me.vertex.core.portal.PortalManager;
import me.vertex.core.portal.PortalTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** /haven and /riftlands command tree with permission-gated editing/debug branches. */
public final class ZoneCommand implements CommandExecutor, TabCompleter {
    private final ZoneManager zones;
    private final ZoneMenu menu;
    private final Messages messages;
    private final ZoneType fixedType;
    private volatile PortalManager portals;
    private static volatile ArenaControlManager arenaControls;

    public ZoneCommand(ZoneManager zones, ZoneMenu menu, Messages messages, ZoneType fixedType) {
        this.zones = zones; this.menu = menu; this.messages = messages; this.fixedType = fixedType;
    }

    public void setPortalManager(PortalManager portals) { this.portals = portals; }
    public static void setArenaControls(ArenaControlManager manager) { arenaControls = manager; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.get(sender, "general.players-only")); return true; }
        ZoneType type = fixedType;
        if (args.length == 0) return openEntry(player, type);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("lootpool")) { menu.openLoot(player, type, player.hasPermission("vertex.zones.admin")); return true; }
        if (sub.equals("create") || sub.equals("wand") || sub.equals("list")) return compactRegion(player, type, args);
        if (sub.equals("portal")) return portal(player, type, args);
        if (sub.equals("spawnpoints")) return spawnpoints(player,type,args);
        if (sub.equals("koth") || sub.equals("outpost")) return capturePoint(player, type,
                sub.equals("koth") ? ArenaControlManager.ControlType.KOTH : ArenaControlManager.ControlType.OUTPOST, args);
        if (sub.equals("admin")) return admin(player,type,args);
        sender.sendMessage(messages.get(player,"zones.usage")); return true;
    }

    private boolean compactRegion(Player player, ZoneType type, String[] args) {
        if (!adminAllowed(player)) return true;
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            player.sendMessage(messages.get(player, "zones.region-list", "zone", type.displayName(), "regions",
                    String.join(", ", zones.regions(type).stream().map(ZoneRegion::id).toList())));
            return true;
        }
        if (action.equals("wand")) {
            if (zones.queueOverflow(player, List.of(zones.selectorItem()), "zone-selector-command")) {
                player.sendMessage(messages.get(player, "zones.selector-given"));
            } else {
                player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
            }
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(messages.get(player, "zones.region-name-required"));
            return true;
        }
        String result = zones.beginRegionSelection(player, type, args[1]);
        player.sendMessage(messages.get(player, switch (result) {
            case "ok" -> "zones.region-selection-started";
            case "storage" -> "delivery.storage-unavailable";
            default -> "zones.region-name-invalid";
        }));
        return true;
    }

    private boolean portal(Player player, ZoneType type, String[] args) {
        if (!player.hasPermission("vertex.portals.admin")) {
            player.sendMessage(messages.get(player, "portals.admin-no-permission"));
            return true;
        }
        if (args.length != 3 || !args[1].equalsIgnoreCase("create") || portals == null) {
            player.sendMessage(messages.get(player, "zones.portal-create-usage", "command", type.configKey()));
            return true;
        }
        PortalTarget target = new PortalTarget(type == ZoneType.HAVEN
                ? PortalTarget.Kind.HAVEN : PortalTarget.Kind.RIFTLANDS, type.configKey());
        String result = portals.beginPortalSelection(player, args[2], target);
        player.sendMessage(messages.get(player,
                "ok".equals(result) ? "portals.selection-started" : "portals.unknown-destination"));
        return true;
    }

    private boolean openEntry(Player player, ZoneType type) {
        if (!player.hasPermission("vertex.zones.use")) { player.sendMessage(messages.get(player, "zones.no-permission")); return true; }
        menu.openEntry(player,type);
        return true;
    }
    private boolean adminAllowed(Player player) { if(player.hasPermission("vertex.zones.admin"))return true; player.sendMessage(messages.get(player,"zones.no-permission")); return false; }
    private boolean spawnpoints(Player player, ZoneType type, String[] args) {
        if (!adminAllowed(player)) return true;
        if (args.length < 2) return routeUsage(player, type);
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            String region = args.length > 2 ? args[2] : "";
            player.sendMessage(messages.get(player, "zones.route-list", "routes", String.join(", ",
                    zones.allRoutes().stream()
                            .filter(route -> region.isBlank() || route.regionId().equalsIgnoreCase(region))
                            .map(ZoneRoute::id).toList())));
            return true;
        }
        if (action.equals("preview")) {
            if (args.length < 3) return routeUsage(player, type);
            player.sendMessage(messages.get(player, zones.previewRoute(player, args[2])
                    ? "zones.route-preview" : "zones.route-invalid"));
            return true;
        }
        if (action.equals("delete")) {
            if (args.length < 3) return routeUsage(player, type);
            player.sendMessage(messages.get(player, zones.deleteRoute(args[2])
                    ? "zones.route-deleted" : "zones.route-not-found"));
            return true;
        }
        if (!action.equals("create") || args.length < 3) return routeUsage(player, type);

        String regionId;
        String routeId;
        if (args.length > 3) {
            // Preserve the explicit region form for existing administrators.
            regionId = args[2];
            routeId = args[3];
        } else {
            // /riftlands spawnpoints create <route> uses the current matching region, or
            // the only configured Riftlands region if the admin is elsewhere.
            routeId = args[2];
            ZoneRegion current = zones.regionAt(player.getLocation());
            if (current != null && current.type() == type) regionId = current.id();
            else {
                List<ZoneRegion> configured = new ArrayList<>(zones.regions(type));
                if (configured.size() != 1) {
                    player.sendMessage(messages.get(player, "zones.route-region-required",
                            "command", type.configKey()));
                    return true;
                }
                regionId = configured.getFirst().id();
            }
        }

        String result = zones.beginRouteSelection(player, type, regionId, routeId);
        String key = switch (result) {
            case "ok" -> "zones.route-selection-started";
            case "missing-region" -> "zones.route-region-not-found";
            case "invalid" -> "zones.route-name-invalid";
            case "storage" -> "delivery.storage-unavailable";
            default -> "zones.route-selection-failed";
        };
        player.sendMessage(messages.get(player, key, "reason", result));
        return true;
    }
    private boolean routeUsage(Player player, ZoneType type) {
        player.sendMessage(messages.get(player, "zones.route-usage", "command", type.configKey()));
        return true;
    }
    private boolean capturePoint(Player player, ZoneType type, ArenaControlManager.ControlType controlType, String[] args) {
        if (!adminAllowed(player)) return true;
        ArenaControlManager controls = arenaControls;
        if (controls == null) {
            player.sendMessage(messages.get(player, "arena-controls.starting"));
            return true;
        }
        if (args.length < 2) {
            capturePointUsage(player, type, controlType);
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> {
                if (args.length != 3) {
                    player.sendMessage(messages.get(player, "arena-controls.create-usage", "command", type.configKey(), "type", controlType.command()));
                } else {
                    ArenaControlManager.SelectionResult result = controls.beginSelection(player, type, controlType, args[2]);
                    player.sendMessage(messages.get(player, result == ArenaControlManager.SelectionResult.STARTED
                            ? "arena-controls.selection-started" : "arena-controls.invalid-name", "zone", type.displayName(), "type", controlType.display(), "name", args[2]));
                }
            }
            case "wand" -> { controls.giveWand(player, type, controlType); player.sendMessage(messages.get(player, "arena-controls.wand-given", "zone", type.displayName(), "type", controlType.display())); }
            case "cancel" -> player.sendMessage(messages.get(player, controls.cancelSelection(player)
                    ? "arena-controls.selection-cancelled" : "arena-controls.no-selection"));
            case "delete" -> player.sendMessage(messages.get(player, args.length == 3 && controls.delete(type, controlType, args[2])
                    ? "arena-controls.deleted" : "arena-controls.not-found"));
            case "list" -> {
                List<String> names = controls.names(type, controlType);
                player.sendMessage(messages.get(player, names.isEmpty() ? "arena-controls.list-empty" : "arena-controls.list",
                        "type", controlType.display(), "points", String.join(", ", names)));
            }
            default -> capturePointUsage(player, type, controlType);
        }
        return true;
    }
    private void capturePointUsage(Player player, ZoneType type, ArenaControlManager.ControlType controlType) {
        player.sendMessage(messages.get(player, "arena-controls.usage", "command", type.configKey(), "type", controlType.command()));
    }
    private boolean admin(Player player,ZoneType type,String[] args){if(!adminAllowed(player))return true;if(args.length<2){player.sendMessage(messages.get(player,"zones.admin-usage","command",type.configKey()));return true;}switch(args[1].toLowerCase(Locale.ROOT)){case "event"->{if(args.length>2&&args[2].equalsIgnoreCase("start")){zones.forceStartEvent();player.sendMessage(messages.get(player,"zones.admin-event-started"));}else if(args.length>2&&args[2].equalsIgnoreCase("stop")){zones.forceStopEvent();player.sendMessage(messages.get(player,"zones.admin-event-stopped"));}else player.sendMessage(messages.get(player,"zones.admin-unknown"));}case "inspect"->{Player target=args.length>2?Bukkit.getPlayerExact(args[2]):player;if(target!=null){player.sendMessage(messages.get(player,"zones.admin-inspect","player",target.getName(),"haven",String.valueOf(zones.kills(target,ZoneType.HAVEN)),"riftlands",String.valueOf(zones.kills(target,ZoneType.RIFTLANDS)),"amplification",String.valueOf(zones.amplification(target,zones.isIn(target,ZoneType.RIFTLANDS)?ZoneType.RIFTLANDS:ZoneType.HAVEN))));}else player.sendMessage(messages.get(player,"zones.admin-unknown"));}case "clear-mobs", "clearmobs" -> {boolean all=args.length>2&&args[2].equalsIgnoreCase("all");int removed=all?zones.clearAllZoneMobs():zones.clearZoneMobs(type);player.sendMessage(messages.get(player,"zones.admin-mobs-cleared","amount",String.valueOf(removed),"zone",all?"all":type.displayName()));}default->player.sendMessage(messages.get(player,"zones.admin-unknown"));}return true;}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){if(args.length==1)return complete(args[0],List.of("create","wand","list","lootpool","portal","spawnpoints","koth","outpost","admin"));if((args[0].equalsIgnoreCase("koth")||args[0].equalsIgnoreCase("outpost"))&&args.length==2)return complete(args[1],List.of("create","wand","cancel","delete","list"));if((args[0].equalsIgnoreCase("koth")||args[0].equalsIgnoreCase("outpost"))&&args.length==3&&args[1].equalsIgnoreCase("delete")&&arenaControls!=null){ArenaControlManager.ControlType pointType=args[0].equalsIgnoreCase("koth")?ArenaControlManager.ControlType.KOTH:ArenaControlManager.ControlType.OUTPOST;return complete(args[2],arenaControls.names(fixedType,pointType));}if(args[0].equalsIgnoreCase("create")&&args.length==2)return List.of();if(args[0].equalsIgnoreCase("portal")&&args.length==2)return complete(args[1],List.of("create"));if(args[0].equalsIgnoreCase("spawnpoints")){if(args.length==2)return complete(args[1],List.of("create","delete","preview","list"));if(args.length==3&&args[1].equalsIgnoreCase("create"))return complete(args[2],zones.regions(fixedType).stream().map(ZoneRegion::id).toList());if(args.length==3&&(args[1].equalsIgnoreCase("delete")||args[1].equalsIgnoreCase("preview")))return complete(args[2],zones.allRoutes().stream().map(ZoneRoute::id).toList());}if(args[0].equalsIgnoreCase("admin")&&args.length==2)return complete(args[1],List.of("event","inspect","clear-mobs"));if(args[0].equalsIgnoreCase("admin")&&args.length==3&&args[1].equalsIgnoreCase("event"))return complete(args[2],List.of("start","stop"));if(args[0].equalsIgnoreCase("admin")&&args.length==3&&args[1].equalsIgnoreCase("inspect"))return complete(args[2],Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());if(args[0].equalsIgnoreCase("admin")&&args.length==3&&args[1].equalsIgnoreCase("clear-mobs"))return complete(args[2],List.of("all"));return List.of();}
    private List<String> complete(String prefix,List<String> choices){String needle=prefix.toLowerCase(Locale.ROOT);return choices.stream().filter(choice->choice.toLowerCase(Locale.ROOT).startsWith(needle)).toList();}
}
