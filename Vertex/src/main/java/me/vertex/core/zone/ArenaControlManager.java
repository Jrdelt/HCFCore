package me.vertex.core.zone;

import me.vertex.core.booster.BoosterCategory;
import me.vertex.core.capture.CaptureEventType;
import me.vertex.core.faction.PvpTopManager;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.mine.MineKothControl;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.DeliveryManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Persistent Haven/Riftlands KOTH and Outpost points, driven by the mine KOTH state machine. */
public final class ArenaControlManager implements Listener {
    public enum SelectionResult { STARTED, INVALID_NAME }
    public enum ControlType {
        KOTH("koth", "KOTH"), OUTPOST("outpost", "Outpost");
        private final String command, display;
        ControlType(String command, String display) { this.command = command; this.display = display; }
        public String command() { return command; }
        public String display() { return display; }
    }
    private record Point(String id, String name, ZoneType zone, ControlType type, String world,
                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(Location location) { return location != null && location.getWorld() != null && world.equalsIgnoreCase(location.getWorld().getName())
                && location.getX() >= minX && location.getX() <= maxX + 1 && location.getY() >= minY && location.getY() <= maxY + 1 && location.getZ() >= minZ && location.getZ() <= maxZ + 1; }
    }
    private record State(Integer owner, double control, long ownedSince, Integer capturing) { static final State EMPTY = new State(null, 0D, 0L, null); }
    /** Mirrors the KOTH/mines region flow: two corners are selected, then a
     * sneaking air-click explicitly commits the selection. */
    private record Selection(ZoneType zone, ControlType type, String name, Location first, Location second) { }
    private record PointBonuses(double sell, double buyDiscount, double exp, double mobDrop) { }

    private final Plugin plugin;
    private final ZoneManager zones;
    private final PvpTopManager pvpTop;
    private final Messages messages;
    private final Storage storage;
    private final NamespacedKey wandKey;
    private final Map<String, Point> points = new ConcurrentHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Map<String, State> dirty = new ConcurrentHashMap<>();
    private MineKothControl.Settings settings = MineKothControl.Settings.DEFAULTS;
    private long kothHoldMillis = 10_800_000L;
    private PointBonuses kothDefaults = new PointBonuses(10D, 5D, 25D, 0D);
    private PointBonuses outpostDefaults = new PointBonuses(10D, 5D, 0D, 50D);
    private volatile YamlConfiguration controlConfig;
    private BukkitTask tickTask, persistTask;

