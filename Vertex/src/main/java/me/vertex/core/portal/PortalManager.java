package me.vertex.core.portal;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.mine.MineManager;
import me.vertex.core.mine.MineRegion;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.zone.ZoneManager;
import me.vertex.core.zone.ZoneRegion;
import me.vertex.core.zone.ZoneRoute;
import me.vertex.core.zone.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/** Durable portal triggers and server-authoritative guided arrival routes. */
public final class PortalManager {
    private final Plugin plugin;
    private final PortalStorage storage;
    private final MineManager mines;
    private final ZoneManager zones;
    private final CombatManager combat;
    private final Messages messages;
    private final NamespacedKey selectorKey;
    private final Map<String, EntryPortal> portals = new ConcurrentHashMap<>();
    private final Map<String, PortalRoute> routes = new ConcurrentHashMap<>();
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    /** Player currently descending onto a target, and the flight state to restore once they land. */
    private final Map<UUID, FlightState> descending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private BukkitTask task;
    private volatile double defaultSpeed = .8D;
    private volatile long activationCooldownMillis = 3_000L;

    public PortalManager(Plugin plugin, PortalStorage storage, MineManager mines, ZoneManager zones,
            CombatManager combat, Messages messages) {
        this.plugin = plugin;
        this.storage = storage;
        this.mines = mines;
        this.zones = zones;
        this.combat = combat;
        this.messages = messages;
        selectorKey = new NamespacedKey(plugin, "portal_selector");
    }

    public void init() throws SQLException {
        storage.init();
        load();
    }

