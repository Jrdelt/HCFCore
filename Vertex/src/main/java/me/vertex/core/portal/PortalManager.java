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
import java.util.Set;
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
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> slowFallers = ConcurrentHashMap.newKeySet();
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
                    if (target != null && points.size() >= 2) {
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
        for (UUID playerId : List.copyOf(flights.keySet())) releaseFlight(playerId, false);
        selections.clear();
        cooldowns.clear();
        slowFallers.clear();
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

    public boolean isFlying(UUID playerId) { return flights.containsKey(playerId); }

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
        if (!giveSelector(player)) {
            selections.remove(player.getUniqueId(), selection);
            return "storage";
        }
        return "ok";
    }

    public String beginRouteSelection(Player player, PortalTarget target, String rawId, double requestedSpeed) {
        String id = PortalTarget.normalize(rawId);
        if (id == null || target == null || !targetExists(target)) return "invalid";
        Selection selection = Selection.route(id, target,
                requestedSpeed <= 0D ? defaultSpeed : requestedSpeed);
        selections.put(player.getUniqueId(), selection);
        if (!giveSelector(player)) {
            selections.remove(player.getUniqueId(), selection);
            return "storage";
        }
        return "ok";
    }

    public boolean isRouteSelecting(UUID playerId) {
        Selection selection = selections.get(playerId);
        return selection != null && selection.route;
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
        if (selection.points.size() < 2) return "incomplete";
        for (ZoneRoute.Waypoint point : selection.points) {
            World world = Bukkit.getWorld(point.world());
            if (world == null || !isTargetLocation(selection.target, point.location(world))) return "outside";
        }
        for (int index = 1; index < selection.points.size(); index++) {
            if (!segmentInsideTarget(selection.target, selection.points.get(index - 1), selection.points.get(index))) return "outside";
        }
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

    /** Called on block movement. Cooldown and all validation are server-side. */
    public void tryEnter(Player player) {
        if (isFlying(player.getUniqueId())) return;
        EntryPortal portal = portals.values().stream().filter(candidate -> candidate.contains(player.getLocation()))
                .sorted(Comparator.comparing(EntryPortal::id)).findFirst().orElse(null);
        if (portal == null) return;
        long now = System.currentTimeMillis();
        if (cooldowns.getOrDefault(player.getUniqueId(), 0L) > now) return;
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
        List<PortalRoute> choices = routes(target).stream().filter(route -> route.waypoints().size() >= 2).toList();
        // Haven/Riftlands already have a durable route system used by their
        // normal entry GUI. Use those routes for physical portals as a safe
        // fallback too, so admins do not have to create the same flight twice.
        // Mine routes remain portal-specific because they are independent
        // destinations and must never borrow a zone route.
        if (choices.isEmpty() && target.kind() != PortalTarget.Kind.MINE) {
            ZoneType type = target.kind() == PortalTarget.Kind.HAVEN ? ZoneType.HAVEN : ZoneType.RIFTLANDS;
            choices = zones.regions(type).stream()
                    .flatMap(region -> zones.routes(region).stream())
                    .filter(route -> route.enabled() && route.waypoints().size() >= 2)
                    .map(route -> new PortalRoute("zone-" + route.id(), target, route.speed(), route.waypoints()))
                    .toList();
        }
        return choices.isEmpty() ? null : choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
    }

    private boolean startFlight(Player player, PortalRoute route, boolean preview) {
        if (!preview && combat.isTagged(player.getUniqueId())) return false;
        ZoneRoute.Waypoint first = route.waypoints().getFirst();
        World world = Bukkit.getWorld(first.world());
        if (world == null || !isTargetLocation(route.target(), first.location(world))) return false;
        FlightState previous = new FlightState(player.getAllowFlight(), player.isFlying(), player.getFlySpeed());
        if (!player.teleport(first.location(world))) return false;
        player.setAllowFlight(true);
        player.setFlying(true);
        flights.put(player.getUniqueId(), new Flight(player.getUniqueId(), route, 0, 0D, previous));
        if (!preview && route.target().kind() == PortalTarget.Kind.RIFTLANDS) zones.startPortalRiftSession(player);
        if (!preview) player.sendMessage(messages.get(player, "portals.entered", "target", route.target().displayName()));
        return true;
    }

    private void releaseFlight(UUID playerId, boolean slowFall) {
        Flight flight = flights.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (flight == null || player == null) return;
        player.setFlying(false);
        player.setAllowFlight(flight.before.allowFlight());
        if (flight.before.allowFlight() && flight.before.flying()) player.setFlying(true);
        player.setFlySpeed(flight.before.flySpeed());
        if (slowFall) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, Integer.MAX_VALUE, 0, false, false, false));
            slowFallers.add(playerId);
        }
    }

    private void tick() {
        for (Flight flight : List.copyOf(flights.values())) tickFlight(flight);
        for (UUID playerId : List.copyOf(slowFallers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || player.isOnGround()) {
                if (player != null) player.removePotionEffect(PotionEffectType.SLOW_FALLING);
                slowFallers.remove(playerId);
            }
        }
    }

    private void tickFlight(Flight flight) {
        Player player = Bukkit.getPlayer(flight.playerId());
        if (player == null) { flights.remove(flight.playerId()); return; }
        List<ZoneRoute.Waypoint> points = flight.route().waypoints();
        if (flight.index() >= points.size() - 1) { releaseFlight(player.getUniqueId(), true); return; }
        ZoneRoute.Waypoint from = points.get(flight.index());
        ZoneRoute.Waypoint to = points.get(flight.index() + 1);
        World world = Bukkit.getWorld(from.world());
        if (world == null || !from.world().equals(to.world())) { releaseFlight(player.getUniqueId(), true); return; }
        double distance = Math.max(.001D, Math.sqrt(Math.pow(to.x() - from.x(), 2D) + Math.pow(to.y() - from.y(), 2D) + Math.pow(to.z() - from.z(), 2D)));
        double progress = flight.progress() + flight.route().speed() / 20D / distance;
        int index = flight.index();
        while (progress >= 1D && index < points.size() - 1) {
            progress -= 1D;
            index++;
            if (index >= points.size() - 1) break;
            from = points.get(index);
            to = points.get(index + 1);
        }
        if (index >= points.size() - 1) { releaseFlight(player.getUniqueId(), true); return; }
        Location next = new Location(world, from.x() + (to.x() - from.x()) * progress,
                from.y() + (to.y() - from.y()) * progress, from.z() + (to.z() - from.z()) * progress,
                to.yaw(), to.pitch());
        if (!isTargetLocation(flight.route().target(), next) || !player.teleport(next)) {
            releaseFlight(player.getUniqueId(), true);
            return;
        }
        flights.put(player.getUniqueId(), new Flight(player.getUniqueId(), flight.route(), index, progress, flight.before()));
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

    private boolean segmentInsideTarget(PortalTarget target, ZoneRoute.Waypoint one, ZoneRoute.Waypoint two) {
        if (!one.world().equals(two.world())) return false;
        World world = Bukkit.getWorld(one.world());
        if (world == null) return false;
        double distance = Math.sqrt(Math.pow(two.x() - one.x(), 2D) + Math.pow(two.y() - one.y(), 2D) + Math.pow(two.z() - one.z(), 2D));
        int samples = Math.max(1, (int) Math.ceil(distance / .5D));
        for (int sample = 0; sample <= samples; sample++) {
            double progress = sample / (double) samples;
            if (!isTargetLocation(target, new Location(world, one.x() + (two.x() - one.x()) * progress,
                    one.y() + (two.y() - one.y()) * progress, one.z() + (two.z() - one.z()) * progress))) return false;
        }
        return true;
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

    private boolean giveSelector(Player player) {
        return me.vertex.core.storage.DeliveryManager.queueOverflow(
                plugin, player, List.of(selectorItem()), "portal-selector");
    }

    Plugin plugin(){return plugin;}

    private static double bounded(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
    private record FlightState(boolean allowFlight, boolean flying, float flySpeed) { }
    private record Flight(UUID playerId, PortalRoute route, int index, double progress, FlightState before) { }
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