    public ArenaControlManager(Plugin plugin, Database database, ZoneManager zones, PvpTopManager pvpTop, Messages messages) {
        this.plugin = plugin; this.zones = zones; this.pvpTop = pvpTop; this.messages = messages; this.storage = new Storage(database);
        wandKey = new NamespacedKey(plugin, "arena_control_wand");
    }
    public void load() throws SQLException {
        File file = new File(plugin.getDataFolder(), "arena-runes.yml");
        if (!file.exists()) plugin.saveResource("arena-runes.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        settings = new MineKothControl.Settings(Math.max(1D, config.getDouble("controls.capture-seconds", 180D)),
                config.getBoolean("controls.multiple-members-speed-up", true), Math.max(0D, config.getDouble("controls.additional-member-speed", .25D)),
                Math.max(1, config.getInt("controls.max-counted-members", 5)), Math.max(1D, config.getDouble("controls.max-speed-multiplier", 2D)),
                Math.max(0D, Math.min(100D, config.getDouble("controls.booster-reset-threshold", 50D))));
        kothHoldMillis = Math.max(60L, config.getLong("controls.koth-hold-seconds", 10_800L)) * 1_000L;
        kothDefaults = readBonuses(config, "controls.defaults.koth",
                new PointBonuses(config.getDouble("controls.koth-sell-percent", 10D), 5D,
                        config.getDouble("controls.koth-xp-percent", 25D), 0D));
        outpostDefaults = readBonuses(config, "controls.defaults.outpost",
                new PointBonuses(10D, 5D, 0D, config.getDouble("controls.outpost-mob-drop-percent", 50D)));
        controlConfig = config;
        storage.init(); points.clear(); states.clear();
        for (Point point : storage.loadPoints()) points.put(point.id(), point);
        states.putAll(storage.loadStates());
    }
    public void start() {
        if (tickTask == null) tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        if (persistTask == null) persistTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushDirty, 200L, 200L);
    }
    public SelectionResult beginSelection(Player player, ZoneType zone, ControlType type, String name) {
        String normalized = normalize(name); if (normalized == null) return SelectionResult.INVALID_NAME;
        selections.put(player.getUniqueId(), new Selection(zone, type, normalized, null, null));
        giveWand(player, zone, type);
        return SelectionResult.STARTED;
    }
    public void giveWand(Player player, ZoneType zone, ControlType type) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD); ItemMeta meta = wand.getItemMeta();
        meta.displayName(messages.getGui(player, "arena-controls.wand-name", "zone", zone.displayName(),
                "type", type.display()));
        meta.lore(messages.getGuiList(player, "arena-controls.wand-lore"));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, zone.name() + ":" + type.name());
        wand.setItemMeta(meta);
        if (!DeliveryManager.queueOverflow(plugin, player, List.of(wand), "arena-control-selector")) {
            player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
        }
    }
    public boolean cancelSelection(Player player) { return selections.remove(player.getUniqueId()) != null; }
    public List<String> names(ZoneType zone, ControlType type) { return points.values().stream().filter(point -> point.zone == zone && point.type == type).map(Point::name).sorted().toList(); }
    public boolean delete(ZoneType zone, ControlType type, String name) {
        Point point = points.remove(id(zone, type, name)); if (point == null) return false;
        states.remove(point.id()); dirty.remove(point.id());
        try { storage.delete(point.id()); return true; } catch (SQLException error) { points.put(point.id(), point); plugin.getLogger().log(Level.SEVERE, "Could not delete arena control point.", error); return false; }
    }
    public double bonusFor(Player player, BoosterCategory category) {
        ZoneRegion region = zones.regionAt(player.getLocation()); if (region == null) return 0D;
        int faction = FactionsHook.getFactionId(player); if (faction == FactionsHook.NO_FACTION) return 0D;
        long now = System.currentTimeMillis(); double total = 0D;
        for (Point point : points.values()) {
            if (point.zone != region.type()) continue; State state = states.getOrDefault(point.id(), State.EMPTY);
            if (state.owner == null || state.owner != faction || state.control < 100D) continue;
            if (point.type == ControlType.KOTH && now - state.ownedSince >= kothHoldMillis) continue;
            PointBonuses bonuses = bonusesFor(point);
            if (point.type == ControlType.OUTPOST && category == BoosterCategory.MOB_DROP) total += bonuses.mobDrop;
            if (category == BoosterCategory.SELL) total += bonuses.sell;
            if (category == BoosterCategory.BUY_DISCOUNT) total += bonuses.buyDiscount;
            if (point.type == ControlType.KOTH && category == BoosterCategory.EXP) total += bonuses.exp;
        }
        return total;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onSelect(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        Selection selection = selections.get(player.getUniqueId());
        ItemStack item = event.getItem();
        if (item == null) item = player.getInventory().getItemInMainHand();
        if (selection == null || item == null || !item.hasItemMeta()
                || !item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.STRING)) return;
        String expectedWand = selection.zone.name() + ":" + selection.type.name();
        String actualWand = item.getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.STRING);
        if (!expectedWand.equals(actualWand)) {
            event.setCancelled(true);
            player.sendMessage(messages.get(player, "arena-controls.no-selection"));
            return;
        }

        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> {
                event.setCancelled(true);
                Location clicked = event.getClickedBlock().getLocation();
                selections.put(player.getUniqueId(), new Selection(selection.zone, selection.type, selection.name,
                        clicked, null));
                player.sendMessage(messages.get(player, "arena-controls.first-corner",
                        "x", String.valueOf(clicked.getBlockX()), "y", String.valueOf(clicked.getBlockY()),
                        "z", String.valueOf(clicked.getBlockZ())));
            }
            case RIGHT_CLICK_BLOCK -> {
                event.setCancelled(true);
                if (selection.first == null) {
                    player.sendMessage(messages.get(player, "arena-controls.selection-incomplete"));
                    return;
                }
                Location clicked = event.getClickedBlock().getLocation();
                ZoneRegion firstRegion = zones.regionAt(selection.first);
                ZoneRegion secondRegion = zones.regionAt(clicked);
                if (!sameRegion(selection, firstRegion, secondRegion)) {
                    player.sendMessage(messages.get(player, "arena-controls.corners-not-same-region",
                            "zone", selection.zone.displayName()));
                    return;
                }
                selections.put(player.getUniqueId(), new Selection(selection.zone, selection.type, selection.name,
                        selection.first, clicked));
                player.sendMessage(messages.get(player, "arena-controls.second-corner",
                        "x", String.valueOf(clicked.getBlockX()), "y", String.valueOf(clicked.getBlockY()),
                        "z", String.valueOf(clicked.getBlockZ())));
            }
            case LEFT_CLICK_AIR, RIGHT_CLICK_AIR -> {
                if (player.isSneaking()) {
                    event.setCancelled(true);
                    completeSelection(player, selection);
                }
            }
            default -> { }
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { selections.remove(event.getPlayer().getUniqueId()); }
    @EventHandler public void onPluginDisable(PluginDisableEvent event) { if (event.getPlugin() == plugin) shutdown(); }
    public void shutdown() { if (tickTask != null) { tickTask.cancel(); tickTask = null; } if (persistTask != null) { persistTask.cancel(); persistTask = null; } selections.clear(); flushDirty(); }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Point point : points.values()) {
            State before = states.getOrDefault(point.id(), State.EMPTY);
            if (point.type == ControlType.KOTH && before.owner != null && now - before.ownedSince >= kothHoldMillis) { states.put(point.id(), State.EMPTY); dirty.put(point.id(), State.EMPTY); Bukkit.broadcast(messages.get(Bukkit.getConsoleSender(), "arena-controls.koth-reset", "name", point.name())); continue; }
            MineKothControl.Result result = MineKothControl.tick(new MineKothControl.Snapshot(before.owner, before.control, before.capturing), members(point), 1D, settings);
            long ownedSince = result.ownerChanged() ? now : before.ownedSince;
            State after = new State(result.ownerFactionId(), result.controlPercent(), ownedSince, result.capturingFactionId());
            if (!after.equals(before)) { states.put(point.id(), after); dirty.put(point.id(), after); if (result.ownerChanged()) captured(point, after, now); }
        }
    }
    private Map<Integer, Integer> members(Point point) { Map<Integer, Integer> result = new LinkedHashMap<>(); for (Player player : Bukkit.getOnlinePlayers()) if (point.contains(player.getLocation())) { int faction = FactionsHook.getFactionId(player); if (faction != FactionsHook.NO_FACTION) result.merge(faction, 1, Integer::sum); } return result; }
    private void captured(Point point, State state, long now) {
        String faction = FactionsHook.getFactionName(state.owner); Bukkit.broadcast(messages.get(Bukkit.getConsoleSender(), "arena-controls.captured", "faction", faction, "name", point.name(), "type", point.type.display()));
        if (pvpTop == null) return;
        Player actor = Bukkit.getOnlinePlayers().stream().filter(player -> point.contains(player.getLocation()) && FactionsHook.getFactionId(player) == state.owner).findFirst().orElse(null);
        if (actor != null) pvpTop.awardCapture(point.type == ControlType.KOTH ? CaptureEventType.KOTH : CaptureEventType.OUTPOST,
                state.owner, actor, "arena-" + point.id(), String.valueOf(now));
    }
    private void flushDirty() { Map<String, State> snapshot = new HashMap<>(dirty); for (Map.Entry<String, State> entry : snapshot.entrySet()) try { storage.saveState(entry.getKey(), entry.getValue()); dirty.remove(entry.getKey(), entry.getValue()); } catch (SQLException error) { plugin.getLogger().log(Level.SEVERE, "Could not persist arena control state.", error); } }
    private boolean sameRegion(Selection selection, ZoneRegion first, ZoneRegion second) {
        return first != null && second != null && first.type() == selection.zone && second.type() == selection.zone
                && first.id().equals(second.id());
    }

    private void completeSelection(Player player, Selection selection) {
        if (selection.first == null || selection.second == null) {
            player.sendMessage(messages.get(player, "arena-controls.selection-incomplete"));
            return;
        }
        ZoneRegion firstRegion = zones.regionAt(selection.first);
        ZoneRegion secondRegion = zones.regionAt(selection.second);
        if (!sameRegion(selection, firstRegion, secondRegion)) {
            player.sendMessage(messages.get(player, "arena-controls.corners-not-same-region",
                    "zone", selection.zone.displayName()));
            return;
        }
        Point point = point(selection);
        try {
            storage.savePoint(point);
            points.put(point.id(), point);
            states.put(point.id(), State.EMPTY);
            dirty.put(point.id(), State.EMPTY);
            selections.remove(player.getUniqueId());
            player.sendMessage(messages.get(player, "arena-controls.created", "type", selection.type.display(),
                    "name", point.name()));
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not save arena control point.", error);
            player.sendMessage(messages.get(player, "arena-controls.save-failed"));
        }
    }

    private Point point(Selection selection) {
        Location first = selection.first;
        Location second = selection.second;
        return new Point(id(selection.zone, selection.type, selection.name), selection.name, selection.zone,
                selection.type, first.getWorld().getName(), Math.min(first.getBlockX(), second.getBlockX()),
                Math.min(first.getBlockY(), second.getBlockY()), Math.min(first.getBlockZ(), second.getBlockZ()),
                Math.max(first.getBlockX(), second.getBlockX()), Math.max(first.getBlockY(), second.getBlockY()),
                Math.max(first.getBlockZ(), second.getBlockZ()));
    }
    private PointBonuses bonusesFor(Point point) { return readBonuses(controlConfig, "controls.point-overrides." + point.id,
            point.type == ControlType.KOTH ? kothDefaults : outpostDefaults); }
    private static PointBonuses readBonuses(YamlConfiguration config, String path, PointBonuses fallback) {
        if (config == null) return fallback;
        return new PointBonuses(Math.max(0D, config.getDouble(path + ".sell-percent", fallback.sell)),
                Math.max(0D, config.getDouble(path + ".buy-discount-percent", fallback.buyDiscount)),
                Math.max(0D, config.getDouble(path + ".xp-percent", fallback.exp)),
                Math.max(0D, config.getDouble(path + ".mob-drop-percent", fallback.mobDrop)));
    }
    private static String id(ZoneType zone, ControlType type, String name) { return zone.configKey() + "-" + type.command() + "-" + normalize(name); }
    private static String normalize(String value) { if (value == null) return null; String result = value.toLowerCase(Locale.ROOT); return result.matches("[a-z0-9][a-z0-9_-]{0,61}") ? result : null; }

    private static final class Storage {
        private final Database database; Storage(Database database) { this.database = database; }
        void init() throws SQLException { try (Connection connection = database.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate("CREATE TABLE IF NOT EXISTS zone_arena_control_points (id VARCHAR(64) PRIMARY KEY, name VARCHAR(64) NOT NULL, zone_type VARCHAR(16) NOT NULL, control_type VARCHAR(16) NOT NULL, world VARCHAR(128) NOT NULL, min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL)"); statement.executeUpdate("CREATE TABLE IF NOT EXISTS zone_arena_control_state (point_id VARCHAR(64) PRIMARY KEY, owner_faction INT, control DOUBLE NOT NULL, owned_since BIGINT NOT NULL, capturing_faction INT)"); } }
        List<Point> loadPoints() throws SQLException { List<Point> result = new ArrayList<>(); try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT id,name,zone_type,control_type,world,min_x,min_y,min_z,max_x,max_y,max_z FROM zone_arena_control_points"); ResultSet rows = statement.executeQuery()) { while (rows.next()) try { result.add(new Point(rows.getString(1), rows.getString(2), ZoneType.valueOf(rows.getString(3)), ControlType.valueOf(rows.getString(4)), rows.getString(5), rows.getInt(6), rows.getInt(7), rows.getInt(8), rows.getInt(9), rows.getInt(10), rows.getInt(11))); } catch (IllegalArgumentException ignored) { } } return result; }
        Map<String, State> loadStates() throws SQLException { Map<String, State> result = new HashMap<>(); try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT point_id,owner_faction,control,owned_since,capturing_faction FROM zone_arena_control_state"); ResultSet rows = statement.executeQuery()) { while (rows.next()) { int owner = rows.getInt(2); Integer ownerValue = rows.wasNull() ? null : owner; int capturing = rows.getInt(5); Integer capturingValue = rows.wasNull() ? null : capturing; result.put(rows.getString(1), new State(ownerValue, rows.getDouble(3), rows.getLong(4), capturingValue)); } } return result; }
        void savePoint(Point point) throws SQLException { String sql = database.dialect() == Database.Dialect.SQLITE ? "INSERT INTO zone_arena_control_points (id,name,zone_type,control_type,world,min_x,min_y,min_z,max_x,max_y,max_z) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,zone_type=excluded.zone_type,control_type=excluded.control_type,world=excluded.world,min_x=excluded.min_x,min_y=excluded.min_y,min_z=excluded.min_z,max_x=excluded.max_x,max_y=excluded.max_y,max_z=excluded.max_z" : "INSERT INTO zone_arena_control_points (id,name,zone_type,control_type,world,min_x,min_y,min_z,max_x,max_y,max_z) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),zone_type=VALUES(zone_type),control_type=VALUES(control_type),world=VALUES(world),min_x=VALUES(min_x),min_y=VALUES(min_y),min_z=VALUES(min_z),max_x=VALUES(max_x),max_y=VALUES(max_y),max_z=VALUES(max_z)"; try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(sql)) { s.setString(1, point.id); s.setString(2, point.name); s.setString(3, point.zone.name()); s.setString(4, point.type.name()); s.setString(5, point.world); s.setInt(6, point.minX); s.setInt(7, point.minY); s.setInt(8, point.minZ); s.setInt(9, point.maxX); s.setInt(10, point.maxY); s.setInt(11, point.maxZ); s.executeUpdate(); } }
        void saveState(String pointId, State state) throws SQLException { String sql = database.dialect() == Database.Dialect.SQLITE ? "INSERT INTO zone_arena_control_state (point_id,owner_faction,control,owned_since,capturing_faction) VALUES (?,?,?,?,?) ON CONFLICT(point_id) DO UPDATE SET owner_faction=excluded.owner_faction,control=excluded.control,owned_since=excluded.owned_since,capturing_faction=excluded.capturing_faction" : "INSERT INTO zone_arena_control_state (point_id,owner_faction,control,owned_since,capturing_faction) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE owner_faction=VALUES(owner_faction),control=VALUES(control),owned_since=VALUES(owned_since),capturing_faction=VALUES(capturing_faction)"; try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(sql)) { s.setString(1, pointId); if (state.owner == null) s.setNull(2, java.sql.Types.INTEGER); else s.setInt(2, state.owner); s.setDouble(3, state.control); s.setLong(4, state.ownedSince); if (state.capturing == null) s.setNull(5, java.sql.Types.INTEGER); else s.setInt(5, state.capturing); s.executeUpdate(); } }
        void delete(String id) throws SQLException { try (Connection c = database.getConnection(); PreparedStatement state = c.prepareStatement("DELETE FROM zone_arena_control_state WHERE point_id=?"); PreparedStatement point = c.prepareStatement("DELETE FROM zone_arena_control_points WHERE id=?")) { c.setAutoCommit(false); state.setString(1, id); state.executeUpdate(); point.setString(1, id); point.executeUpdate(); c.commit(); } }
    }
}