    public void load() {
        defaultSpeed = bounded(plugin.getConfig().getDouble("portals.flight.speed", .8D), .05D, 8D);
        activationCooldownMillis = Math.max(0L,
                plugin.getConfig().getLong("portals.activation-cooldown-seconds", 3L) * 1_000L);
        try {
            portals.clear();
            for (EntryPortal portal : storage.loadPortals()) portals.put(portal.id(), portal);
            routes.clear();
            for (PortalStorage.RouteRow row : storage.loadRoutes()) {
                PortalTarget target = PortalTarget.fromStorage(row.target());
                decodeWaypoints(row.waypoints()).ifPresent(points -> {
                    if (target != null && !points.isEmpty()) {
                        routes.put(row.id(), new PortalRoute(row.id(), target, row.speed(), points));
                    }
                });
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not load portal definitions", exception);
        }
    }

    public void start() {
        shutdown();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        for (UUID playerId : List.copyOf(descending.keySet())) releaseFlight(playerId, false);
        selections.clear();
        cooldowns.clear();
    }

    public Collection<EntryPortal> portals() {
        return portals.values().stream().sorted(Comparator.comparing(EntryPortal::id)).toList();
    }

    public Collection<PortalRoute> routes() {
        return routes.values().stream().sorted(Comparator.comparing(PortalRoute::id)).toList();
    }

    public Collection<PortalRoute> routes(PortalTarget target) {
        return routes.values().stream().filter(route -> route.target().equals(target))
                .sorted(Comparator.comparing(PortalRoute::id)).toList();
    }

    public boolean isFlying(UUID playerId) { return descending.containsKey(playerId); }

    /** Starts a mine-menu arrival using the configured random spawn points. */
    public boolean startMineEntry(Player player, String mineId) {
        if (combat.isTagged(player.getUniqueId())) return false;
        PortalRoute route = chooseRoute(new PortalTarget(PortalTarget.Kind.MINE, mineId));
        return route != null && startFlight(player, route, true);
    }

    public ItemStack selectorItem() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.getGui(null, "portals.selector-name"));
        meta.lore(messages.getGuiList(null, "portals.selector-lore"));
        meta.getPersistentDataContainer().set(selectorKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSelector(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(selectorKey, PersistentDataType.BYTE);
    }

    public String beginPortalSelection(Player player, String rawId, PortalTarget target) {
        String id = PortalTarget.normalize(rawId);
        if (id == null || target == null || !targetExists(target)) return "invalid";
        Selection selection = Selection.portal(id, target);
        selections.put(player.getUniqueId(), selection);
        giveSelector(player);
        return "ok";
    }

    public String beginRouteSelection(Player player, PortalTarget target, String rawId, double requestedSpeed) {
        String id = PortalTarget.normalize(rawId);
        if (id == null || target == null || !targetExists(target)) return "invalid";
        Selection selection = Selection.route(id, target,
                requestedSpeed <= 0D ? defaultSpeed : requestedSpeed);
        selections.put(player.getUniqueId(), selection);
        giveSelector(player);
        return "ok";
    }

    public boolean isRouteSelecting(UUID playerId) {
        Selection selection = selections.get(playerId);
        return selection != null && selection.route;
    }

    /** Read-only selection details for standardized portal-corner feedback. */
    public String selectedPortalId(UUID playerId) {
        Selection selection = selections.get(playerId);
        return selection == null ? null : selection.id;
    }

    public PortalTarget selectedTarget(UUID playerId) {
        Selection selection = selections.get(playerId);
        return selection == null ? null : selection.target;
    }

    public Location selectedCorner(UUID playerId, boolean first) {
        Selection selection = selections.get(playerId);
        Location location = selection == null ? null : (first ? selection.first : selection.second);
        return location == null ? null : location.clone();
    }

    public String selectCorner(Player player, Location location, boolean first) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.route) return "none";
        if (selection.first != null && !selection.first.getWorld().equals(location.getWorld())) return "world";
        if (first) selection.first = location.toBlockLocation();
        else selection.second = location.toBlockLocation();
        return "ok";
    }

    public String addRoutePoint(Player player, Location location) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || !selection.route) return "none";
        if (!isTargetLocation(selection.target, location)) return "outside";
        selection.points.add(new ZoneRoute.Waypoint(location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch()));
        return "ok";
    }

    public String removeRoutePoint(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || !selection.route || selection.points.isEmpty()) return "none";
        selection.points.remove(selection.points.size() - 1);
        return "ok";
    }

    public String finishSelection(Player player) {
        Selection selection = selections.remove(player.getUniqueId());
        if (selection == null) return "none";
        if (selection.route) return saveRoute(selection);
        if (selection.first == null || selection.second == null || selection.first.getWorld() == null
                || !selection.first.getWorld().equals(selection.second.getWorld())) return "incomplete";
        EntryPortal portal = new EntryPortal(selection.id, selection.target, selection.first.getWorld().getName(),
                selection.first.getBlockX(), selection.first.getBlockY(), selection.first.getBlockZ(),
                selection.second.getBlockX(), selection.second.getBlockY(), selection.second.getBlockZ());
        try {
            storage.upsertPortal(portal);
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not persist entry portal " + portal.id(), error);
            return "persist";
        }
        portals.put(portal.id(), portal);
        return "ok";
    }

    private String saveRoute(Selection selection) {
        if (selection.points.isEmpty()) return "incomplete";
        if (!routeStaysInTarget(selection.target, selection.points)) return "outside";
        PortalRoute route = new PortalRoute(selection.id, selection.target, selection.speed, selection.points);
        try {
            storage.upsertRoute(route, encodeWaypoints(route.waypoints()));
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not persist entry route " + route.id(), error);
            return "persist";
        }
        routes.put(route.id(), route);
        return "ok";
    }

    /** Prevent a route from cutting through terrain outside its destination between two valid points. */
    private boolean routeStaysInTarget(PortalTarget target, List<ZoneRoute.Waypoint> points) {
        if (target == null || points.isEmpty()) return false;
        World expected = Bukkit.getWorld(points.getFirst().world());
        if (expected == null) return false;
        Location previous = null;
        for (ZoneRoute.Waypoint waypoint : points) {
            World world = Bukkit.getWorld(waypoint.world());
            if (world == null || !world.equals(expected)) return false;
            Location current = waypoint.location(world);
            if (!isTargetLocation(target, current)) return false;
            if (previous != null && !segmentStaysInTarget(target, previous, current)) return false;
            previous = current;
        }
        return true;
    }

    private boolean segmentStaysInTarget(PortalTarget target, Location from, Location to) {
        double distance = from.distance(to);
        int samples = Math.max(1, (int) Math.ceil(distance / .5D));
        for (int index = 1; index <= samples; index++) {
            double fraction = index / (double) samples;
            Location sample = from.clone().add((to.getX() - from.getX()) * fraction,
                    (to.getY() - from.getY()) * fraction, (to.getZ() - from.getZ()) * fraction);
            if (!isTargetLocation(target, sample)) return false;
        }
        return true;
    }

    public boolean deletePortal(String rawId) {
        String id = PortalTarget.normalize(rawId);
        EntryPortal portal = id == null ? null : portals.get(id);
        if (portal == null) return false;
        try {
            storage.deletePortal(portal.id());
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not delete entry portal " + portal.id(), error);
            return false;
        }
        portals.remove(id, portal);
        return true;
    }

    /** Deletes only when the portal belongs to the command's destination scope. */
    public boolean deletePortal(PortalTarget target, String rawId) {
        String id = PortalTarget.normalize(rawId);
        EntryPortal portal = id == null ? null : portals.get(id);
        return portal != null && portal.target().equals(target) && deletePortal(id);
    }

    public boolean deleteRoute(String rawId) {
        String id = PortalTarget.normalize(rawId);
        PortalRoute route = id == null ? null : routes.get(id);
        if (route == null) return false;
        try {
            storage.deleteRoute(route.id());
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not delete entry route " + route.id(), error);
            return false;
        }
        routes.remove(id, route);
        return true;
    }

    /** Deletes only when the route belongs to the command's destination scope. */
    public boolean deleteRoute(PortalTarget target, String rawId) {
        String id = PortalTarget.normalize(rawId);
        PortalRoute route = id == null ? null : routes.get(id);
        return route != null && route.target().equals(target) && deleteRoute(id);
    }

    /** Called on block movement. Cooldown and all validation are server-side. */
    public void tryEnter(Player player) {
        if (isFlying(player.getUniqueId())) return;
        if (portals.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (cooldowns.getOrDefault(player.getUniqueId(), 0L) > now) return;
        Location loc = player.getLocation();
        EntryPortal portal = portals.values().stream().filter(candidate -> candidate.contains(loc))
                .sorted(Comparator.comparing(EntryPortal::id)).findFirst().orElse(null);
        if (portal == null) return;
        cooldowns.put(player.getUniqueId(), now + activationCooldownMillis);
        if (!player.hasPermission("vertex.portals.use")) {
            player.sendMessage(messages.get(player, "portals.no-permission"));
            return;
        }
        if (combat.isTagged(player.getUniqueId())) {
            player.sendMessage(messages.get(player, "portals.combat"));
            return;
        }
        if (portal.target().kind() != PortalTarget.Kind.MINE) {
            ZoneType type = portal.target().kind() == PortalTarget.Kind.HAVEN ? ZoneType.HAVEN : ZoneType.RIFTLANDS;
            String admission = zones.requestPortalEntry(player, type);
            if (!"ok".equals(admission)) {
                if (admission.startsWith("cooldown:")) {
                    player.sendMessage(messages.get(player, "zones.entry-cooldown", "seconds", admission.substring("cooldown:".length())));
                } else {
                    player.sendMessage(messages.get(player, "portals.unavailable", "target", portal.target().displayName()));
                }
                return;
            }
        }
        PortalRoute route = chooseRoute(portal.target());
        if (route == null) {
            player.sendMessage(messages.get(player, "portals.no-route", "target", portal.target().displayName()));
            return;
        }
        if (!startFlight(player, route, false)) {
            player.sendMessage(messages.get(player, "portals.unavailable", "target", portal.target().displayName()));
        }
    }

    public boolean previewRoute(Player player, String rawId) {
        PortalRoute route = routes.get(PortalTarget.normalize(rawId));
        return route != null && startFlight(player, route, true);
    }

    public void releaseFlight(Player player, boolean slowFall) { releaseFlight(player.getUniqueId(), slowFall); }

    private PortalRoute chooseRoute(PortalTarget target) {
        List<PortalRoute> choices = routes(target).stream().filter(route -> !route.waypoints().isEmpty()).toList();
        // Haven/Riftlands already have a durable route system used by their
        // normal entry GUI. Use those routes for physical portals as a safe
        // fallback too, so admins do not have to create the same flight twice.
        // Mine routes remain portal-specific because they are independent
        // destinations and must never borrow a zone route.
        if (choices.isEmpty() && target.kind() != PortalTarget.Kind.MINE) {
            ZoneType type = target.kind() == PortalTarget.Kind.HAVEN ? ZoneType.HAVEN : ZoneType.RIFTLANDS;
            choices = zones.regions(type).stream()
                    .flatMap(region -> zones.routes(region).stream())
                    .filter(route -> route.enabled() && !route.waypoints().isEmpty())
                    .map(route -> new PortalRoute("zone-" + route.id(), target, route.speed(), route.waypoints()))
                    .toList();
        }
        return choices.isEmpty() ? null : choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    /**
     * No more server-scripted per-tick flight through a recorded path --
     * that meant an absolute-position teleport every tick, which resets the
     * client's movement interpolation each time and reads as constant
     * screen stutter/glitching. Instead: teleport straight above the
     * route's actual destination (its last recorded waypoint; the earlier
     * points, once just a guide path, are no longer used for anything) and
     * let normal gravity carry the player down, same as any other fall --
     * the client interpolates that exactly like every other bit of vanilla
     * movement, and free look was never an issue here since nothing but
     * position is being touched.
     */
    private boolean startFlight(Player player, PortalRoute route, boolean preview) {
        if (!preview && combat.isTagged(player.getUniqueId())) return false;
        ZoneRoute.Waypoint destination = route.waypoints().getLast();
        World world = Bukkit.getWorld(destination.world());
        if (world == null || !isTargetLocation(route.target(), destination.location(world))) return false;
        Location dropPoint = destination.location(world);
        dropPoint.setY(Math.min(world.getMaxHeight() - 8, dropPoint.getY() + 96D));
        FlightState previous = new FlightState(player.getAllowFlight(), player.isFlying(), player.getFlySpeed());
        if (!player.teleport(dropPoint)) return false;
        player.setAllowFlight(false);
        player.setFlying(false);
        me.vertex.core.util.FlightEffects.renewSlowFall(player);
        descending.put(player.getUniqueId(), previous);
        if (!preview && route.target().kind() == PortalTarget.Kind.RIFTLANDS) zones.startPortalRiftSession(player);
        if (!preview) player.sendMessage(messages.get(player, "portals.entered", "target", route.target().displayName()));
        return true;
    }

    private void releaseFlight(UUID playerId, boolean slowFall) {
        FlightState before = descending.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (before == null || player == null) return;
        player.setFlying(false);
        player.setAllowFlight(before.allowFlight());
        if (before.allowFlight() && before.flying()) player.setFlying(true);
        player.setFlySpeed(before.flySpeed());
        if (slowFall) me.vertex.core.util.FlightEffects.renewSlowFall(player);
    }

    private void tick() {
        if (descending.isEmpty()) return;
        for (UUID playerId : List.copyOf(descending.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                descending.remove(playerId);
            } else if (player.isOnGround()) {
                releaseFlight(playerId, false);
            } else {
                // A finite, renewed effect cannot become permanent after a crash.
                me.vertex.core.util.FlightEffects.renewSlowFall(player);
            }
        }
    }

    private boolean targetExists(PortalTarget target) {
        return switch (target.kind()) {
            case HAVEN -> zones.regions(ZoneType.HAVEN).stream().anyMatch(region -> !region.world().isBlank());
            case RIFTLANDS -> zones.regions(ZoneType.RIFTLANDS).stream().anyMatch(region -> !region.world().isBlank());
            case MINE -> mines.region(target.id()) != null && mines.region(target.id()).isDefined();
        };
    }

    private boolean isTargetLocation(PortalTarget target, Location location) {
        return switch (target.kind()) {
            case HAVEN -> { ZoneRegion region = zones.regionAt(location); yield region != null && region.type() == ZoneType.HAVEN; }
            case RIFTLANDS -> { ZoneRegion region = zones.regionAt(location); yield region != null && region.type() == ZoneType.RIFTLANDS; }
            case MINE -> { MineRegion region = mines.region(target.id()); yield region != null && region.contains(location); }
        };
    }


    private Optional<List<ZoneRoute.Waypoint>> decodeWaypoints(String encoded) {
        try {
            List<ZoneRoute.Waypoint> points = new ArrayList<>();
            for (String raw : encoded.split(";")) {
                if (raw.isBlank()) continue;
                String[] values = raw.split("\\|", -1);
                if (values.length != 6) return Optional.empty();
                points.add(new ZoneRoute.Waypoint(new String(Base64.getUrlDecoder().decode(values[0]), StandardCharsets.UTF_8),
                        Double.parseDouble(values[1]), Double.parseDouble(values[2]), Double.parseDouble(values[3]),
                        Float.parseFloat(values[4]), Float.parseFloat(values[5])));
            }
            return Optional.of(points);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String encodeWaypoints(List<ZoneRoute.Waypoint> points) {
        StringBuilder output = new StringBuilder();
        for (ZoneRoute.Waypoint point : points) {
            output.append(Base64.getUrlEncoder().withoutPadding().encodeToString(point.world().getBytes(StandardCharsets.UTF_8)))
                    .append('|').append(point.x()).append('|').append(point.y()).append('|').append(point.z())
                    .append('|').append(point.yaw()).append('|').append(point.pitch()).append(';');
        }
        return output.toString();
    }

    private void giveSelector(Player player) {
        me.vertex.core.storage.ItemGiver.give(player, List.of(selectorItem()));
    }

    Plugin plugin(){return plugin;}

    private static double bounded(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private record FlightState(boolean allowFlight, boolean flying, float flySpeed) { }
    private static final class Selection {
        private final String id;
        private final PortalTarget target;
        private final boolean route;
        private final double speed;
        private Location first;
        private Location second;
        private final List<ZoneRoute.Waypoint> points = new ArrayList<>();
        private Selection(String id, PortalTarget target, boolean route, double speed) { this.id = id; this.target = target; this.route = route; this.speed = bounded(speed, .05D, 8D); }
        private static Selection portal(String id, PortalTarget target) { return new Selection(id, target, false, .8D); }
        private static Selection route(String id, PortalTarget target, double speed) { return new Selection(id, target, true, speed); }
    }
}
