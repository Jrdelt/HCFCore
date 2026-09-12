package me.vertex.core.zone;

import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.booster.BoosterService;
import me.vertex.core.item.ItemKind;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.storage.Database;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Owns Haven/Riftlands state and gameplay. World/entity work stays on the
 * primary thread; SQL uses compact, idempotent records so restart recovery
 * has one source of truth for progression, routes, event standings, and
 * active Riftlands sessions.
 */
public final class ZoneManager {
    private static final DecimalFormat PERCENT = new DecimalFormat("0.##");
    private static final double RARE_DROP_MESSAGE_THRESHOLD_PERCENT = 2.5D;
    private final Plugin plugin;
    private final ZoneStorage storage;
    private final Messages messages;
    private final CombatManager combat;
    private final BackpackManager backpacks;
    private final TrackedItemIds trackedItems;
    private final NamespacedKey selectorKey, zoneMobKey, mobRegionKey, mobDefinitionKey, mobProfileKey, ticketKey, chanceKey,
            lootEditorOriginalKey, sessionKey;
    private final Map<String, ZoneRegion> regions = new ConcurrentHashMap<>();
    private final Map<String, ZoneRoute> routes = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<UUID, ZoneStorage.FlightReturn> pendingFlightReturns = new ConcurrentHashMap<>();
    private final Set<UUID> failedPlayerLoads = ConcurrentHashMap.newKeySet();
    private final Set<UUID> playerLoads = ConcurrentHashMap.newKeySet();
    private final Set<UUID> scheduledPlayerLoadRetries = ConcurrentHashMap.newKeySet();
    private final Map<ZoneType, List<LootEntry>> loot = new EnumMap<>(ZoneType.class);
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, Countdown> entries = new ConcurrentHashMap<>();
    private final Map<UUID, Countdown> exits = new ConcurrentHashMap<>();
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private final Set<UUID> slowFalling = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spawnDispatchBypass = ConcurrentHashMap.newKeySet();
    private final Set<String> peacefulWorldWarnings = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Score> scores = new ConcurrentHashMap<>();
    /** Region -> only entity UUIDs spawned by Vertex, avoiding world-wide cleanup scans every tick. */
    private final Map<String, Set<UUID>> zoneMobsByRegion = new ConcurrentHashMap<>();
    private final Map<ZoneType, ZoneConfig> configs = new EnumMap<>(ZoneType.class);
    private final Map<ZoneType, List<String>> blockedCommands = new EnumMap<>(ZoneType.class);
    private final Map<ZoneType, Double> mobDespawnRadii = new EnumMap<>(ZoneType.class);
    private final Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private final Map<String, PendingWrite> dirtyWrites = new ConcurrentHashMap<>();
    private final AtomicLong writeVersion = new AtomicLong();
    private final Object writeLock = new Object();
    private CompletableFuture<Void> writeChain = CompletableFuture.completedFuture(null);
    private final BossBar eventBar = Bukkit.createBossBar("Mob Kill Event", BarColor.PURPLE, BarStyle.SOLID);
    private volatile BoosterService boosters;
    private volatile long cycleAnchor;
    private volatile long loadedScoreEvent = Long.MIN_VALUE;
    private volatile long lastFinishedEvent = Long.MIN_VALUE;
    private volatile long lastAnnouncedEvent = Long.MIN_VALUE;
    private volatile long lastBossRefresh;
    private BukkitTask tickTask;

    public ZoneManager(Plugin plugin, Database database, Messages messages, CombatManager combat,
            BackpackManager backpacks, TrackedItemIds trackedItems) {
        this.plugin = plugin;
        this.storage = new ZoneStorage(database);
        this.messages = messages;
        this.combat = combat;
        this.backpacks = backpacks;
        this.trackedItems = trackedItems;
        selectorKey = new NamespacedKey(plugin, "zone_selector");
        zoneMobKey = new NamespacedKey(plugin, "zone_mob");
        mobRegionKey = new NamespacedKey(plugin, "zone_mob_region");
        mobDefinitionKey = new NamespacedKey(plugin, "zone_mob_definition");
        mobProfileKey = new NamespacedKey(plugin, "zone_mob_profile");
        ticketKey = new NamespacedKey(plugin, "riftlands_ticket");
        chanceKey = new NamespacedKey(plugin, "zone_loot_chance");
        lootEditorOriginalKey = new NamespacedKey(plugin, "zone_loot_editor_original");
        sessionKey = new NamespacedKey(plugin, "riftlands_session");
        for (ZoneType type : ZoneType.values()) loot.put(type, new ArrayList<>());
    }

    public ZoneStorage storage() { return storage; }
    public Component message(Player player, String key, String... placeholders) { return messages.get(player, key, placeholders); }
    public void setBoosterService(BoosterService boosters) { this.boosters = boosters; }
    public NamespacedKey sessionKey() { return sessionKey; }
    public boolean isZoneMob(Entity entity) { return entity != null && entity.getPersistentDataContainer().has(zoneMobKey, PersistentDataType.STRING); }
    public boolean isTicket(ItemStack item) { return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(ticketKey, PersistentDataType.BYTE); }
    public boolean queueOverflow(Player player,java.util.Collection<ItemStack> items,String source){return me.vertex.core.storage.DeliveryManager.queueOverflow(plugin,player,items,source);}

    public void initStorage() throws SQLException { storage.init(); }

    public void load() {
        for (ZoneType type : ZoneType.values()) {
            configs.put(type, readConfig(type));
            blockedCommands.put(type, readBlockedCommands(type));
            mobDespawnRadii.put(type, readMobDespawnRadius(type));
        }
    }

    public List<String> blockedCommands(ZoneType type) {
        return List.copyOf(blockedCommands.getOrDefault(type, List.of()));
    }

    private List<String> readBlockedCommands(ZoneType type) {
        File file = new File(plugin.getDataFolder(), type.configKey() + ".yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return yaml.getStringList("commands.blocked-in-zone").stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .toList();
    }

    private double readMobDespawnRadius(ZoneType type) {
        File file = new File(plugin.getDataFolder(), type.configKey() + ".yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        return Math.max(1D, yaml.getDouble("mob-spawning.despawn-radius", 40D));
    }

    /** Called once after storage init; all definitions are loaded before commands become usable. */
    public void loadState() {
        try {
            regions.clear(); for (ZoneRegion region : storage.loadRegions()) regions.put(region.id(), region);
            routes.clear(); for (ZoneStorage.RouteRow row : storage.loadRoutes()) decodeRoute(row).ifPresent(route -> routes.put(route.id(), route));
            for (ZoneType type : ZoneType.values()) {
                List<LootEntry> loaded = new ArrayList<>();
                for (ZoneStorage.LootRow row : storage.loadLoot(type)) {
                    try { loaded.add(new LootEntry(ItemStack.deserializeItemsFromBytes(row.item())[0], row.chance())); }
                    catch (Exception ex) { plugin.getLogger().warning("Ignoring corrupt " + type.configKey() + " loot entry " + row.position() + "."); }
                }
                if (loaded.isEmpty() && config(type).ticketInLoot()) {
                    ItemStack ticket = createTicket();
                    loaded.add(new LootEntry(ticket, config(type).ticketChance()));
                    storage.replaceLoot(type, lootRows(loaded));
                }
                loot.put(type, loaded);
            }
            long cycle = Math.max(60_000L, config(ZoneType.HAVEN).eventCycleMillis());
            cycleAnchor = storage.loadLong("cycle_anchor", System.currentTimeMillis() - Math.floorMod(System.currentTimeMillis(), cycle));
            storage.saveLong("cycle_anchor", cycleAnchor);
            restoreCurrentScores();
        } catch (Exception e) {
            throw new IllegalStateException("Could not load Haven/Riftlands state", e);
        }
    }

    public void start() {
        shutdown();
        zoneMobsByRegion.clear();
        for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                trackLoadedZoneMobs(java.util.Arrays.asList(chunk.getEntities()));
            }
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        // Plugin reloads do not fire a new pre-login event for players who are
        // already online, so warm those states without touching JDBC here.
        for (Player player : Bukkit.getOnlinePlayers()) preloadPlayerAsync(player);
    }
    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        tickTask = null;
        for (Player player : Bukkit.getOnlinePlayers()) eventBar.removePlayer(player);
        for (Flight flight : List.copyOf(flights.values())) releaseFlight(flight.playerId(), false);
        entries.clear(); exits.clear(); selections.clear(); slowFalling.clear();
        awaitWrites();
    }

    /** Runs from AsyncPlayerPreLoginEvent, never from the primary thread. */
    public void preloadPlayer(UUID uuid, String name) {
        if (!playerLoads.add(uuid)) return;
        try {
            CompletableFuture<Void> preceding;
            synchronized (writeLock) { preceding = writeChain; }
            try { preceding.get(5, TimeUnit.SECONDS); }
            catch (Exception error) { plugin.getLogger().log(Level.WARNING,
                    "Timed out waiting for zone writes before loading " + uuid, error); }
            ZoneStorage.PlayerRow row = storage.loadPlayer(uuid);
            PlayerState state = row == null ? new PlayerState(uuid, name) : PlayerState.from(row);
            state.name = name;
            state.loaded = true;
            state.persistenceHealthy = true;
            players.put(uuid, state);
            ZoneStorage.FlightReturn flightReturn = storage.loadFlightReturn(uuid);
            if (flightReturn == null) pendingFlightReturns.remove(uuid);
            else pendingFlightReturns.put(uuid, flightReturn);
            failedPlayerLoads.remove(uuid);
        } catch (Exception error) {
            failedPlayerLoads.add(uuid);
            plugin.getLogger().log(Level.SEVERE, "Could not load zone state for " + uuid
                    + "; zone progression will remain disabled for that player.", error);
        } finally {
            playerLoads.remove(uuid);
        }
    }

    private void preloadPlayerAsync(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        CompletableFuture<Void> load = CompletableFuture.runAsync(() -> preloadPlayer(uuid, name));
        pendingWrites.add(load);
        load.whenComplete((ignored, error) -> {
            pendingWrites.remove(load);
            Bukkit.getScheduler().runTask(plugin, () -> completePlayerJoin(player));
        });
    }

    /** Applies Bukkit-side recovery after the asynchronous state load. */
    public void completePlayerJoin(Player player) {
        if (!player.isOnline()) return;
        PlayerState state = players.get(player.getUniqueId());
        if (state == null || !state.loaded || failedPlayerLoads.contains(player.getUniqueId())) {
            UUID uuid = player.getUniqueId();
            if (scheduledPlayerLoadRetries.add(uuid)) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                scheduledPlayerLoadRetries.remove(uuid);
                if (player.isOnline()) preloadPlayerAsync(player);
            }, 100L);
            return;
        }
        ZoneStorage.FlightReturn row = pendingFlightReturns.get(player.getUniqueId());
        if (row == null) return;
        World world = Bukkit.getWorld(row.world());
        ZoneRegion region = region(row.region());
        if (world == null || region == null) return;
        Location safe = findSafeGround(region, new Location(world, row.x(), row.y(), row.z()));
        if (safe != null && player.teleport(safe)) {
            pendingFlightReturns.remove(player.getUniqueId(), row);
            persist("flight-return:" + player.getUniqueId(),
                    () -> storage.deleteFlightReturn(player.getUniqueId()));
        }
    }

    public ZoneRegion regionAt(Location location) {
        if (location == null) return null;
        return regions.values().stream().filter(region -> region.contains(location))
                .sorted(Comparator.comparing(ZoneRegion::id)).findFirst().orElse(null);
    }
    public ZoneRegion region(String id) { return id == null ? null : regions.get(ZoneRegion.normalizeId(id)); }
    public Collection<ZoneRegion> regions(ZoneType type) { return regions.values().stream().filter(r -> r.type() == type).sorted(Comparator.comparing(ZoneRegion::id)).toList(); }
    public Collection<ZoneRoute> routes(ZoneRegion region) { return routes.values().stream().filter(r -> r.regionId().equals(region.id())).sorted(Comparator.comparing(ZoneRoute::id)).toList(); }
    public Collection<ZoneRoute> allRoutes() { return routes.values().stream().sorted(Comparator.comparing(ZoneRoute::id)).toList(); }
    /** Current players physically inside any configured region of this zone type. */
    public int playerCount(ZoneType type) {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) if (isIn(player, type)) count++;
        return count;
    }
    /** Current loaded Vertex mobs tracked inside this zone type; stale entity IDs are cleaned while counting. */
    public int mobCount(ZoneType type) {
        int count = 0;
        for (ZoneRegion region : regions(type)) {
            Set<UUID> ids = zoneMobsByRegion.get(region.id());
            if (ids == null) continue;
            for (UUID id : List.copyOf(ids)) {
                Entity entity = Bukkit.getEntity(id);
                if (entity == null || !entity.isValid() || !isZoneMob(entity)) ids.remove(id);
                else if (region.contains(entity.getLocation())) count++;
            }
            if (ids.isEmpty()) zoneMobsByRegion.remove(region.id(), ids);
        }
        return count;
    }

    /** Removes only Vertex-tagged mobs belonging to the requested zone type. */
    public int clearZoneMobs(ZoneType type) {
        int removed = 0;
        for (ZoneRegion region : regions(type)) {
            Set<UUID> ids = zoneMobsByRegion.remove(region.id());
            if (ids == null) continue;
            for (UUID id : ids) {
                Entity entity = Bukkit.getEntity(id);
                if (entity != null && isZoneMob(entity)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public int clearAllZoneMobs() {
        return clearZoneMobs(ZoneType.HAVEN) + clearZoneMobs(ZoneType.RIFTLANDS);
    }

    /** Re-indexes persisted zone mobs when their chunks load or the plugin starts. */
    public void trackLoadedZoneMobs(Collection<? extends Entity> entities) {
        for (Entity entity : entities) {
            if (!isZoneMob(entity)) continue;
            String regionId = entity.getPersistentDataContainer().get(mobRegionKey, PersistentDataType.STRING);
            String normalized = ZoneRegion.normalizeId(regionId);
            ZoneRegion region = normalized == null ? null : regions.get(normalized);
            if (region != null) zoneMobsByRegion.computeIfAbsent(region.id(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(entity.getUniqueId());
        }
    }
    /** Removes unloaded entity handles; an EntitiesLoadEvent adds valid mobs back later. */
    public void untrackUnloadedZoneMobs(Collection<? extends Entity> entities) {
        for (Entity entity : entities) {
            if (!isZoneMob(entity)) continue;
            String regionId = entity.getPersistentDataContainer().get(mobRegionKey, PersistentDataType.STRING);
            String normalized = ZoneRegion.normalizeId(regionId);
            Set<UUID> ids = normalized == null ? null : zoneMobsByRegion.get(normalized);
            if (ids != null) ids.remove(entity.getUniqueId());
        }
    }
    public boolean isIn(Player player, ZoneType type) { ZoneRegion region = regionAt(player.getLocation()); return region != null && region.type() == type; }
    public boolean isInAnyZone(Location location) { return regionAt(location) != null; }
    public boolean isFlying(UUID uuid) { return flights.containsKey(uuid); }
    public boolean isEntering(UUID uuid) { return entries.containsKey(uuid); }

    // ---- admin selection -------------------------------------------------
    public ItemStack selectorItem() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages.getGui(null, "zones.selector-name"));
        meta.lore(messages.getGuiList(null, "zones.selector-lore"));
        meta.getPersistentDataContainer().set(selectorKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta); return item;
    }
    public boolean isSelector(ItemStack item) { return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(selectorKey, PersistentDataType.BYTE); }
    public boolean isRouteSelecting(UUID uuid) { Selection selection = selections.get(uuid); return selection != null && selection.routeId != null; }
    /** One-shot guard used when this module dispatches the server's real /spawn command after a successful channel. */
    public boolean consumeSpawnDispatchBypass(UUID uuid) { return spawnDispatchBypass.remove(uuid); }
    /** True while the internally dispatched /spawn command is waiting to run. */
    public boolean hasSpawnDispatchBypass(UUID uuid) { return spawnDispatchBypass.contains(uuid); }
    public String beginRegionSelection(Player player, ZoneType type, String id) {
        String normalized = ZoneRegion.normalizeId(id);
        if (normalized == null) return "invalid";
        Selection selection = Selection.region(type, normalized);
        selections.put(player.getUniqueId(), selection);
        if (!giveSelector(player)) {
            selections.remove(player.getUniqueId(), selection);
            return "storage";
        }
        return "ok";
    }
    public String beginRouteSelection(Player player, ZoneType type, String regionId, String routeId) {
        ZoneRegion region = region(regionId);
        if (region == null || region.type() != type) return "missing-region";
        String normalized = ZoneRegion.normalizeId(routeId);
        if (normalized == null) return "invalid";
        Selection selection = Selection.route(type, region.id(), normalized);
        selections.put(player.getUniqueId(), selection);
        if (!giveSelector(player)) {
            selections.remove(player.getUniqueId(), selection);
            return "storage";
        }
        return "ok";
    }
    private boolean giveSelector(Player player) {
        return me.vertex.core.storage.DeliveryManager.queueOverflow(plugin, player,
                List.of(selectorItem()), "zone-selector");
    }
    public String selectCorner(Player player, Location location, boolean first) {
        Selection selection = selections.get(player.getUniqueId()); if (selection == null) return "none";
        if (!sameWorld(selection, location)) { selection.clear(); return "world"; }
        if (first) selection.first = location.toBlockLocation(); else selection.second = location.toBlockLocation();
        return "ok";
    }
    public String addRoutePoint(Player player, Location location) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.routeId == null) return "none";
        ZoneRegion region = region(selection.regionId);
        if (region == null || !region.contains(location)) return "outside";
        selection.points.add(new ZoneRoute.Waypoint(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
        return "ok";
    }
    public String removeRoutePoint(Player player) { Selection s=selections.get(player.getUniqueId()); if(s==null||s.routeId==null||s.points.isEmpty())return "none"; s.points.removeLast(); return "ok"; }
    public String finishSelection(Player player) {
        Selection s=selections.remove(player.getUniqueId()); if(s==null) return "none";
        if (s.routeId != null) return saveRoute(s);
        if (s.first == null || s.second == null || s.first.getWorld()==null || !s.first.getWorld().equals(s.second.getWorld())) return "incomplete";
        ZoneRegion candidate=new ZoneRegion(s.regionId,s.type,s.first.getWorld().getName(),s.first.getBlockX(),s.first.getBlockY(),s.first.getBlockZ(),s.second.getBlockX(),s.second.getBlockY(),s.second.getBlockZ());
        ZoneRegion conflict=regions.values().stream().filter(r->!r.id().equals(candidate.id())&&r.overlaps(candidate)).findFirst().orElse(null);
        if(conflict!=null)return "overlap:"+conflict.id();
        try {
            storage.upsertRegion(candidate);
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not persist zone region " + candidate.id(), error);
            return "persist";
        }
        regions.put(candidate.id(),candidate); return "ok";
    }
    private boolean sameWorld(Selection s, Location location) { return s.first==null || (location.getWorld()!=null&&s.first.getWorld()!=null&&s.first.getWorld().equals(location.getWorld())); }
    private String saveRoute(Selection s) {
        ZoneRegion region=region(s.regionId); if(region==null||s.points.isEmpty())return "incomplete";
        for(ZoneRoute.Waypoint point:s.points) { World world=Bukkit.getWorld(point.world()); if(world==null||!region.contains(point.location(world)))return "outside"; }
        ZoneRoute route=new ZoneRoute(s.routeId,region.id(),true,config(region.type()).routeSpeed(),true,s.points);
        try {
            storage.upsertRoute(route, encodeRoute(route));
        } catch (SQLException error) {
            plugin.getLogger().log(Level.SEVERE, "Could not persist zone route " + route.id(), error);
            return "persist";
        }
        routes.put(route.id(),route); return "ok";
    }
    public boolean deleteRegion(String id) { String normalized=ZoneRegion.normalizeId(id);ZoneRegion removed=regions.get(normalized);if(removed==null)return false;try{storage.deleteRegion(removed.id());}catch(SQLException error){plugin.getLogger().log(Level.SEVERE,"Could not delete zone region "+removed.id(),error);return false;}regions.remove(normalized,removed);routes.values().removeIf(route->route.regionId().equals(removed.id()));return true; }
    public boolean deleteRoute(String id) { String normalized=ZoneRegion.normalizeId(id);ZoneRoute removed=routes.get(normalized);if(removed==null)return false;try{storage.deleteRoute(removed.id());}catch(SQLException error){plugin.getLogger().log(Level.SEVERE,"Could not delete zone route "+removed.id(),error);return false;}routes.remove(normalized,removed);return true; }
    /** Staff preview uses the same server-authoritative path as a real entry, but does not create a Riftlands session. */
    public boolean previewRoute(Player player, String id) {
        ZoneRoute route = routes.get(ZoneRegion.normalizeId(id)); if (route == null || route.waypoints().isEmpty()) return false;
        ZoneRegion region = region(route.regionId()); World world = region == null ? null : Bukkit.getWorld(region.world());
        if (world == null) return false;
        ZoneRoute.Waypoint first = route.waypoints().get(ThreadLocalRandom.current().nextInt(route.waypoints().size()));
        if (!player.teleport(first.location(world))) return false;
        FlightState before = new FlightState(player.getAllowFlight(), player.isFlying(), player.getFlySpeed());
        player.setAllowFlight(false); player.setFlying(false);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,Integer.MAX_VALUE,0,false,false,false));
        slowFalling.add(player.getUniqueId());
        flights.put(player.getUniqueId(), new Flight(player.getUniqueId(), region.type(), route.regionId(), before));
        return true;
    }

    // ---- entry, exits, guided flight ------------------------------------
    public String requestEntry(Player player, ZoneType type) {
        if (!hasEntryRoute(type)) return "unconfigured";
        return requestEntryChecks(player, type);
    }
    /**
     * Validates a physical portal entry without requiring a route from the
     * zone-entry GUI. PortalManager validates and selects its own route after
     * this check, so a dedicated /portal route is sufficient by itself.
     */
    public String requestPortalEntry(Player player, ZoneType type) {
        return requestEntryChecks(player, type);
    }
    private String requestEntryChecks(Player player, ZoneType type) {
        if (combat.isTagged(player.getUniqueId())) return "combat";
        PlayerState state=state(player);
        if (!state.operational()) return "loading";
        long remaining=state.cooldown(type)-System.currentTimeMillis();
        if(remaining>0)return "cooldown:"+Math.ceil(remaining/1000D);
        return "ok";
    }
    private boolean hasEntryRoute(ZoneType type) {
        return regions(type).stream().flatMap(region -> routes(region).stream())
                .anyMatch(route -> route.enabled() && !route.waypoints().isEmpty());
    }
    public String beginEntry(Player player, ZoneType type) {
        String allowed=requestEntry(player,type); if(!"ok".equals(allowed))return allowed;
        entries.put(player.getUniqueId(),new Countdown(type,player.getLocation().clone(),System.currentTimeMillis()+config(type).countdownSeconds()*1000L));
        return "ok";
    }
    public void cancelEntry(Player player,String reason) { if(entries.remove(player.getUniqueId())!=null)player.sendMessage(messages.get(player,"zones.entry-cancelled","reason",reason)); }
    public String beginExit(Player player) {
        ZoneRegion region=regionAt(player.getLocation()); if(region==null)return "not-zone"; if(combat.isTagged(player.getUniqueId()))return "combat";
        exits.put(player.getUniqueId(),new Countdown(region.type(),player.getLocation().clone(),System.currentTimeMillis()+config(region.type()).exitSeconds()*1000L)); return "ok";
    }
    public void cancelExit(Player player,String reason){if(exits.remove(player.getUniqueId())!=null)player.sendMessage(messages.get(player,"zones.spawn-cancelled","reason",reason));}
    public boolean startFlight(Player player, ZoneType type) {
        if(combat.isTagged(player.getUniqueId()))return false;
        ZoneRoute route=chooseRoute(player,type); if(route==null)return false;
        if(type==ZoneType.RIFTLANDS){PlayerState state=state(player); if(!state.operational())return false;if(state.sessionId==null){state.sessionId=UUID.randomUUID().toString();persistPlayer(state);}}
        ZoneRoute.Waypoint spawn=route.waypoints().get(ThreadLocalRandom.current().nextInt(route.waypoints().size()));
        World world=Bukkit.getWorld(spawn.world()); ZoneRegion spawnRegion=region(route.regionId());
        if(world==null||spawnRegion==null||!spawnRegion.contains(spawn.location(world)))return false;
        FlightState before=new FlightState(player.getAllowFlight(),player.isFlying(),player.getFlySpeed());
        if(!player.teleport(spawn.location(world)))return false;
        player.setAllowFlight(false); player.setFlying(false);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,Integer.MAX_VALUE,0,false,false,false));
        slowFalling.add(player.getUniqueId());
        flights.put(player.getUniqueId(),new Flight(player.getUniqueId(),type,route.regionId(),before));
        player.sendMessage(messages.get(player,"zones.entered","zone",type.displayName())); return true;
    }
    /** Starts a Riftlands loot ledger after a separately validated portal arrival. */
    public void startPortalRiftSession(Player player) {
        PlayerState state = state(player);
        if (!state.operational()) {
            player.sendMessage(messages.get(player, "zones.state-loading"));
            return;
        }
        if (state.sessionId == null) {
            state.sessionId = UUID.randomUUID().toString();
            persistPlayer(state);
        }
    }
    private ZoneRoute chooseRoute(Player player, ZoneType type) {
        List<ZoneRoute> choices=regions(type).stream().flatMap(region->routes(region).stream()).filter(ZoneRoute::enabled).filter(route->!route.waypoints().isEmpty()).toList();
        if(choices.isEmpty())return null;
        if(type==ZoneType.HAVEN)return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        return choices.stream().max(Comparator.comparingDouble(route -> hostileDistance(route.waypoints().getFirst(),player))).orElse(choices.getFirst());
    }
    private double hostileDistance(ZoneRoute.Waypoint waypoint,Player player){World world=Bukkit.getWorld(waypoint.world());if(world==null)return 0D;double nearest=Double.MAX_VALUE;for(Player other:world.getPlayers())if(!other.equals(player)&&isHostile(player,other))nearest=Math.min(nearest,other.getLocation().distanceSquared(waypoint.location(world)));return nearest==Double.MAX_VALUE?Double.MAX_VALUE:nearest;}
    public void releaseFlight(UUID uuid, boolean slowFall) {
        Flight flight=flights.remove(uuid); Player player=Bukkit.getPlayer(uuid); if(flight==null||player==null)return;
        player.setFlying(false); player.setAllowFlight(flight.before.allowFlight());
        if (flight.before.allowFlight() && flight.before.flying()) player.setFlying(true);
        player.setFlySpeed(flight.before.flySpeed());
        if(slowFall){player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,Integer.MAX_VALUE,0,false,false,false));slowFalling.add(uuid);}
    }
    public void releaseFlightFromHit(Player player){if(flights.containsKey(player.getUniqueId()))releaseFlight(player.getUniqueId(),true);}
    public void recordFlightDisconnect(Player player) { Flight flight=flights.get(player.getUniqueId());if(flight==null||combat.isTagged(player.getUniqueId()))return;ZoneRegion region=region(flight.regionId());if(region==null)return;Location current=player.getLocation().clone();Location safe=findSafeGround(region,current);Location stored=safe==null?current:safe;releaseFlight(player.getUniqueId(),false);ZoneStorage.FlightReturn row=new ZoneStorage.FlightReturn(region.id(),stored.getWorld().getName(),stored.getX(),stored.getY(),stored.getZ());pendingFlightReturns.put(player.getUniqueId(),row);persist("flight-return:"+player.getUniqueId(),()->storage.saveFlightReturn(player.getUniqueId(),region.id(),stored.getWorld().getName(),stored.getX(),stored.getY(),stored.getZ())); }
    /** Compatibility alias retained for older listeners; performs no JDBC. */
    public void returnAfterFlightDisconnect(Player player) { completePlayerJoin(player); }

    // ---- mobs, loot, progression, sessions ------------------------------
    public void handleZoneDeath(LivingEntity entity, Player killer, List<ItemStack> vanillaDrops) {
        String raw=entity.getPersistentDataContainer().get(zoneMobKey,PersistentDataType.STRING); if(raw==null)return;
        String mobRegion = entity.getPersistentDataContainer().get(mobRegionKey, PersistentDataType.STRING);
        if (mobRegion != null) { Set<UUID> ids = zoneMobsByRegion.get(mobRegion); if (ids != null) ids.remove(entity.getUniqueId()); }
        vanillaDrops.clear(); ZoneType type; try{type=ZoneType.valueOf(raw);}catch(Exception ignored){return;}
        if(killer==null)return; PlayerState state=state(killer); if(!state.operational()){killer.sendMessage(messages.get(killer,"zones.state-loading"));return;}if(type==ZoneType.HAVEN)state.havenKills++;else state.riftKills++;persistPlayer(state);
        if(isEventActive()){double points=type==ZoneType.HAVEN?config(type).havenScore():config(type).riftScore();Score previous=scores.get(killer.getUniqueId());Score score=new Score(killer.getUniqueId(),killer.getName(),(previous==null?0D:previous.score())+points,previous==null?System.currentTimeMillis():previous.reachedAt());scores.put(killer.getUniqueId(),score);long start=currentEventStart();persist("score:"+start+":"+score.uuid(),()->storage.upsertScore(start,new ZoneStorage.ScoreRow(score.uuid(),score.name(),score.score(),score.reachedAt())));}
        List<RolledLoot> rewards=rollLoot(killer,type); if(!rewards.isEmpty())deliverRewards(killer,type,rewards);
    }
    private List<RolledLoot> rollLoot(Player player,ZoneType type){List<LootEntry> pool=loot.getOrDefault(type,List.of());if(pool.isEmpty())return List.of();List<RolledLoot> result=new ArrayList<>();Set<String> rareSeen=new HashSet<>();int guaranteed=(int)Math.floor(amplification(player,type)/100D);double chance=amplification(player,type)/100D-guaranteed;int rolls=1+guaranteed+(chance>0D&&ThreadLocalRandom.current().nextDouble()<chance?1:0);for(int i=0;i<rolls;i++)for(LootEntry entry:pool)if(ThreadLocalRandom.current().nextDouble()*100D<entry.chance()){String key=Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(new ItemStack[]{entry.item()}));if(entry.chance()<config(type).rareThreshold()&& !rareSeen.add(key))continue;result.add(new RolledLoot(entry.item().clone(),entry.chance()));}return result;}
    private void deliverRewards(Player player, ZoneType type, List<RolledLoot> rewards) {
        PlayerState state = state(player);
        List<ItemStack> tagged = new ArrayList<>();
        double rareChance = -1D;
        for (RolledLoot reward : rewards) {
            ItemStack copy = reward.item().clone();
            if (reward.chance() < RARE_DROP_MESSAGE_THRESHOLD_PERCENT) {
                rareChance = Math.max(rareChance, reward.chance());
            }
            if (type == ZoneType.RIFTLANDS && state.sessionId != null) {
                ItemMeta meta = copy.getItemMeta();
                meta.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, state.sessionId);
                copy.setItemMeta(meta);
            }
            tagged.add(copy);
        }

        BackpackManager.EquippedBackpack bag = backpacks.equippedBackpack(player);
        ItemStack backpackBefore = bag == null ? null : bag.item().clone();
        long storedBefore = bag == null ? -1L : backpacks.equippedStoredCount(player);
        List<ItemStack> leftovers = bag == null ? tagged : backpacks.storeExact(bag, tagged);
        if (!queueOverflow(player, leftovers, "zone-loot")) {
            if (backpackBefore != null) {
                player.getInventory().setItemInOffHand(backpackBefore);
                player.updateInventory();
            }
            player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
            return;
        }
        if (bag != null) {
            player.getInventory().setItemInOffHand(bag.item().clone());
            player.updateInventory();
            sendStorageActionBar(player, bag, storedBefore);
        }
        if (rareChance >= 0D) {
            player.sendMessage(messages.get(player, "zones.rare-loot-reward",
                    "zone", type.displayName(), "chance", PERCENT.format(rareChance)));
        }
    }
    private void sendStorageActionBar(Player player, BackpackManager.EquippedBackpack equipped, long storedBefore) {
        long stored = backpacks.equippedStoredCount(player);
        if (stored <= storedBefore) return;
        player.sendActionBar(messages.get(player, "backpack.store-action-bar",
                "backpack_name", backpacks.displayName(equipped.tier()),
                "tier", backpacks.tierLabel(equipped.tier()),
                "backpack_level", String.valueOf(backpacks.equippedLevel(player)),
                "used_storage", String.format("%,d", stored),
                "storage", String.format("%,d", backpacks.equippedCapacity(player))));
    }
    public List<ItemStack> takeRiftSessionLoot(Player player){PlayerState state=state(player);if(!state.operational()||state.sessionId==null)return List.of();String id=state.sessionId;List<ItemStack> drops=new ArrayList<>();for(int slot=0;slot<player.getInventory().getSize();slot++){ItemStack item=player.getInventory().getItem(slot);if(matchesSession(item,id)){drops.add(item.clone());player.getInventory().setItem(slot,null);}}drops.addAll(backpacks.removeMarkedFromEquipped(player,sessionKey,id));state.sessionId=null;persistPlayer(state);return drops;}
    public void secureRiftSession(Player player){PlayerState state=state(player);if(!state.operational())return;if(state.sessionId!=null){state.sessionId=null;persistPlayer(state);player.sendMessage(messages.get(player,"zones.rift-loot-secured"));}}
    public boolean isSessionItem(Player player,ItemStack item){PlayerState state=state(player);return state.loaded&&state.sessionId!=null&&matchesSession(item,state.sessionId);}
    private boolean matchesSession(ItemStack item,String id){return item!=null&&item.hasItemMeta()&&id.equals(item.getItemMeta().getPersistentDataContainer().get(sessionKey,PersistentDataType.STRING));}
    public void setDeathCooldown(Player player,ZoneType type){PlayerState state=state(player);if(!state.operational())return;long seconds=config(type).deathCooldownSeconds();state.setCooldown(type,seconds<=0?0:System.currentTimeMillis()+seconds*1000L);persistPlayer(state);}
    public double progressionBoost(Player player,ZoneType type){long kills=state(player).kills(type);double value=0D;for(var e:config(type).milestones().entrySet())if(kills>=e.getKey())value=e.getValue();return value;}
    public double amplification(Player player,ZoneType type){double value=progressionBoost(player,type)+winnerBoost(player);double backpack=backpacks.equippedDropBonusPercent(player);if(backpack>0)value+=backpack;BoosterService service=boosters;if(service!=null)value+=service.contributions(player,me.vertex.core.booster.BoosterCategory.MOB_DROP).stream().filter(c->c.active()&&!"backpack".equals(c.sourceId())&&!"zone".equals(c.sourceId())).mapToDouble(me.vertex.core.booster.BoosterContribution::percent).sum();double cap=config(type).amplificationCap();return cap<=0D?value:Math.min(value,cap);}
    public double winnerBoost(Player player){return state(player).winnerBoost;}

    // ---- ticket -----------------------------------------------------------
    public ItemStack createTicket(){ZoneConfig c=config(ZoneType.RIFTLANDS);ItemStack item=new ItemStack(c.ticketMaterial());ItemMeta meta=item.getItemMeta();meta.displayName(MessageFormatter.deserialize(me.vertex.core.lang.SmallCaps.template(c.ticketName())));meta.lore(c.ticketLore().stream().map(me.vertex.core.lang.SmallCaps::template).map(MessageFormatter::deserialize).toList());if(c.ticketModel()!=null)meta.setCustomModelData(c.ticketModel());if(c.ticketGlow()){meta.addEnchant(Enchantment.UNBREAKING,1,true);meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);}meta.getPersistentDataContainer().set(ticketKey,PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);trackedItems.ensureInstanceId(item,ItemKind.RIFTLANDS_TICKET);return item;}
    public String useTicket(Player player,ItemStack held){ZoneRegion region=regionAt(player.getLocation());if(region==null||region.type()!=ZoneType.RIFTLANDS)return "not-rift";if(!combat.isTagged(player.getUniqueId()))return "not-combat";Location target=findSafeTicketLocation(player,region);if(target==null)return "no-safe";if(!player.teleport(target))return "no-safe";combat.clear(player.getUniqueId());held.setAmount(held.getAmount()-1);player.sendMessage(messages.get(player,"zones.ticket-success"));return "ok";}
    private Location findSafeTicketLocation(Player player,ZoneRegion region){ZoneConfig c=config(ZoneType.RIFTLANDS);List<Player> hostiles=player.getWorld().getPlayers().stream().filter(other->!other.equals(player)&&isHostile(player,other)).toList();Location best=null;double bestScore=-1D;for(int i=0;i<c.ticketCandidates();i++){int x=ThreadLocalRandom.current().nextInt(region.minX()+c.ticketBorder(),region.maxX()-c.ticketBorder()+1);int z=ThreadLocalRandom.current().nextInt(region.minZ()+c.ticketBorder(),region.maxZ()-c.ticketBorder()+1);Location candidate=safeGround(region,new Location(player.getWorld(),x,player.getLocation().getY(),z),c.ticketRouteAvoid());if(candidate==null)continue;double min=Double.MAX_VALUE;for(Player hostile:hostiles)min=Math.min(min,candidate.distanceSquared(hostile.getLocation()));if(min>bestScore){bestScore=min;best=candidate;}}return best;}
    private boolean isHostile(Player a,Player b){return !me.vertex.core.factions.FactionsHook.isSameFaction(a,b)&&!me.vertex.core.factions.FactionsHook.isAllyFaction(me.vertex.core.factions.FactionsHook.getFactionId(a),me.vertex.core.factions.FactionsHook.getFactionId(b));}

    // ---- event / scheduled tick -----------------------------------------
    private void tick(){long now=System.currentTimeMillis();tickCountdowns(now);tickFlights();tickSlowFalling();tickMobs();updateMobNameplates();tickEvent(now);}
    private void tickCountdowns(long now){for(var entry:List.copyOf(entries.entrySet())){Player player=Bukkit.getPlayer(entry.getKey());Countdown countdown=entry.getValue();if(player==null||invalidCountdown(player,countdown)){if(player!=null)cancelEntry(player,"movement/combat");else entries.remove(entry.getKey());continue;}long remaining=Math.max(0L,countdown.until-now);if(remaining==0L){entries.remove(entry.getKey());if(!startFlight(player,countdown.type))player.sendMessage(messages.get(player,"zones.no-route"));}else if(remaining%1000L<1000L){player.sendMessage(messages.get(player,"zones.entry-countdown","zone",countdown.type.displayName(),"seconds",String.valueOf((remaining+999)/1000)));}}
        for(var entry:List.copyOf(exits.entrySet())){Player player=Bukkit.getPlayer(entry.getKey());Countdown countdown=entry.getValue();if(player==null||invalidCountdown(player,countdown)){if(player!=null)cancelExit(player,"movement/combat");else exits.remove(entry.getKey());continue;}long remaining=Math.max(0L,countdown.until-now);if(remaining==0L){exits.remove(entry.getKey());secureRiftSession(player);spawnDispatchBypass.add(player.getUniqueId());Bukkit.dispatchCommand(player,"spawn");}else if(remaining%1000L<1000L)player.sendMessage(messages.get(player,"zones.spawn-countdown","seconds",String.valueOf((remaining+999)/1000)));}}
    private boolean invalidCountdown(Player player,Countdown countdown){return combat.isTagged(player.getUniqueId())||!Objects.equals(player.getWorld(),countdown.origin.getWorld())||player.getLocation().distanceSquared(countdown.origin)>0.01D;}
    private void tickFlights(){for(Flight flight:List.copyOf(flights.values())){Player player=Bukkit.getPlayer(flight.playerId());if(player==null){flights.remove(flight.playerId());continue;}if(player.isOnGround())releaseFlight(player.getUniqueId(),false);}}
    private void tickSlowFalling(){for(UUID uuid:List.copyOf(slowFalling)){Player player=Bukkit.getPlayer(uuid);if(player==null||player.isOnGround()){if(player!=null)player.removePotionEffect(PotionEffectType.SLOW_FALLING);slowFalling.remove(uuid);}}}
    private void tickMobs(){for(ZoneType type:ZoneType.values()){Map<String,List<Player>> byRegion=new HashMap<>();for(Player p:Bukkit.getOnlinePlayers()){ZoneRegion r=regionAt(p.getLocation());if(r!=null&&r.type()==type)byRegion.computeIfAbsent(r.id(),ignored->new ArrayList<>()).add(p);}for(ZoneRegion r:regions(type)){List<Player> active=byRegion.getOrDefault(r.id(),List.of());if(active.isEmpty()){despawnZoneMobs(r);continue;}maintainMobs(r,active);despawnFarZoneMobs(r,active,mobDespawnRadii.getOrDefault(type,40D));}}}
    private void maintainMobs(ZoneRegion region,List<Player> active){if(region==null)return;ZoneConfig c=config(region.type());if(active.isEmpty()||!prepareMobWorld(active.getFirst().getWorld(),c))return;int zoneMobCount=countZoneMobs(region,active.getFirst().getWorld());for(Player player:active){if(!player.isOnGround()||zoneMobCount>=c.maxLocalMobs())continue;int target=ThreadLocalRandom.current().nextInt(10,21);int nearby=(int)player.getWorld().getNearbyEntities(player.getLocation(),10D,10D,10D,entity->isZoneMob(entity)&&region.id().equals(entity.getPersistentDataContainer().get(mobRegionKey,PersistentDataType.STRING))&&entity.getLocation().distanceSquared(player.getLocation())<=100D).size();for(int count=nearby;count<target&&zoneMobCount<c.maxLocalMobs();count++){Location at=randomMobLocation(region,player,c,2D,10D);if(at==null)break;spawnMob(region,at);zoneMobCount++;}}}
    private int countZoneMobs(ZoneRegion region,World world){int count=0;Set<UUID> ids=zoneMobsByRegion.get(region.id());if(ids==null)return 0;for(UUID id:ids){Entity entity=Bukkit.getEntity(id);if(entity!=null&&entity.isValid()&&entity.getWorld().equals(world)&&region.contains(entity.getLocation()))count++;}return count;}
    private void despawnFarZoneMobs(ZoneRegion region,List<Player> active,double radius){Set<UUID> ids=zoneMobsByRegion.get(region.id());if(ids==null)return;double radiusSquared=radius*radius;for(UUID id:List.copyOf(ids)){Entity entity=Bukkit.getEntity(id);if(!(entity instanceof LivingEntity living)||!isZoneMob(entity)){ids.remove(id);continue;}boolean nearby=active.stream().anyMatch(player->player.getWorld().equals(entity.getWorld())&&player.getLocation().distanceSquared(entity.getLocation())<=radiusSquared);if(!nearby){entity.remove();ids.remove(id);}}}
    private boolean prepareMobWorld(World world,ZoneConfig config){if(world.getDifficulty()!=Difficulty.PEACEFUL){peacefulWorldWarnings.remove(world.getName());return true;}if(!config.forceNormalDifficulty()){if(peacefulWorldWarnings.add(world.getName()))plugin.getLogger().warning("Zone mobs cannot spawn in peaceful world '"+world.getName()+"'. Set mob-spawning.force-normal-difficulty to true or change the world's difficulty.");return false;}world.setDifficulty(Difficulty.NORMAL);peacefulWorldWarnings.remove(world.getName());plugin.getLogger().info("Changed zone world '"+world.getName()+"' from PEACEFUL to NORMAL so configured hostile zone mobs can spawn.");return true;}
    private List<List<Player>> clusters(List<Player> active,double radius){List<List<Player>> result=new ArrayList<>();Set<UUID> used=new HashSet<>();double radiusSquared=radius*radius;for(Player root:active){if(!used.add(root.getUniqueId()))continue;List<Player> cluster=new ArrayList<>();java.util.ArrayDeque<Player> queue=new java.util.ArrayDeque<>();cluster.add(root);queue.add(root);while(!queue.isEmpty()){Player current=queue.removeFirst();for(Player other:active){if(used.contains(other.getUniqueId())||!current.getWorld().equals(other.getWorld())||current.getLocation().distanceSquared(other.getLocation())>radiusSquared)continue;used.add(other.getUniqueId());cluster.add(other);queue.addLast(other);}}result.add(cluster);}return result;}
    private Location randomMobLocation(ZoneRegion region,Player player,ZoneConfig config){return randomMobLocation(region,player,config,config.minSpawnDistance(),config.maxSpawnDistance());}
    private Location randomMobLocation(ZoneRegion region,Player player,ZoneConfig config,double minDistance,double maxDistance){for(int tries=0;tries<12;tries++){double angle=ThreadLocalRandom.current().nextDouble(Math.PI*2D);double normalized=ThreadLocalRandom.current().nextDouble();double distance=minDistance+(maxDistance-minDistance)*Math.pow(normalized,config.spawnDistanceBias());Location base=player.getLocation().clone().add(Math.cos(angle)*distance,0,Math.sin(angle)*distance);Location safe=safeGround(region,base,0);if(safe!=null&&safe.distanceSquared(player.getLocation())>=minDistance*minDistance&&safe.distanceSquared(player.getLocation())<=maxDistance*maxDistance)return safe;}return null;}
    private Location safeGround(ZoneRegion region,Location near,int routeAvoid){
        if(near==null||near.getWorld()==null)return null;
        World world=near.getWorld();int x=near.getBlockX(),z=near.getBlockZ();
        if(!region.world().equals(world.getName())||x<region.minX()||x>region.maxX()||z<region.minZ()||z>region.maxZ())return null;
        int minimumFeet=Math.max(region.minY()+1,world.getMinHeight()+1);
        int maximumFeet=Math.min(region.maxY()-1,world.getMaxHeight()-2);
        if(minimumFeet>maximumFeet)return null;
        // The world's highest block can sit above an indoor or underground
        // arena. Search downward inside the selected cuboid for two open
        // blocks over a solid floor instead of rejecting the whole column.
        int start=Math.min(maximumFeet,world.getHighestBlockYAt(x,z)+1);
        if(start<minimumFeet)start=maximumFeet;
        for(int y=start;y>=minimumFeet;y--){
            Location result=new Location(world,x+.5,y,z+.5);
            if(!result.getBlock().isPassable()||!result.clone().add(0,1,0).getBlock().isPassable()
                    ||!result.clone().add(0,-1,0).getBlock().getType().isSolid())continue;
            if(routeAvoid>0){boolean tooClose=false;for(ZoneRoute route:routes(region)){for(ZoneRoute.Waypoint point:route.waypoints()){if(result.distanceSquared(point.location(world))<routeAvoid*(double)routeAvoid){tooClose=true;break;}}if(tooClose)break;}if(tooClose)continue;}
            return result;
        }
        return null;
    }
    private Location findSafeGround(ZoneRegion region,Location near){Location direct=safeGround(region,near,0);if(direct!=null)return direct;for(int radius=1;radius<=16;radius++){for(int dx=-radius;dx<=radius;dx++){for(int dz=-radius;dz<=radius;dz++){if(Math.abs(dx)!=radius&&Math.abs(dz)!=radius)continue;Location found=safeGround(region,near.clone().add(dx,0,dz),0);if(found!=null)return found;}}}return null;}
    private void spawnMob(ZoneRegion region,Location at){List<MobDefinition> pool=config(region.type()).mobs();if(pool.isEmpty())return;double total=pool.stream().filter(definition -> definition.enabled).mapToDouble(definition -> definition.weight).sum();if(total<=0D)return;double roll=ThreadLocalRandom.current().nextDouble(total);MobDefinition selected=pool.getFirst();for(MobDefinition definition:pool)if(definition.enabled&&(roll-=definition.weight)<=0D){selected=definition;break;}Class<? extends Entity> rawClass=selected.type.getEntityClass();if(rawClass==null||!LivingEntity.class.isAssignableFrom(rawClass))return;@SuppressWarnings("unchecked")Class<? extends LivingEntity> entityClass=(Class<? extends LivingEntity>)rawClass;LivingEntity entity=spawnTaggedMob(at,entityClass,region,selected);if(entity==null||!entity.isValid())return;zoneMobsByRegion.computeIfAbsent(region.id(),ignored->ConcurrentHashMap.newKeySet()).add(entity.getUniqueId());entity.setCanPickupItems(false);double health=ThreadLocalRandom.current().nextDouble(selected.minHealth,Math.max(selected.minHealth+.001D,selected.maxHealth));if(entity.getAttribute(Attribute.MAX_HEALTH)!=null)entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);entity.setHealth(Math.min(health,entity.getMaxHealth()));MobProfile profile=selected.profiles.get(ThreadLocalRandom.current().nextInt(selected.profiles.size()));entity.getPersistentDataContainer().set(mobProfileKey,PersistentDataType.STRING,profile.name);equipRandomLeatherArmor(entity);if(ThreadLocalRandom.current().nextDouble()<.35D)entity.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,Integer.MAX_VALUE,2,false,false,true));updateMobNameplate(entity,region.type(),profile.name);if(entity.getAttribute(Attribute.ATTACK_DAMAGE)!=null)entity.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(profile.damage*.5D);}
    private void updateMobNameplates(){for(Set<UUID> ids:zoneMobsByRegion.values())for(UUID id:ids){Entity entity=Bukkit.getEntity(id);if(!(entity instanceof LivingEntity living)||!isZoneMob(living))continue;String raw=entity.getPersistentDataContainer().get(zoneMobKey,PersistentDataType.STRING);ZoneType type;try{type=ZoneType.valueOf(raw);}catch(Exception ignored){continue;}String profile=entity.getPersistentDataContainer().get(mobProfileKey,PersistentDataType.STRING);if(profile==null)profile=entity.getPersistentDataContainer().get(mobDefinitionKey,PersistentDataType.STRING);updateMobNameplate(living,type,profile);}}
    private void updateMobNameplate(LivingEntity entity,ZoneType type,String profile){
        if(entity instanceof org.bukkit.entity.Mob mob){LivingEntity target=mob.getTarget();boolean engaged=target!=null&&target.isValid()&&!target.isDead();mob.setAware(engaged);}
        double health=entity.getHealth();
        double previous=Double.NaN;
        for(MetadataValue value:entity.getMetadata("vertex_zone_last_health"))if(value.getOwningPlugin()==plugin){previous=value.asDouble();break;}
        if(!Double.isNaN(previous)&&health>previous){entity.setHealth(previous);health=previous;}
        entity.setMetadata("vertex_zone_last_health",new FixedMetadataValue(plugin,health));
        TextColor color=type==ZoneType.RIFTLANDS?NamedTextColor.DARK_PURPLE:TextColor.color(255,255,128);String title=profile==null?"MOB":profile.replace('_',' ');if(type==ZoneType.RIFTLANDS)title=title.toUpperCase(Locale.ROOT);Component style=Component.text(title).color(color).decorate(TextDecoration.BOLD);Component healthComponent=Component.text(PERCENT.format(health)).color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD);Component heart=Component.text(" ❤").color(NamedTextColor.RED).decorate(TextDecoration.BOLD);entity.customName(healthComponent.append(heart).append(Component.newline()).append(style));entity.setCustomNameVisible(true);}
    private void equipRandomLeatherArmor(LivingEntity entity){EntityEquipment equipment=entity.getEquipment();if(equipment==null)return;Color[] colors={Color.fromRGB(45,20,70),Color.fromRGB(75,25,100),Color.fromRGB(35,35,35),Color.fromRGB(110,20,35),Color.fromRGB(20,65,80)};Color color=colors[ThreadLocalRandom.current().nextInt(colors.length)];equipment.setHelmet(leather(Material.LEATHER_HELMET,color));equipment.setChestplate(leather(Material.LEATHER_CHESTPLATE,color));equipment.setLeggings(leather(Material.LEATHER_LEGGINGS,color));equipment.setBoots(leather(Material.LEATHER_BOOTS,color));equipment.setHelmetDropChance(0F);equipment.setChestplateDropChance(0F);equipment.setLeggingsDropChance(0F);equipment.setBootsDropChance(0F);}
    private ItemStack leather(Material material,Color color){ItemStack item=new ItemStack(material);LeatherArmorMeta meta=(LeatherArmorMeta)item.getItemMeta();meta.setColor(color);item.setItemMeta(meta);return item;}
    private <T extends LivingEntity>T spawnTaggedMob(Location at,Class<T> entityClass,ZoneRegion region,MobDefinition definition){return at.getWorld().spawn(at,entityClass,org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM,false,entity->{entity.getPersistentDataContainer().set(zoneMobKey,PersistentDataType.STRING,region.type().name());entity.getPersistentDataContainer().set(mobRegionKey,PersistentDataType.STRING,region.id());entity.getPersistentDataContainer().set(mobDefinitionKey,PersistentDataType.STRING,definition.id);});}
    private void despawnZoneMobs(ZoneRegion region){Set<UUID> ids=zoneMobsByRegion.remove(region.id());if(ids==null)return;for(UUID id:ids){Entity entity=Bukkit.getEntity(id);if(entity!=null&&isZoneMob(entity))entity.remove();}}
    private void tickEvent(long now){long start=currentEventStart();boolean active=now>=start&&now<start+config(ZoneType.HAVEN).eventDurationMillis();if(active&&loadedScoreEvent!=start)restoreCurrentScores();if(active&&lastAnnouncedEvent!=start){lastAnnouncedEvent=start;for(Player player:Bukkit.getOnlinePlayers())player.sendMessage(messages.get(player,"zones.event-start"));}if(!active&&loadedScoreEvent==start&&lastFinishedEvent!=start){finishEvent(start);lastFinishedEvent=start;}if(now-lastBossRefresh>=config(ZoneType.HAVEN).bossRefreshMillis()){lastBossRefresh=now;updateBossBar(active,start,now);}}
    private long currentEventStart(){long cycle=Math.max(60_000L,config(ZoneType.HAVEN).eventCycleMillis());long now=System.currentTimeMillis();return cycleAnchor+Math.floorDiv(now-cycleAnchor,cycle)*cycle;}
    private boolean isEventActive(){long now=System.currentTimeMillis(),start=currentEventStart();return now>=start&&now<start+config(ZoneType.HAVEN).eventDurationMillis();}
    private void restoreCurrentScores(){long start=currentEventStart();scores.clear();try{for(ZoneStorage.ScoreRow row:storage.loadScores(start))scores.put(row.uuid(),new Score(row.uuid(),row.name(),row.score(),row.reachedAt()));loadedScoreEvent=start;}catch(SQLException e){plugin.getLogger().log(Level.WARNING,"Could not restore zone event scores",e);}}
    private void finishEvent(long start){List<Score> top=topScores();double[] rewards={config(ZoneType.HAVEN).firstBoost(),config(ZoneType.HAVEN).secondBoost(),config(ZoneType.HAVEN).thirdBoost()};for(PlayerState state:players.values()){state.winnerBoost=0D;state.winnerCycle=start;}List<ZoneStorage.WinnerRow> winners=new ArrayList<>();for(int i=0;i<Math.min(3,top.size());i++){Player online=Bukkit.getPlayer(top.get(i).uuid());PlayerState state=online!=null?state(online):state(top.get(i).uuid(),top.get(i).name());state.winnerBoost=rewards[i];state.winnerCycle=start;winners.add(new ZoneStorage.WinnerRow(top.get(i).uuid(),top.get(i).name(),rewards[i]));if(online!=null)online.sendMessage(messages.get(online,"zones.event-winner","place",String.valueOf(i+1),"boost",PERCENT.format(rewards[i])));}persist("winner-boosts",()->storage.replaceWinnerBoosts(start,winners));for(Player p:Bukkit.getOnlinePlayers())p.sendMessage(messages.get(p,"zones.event-end"));}
    private List<Score> topScores(){return scores.values().stream().sorted(Comparator.comparingDouble(Score::score).reversed().thenComparingLong(Score::reachedAt).thenComparing(s->s.uuid().toString())).limit(3).toList();}
    private void updateBossBar(boolean active,long start,long now){if(!active){for(Player p:Bukkit.getOnlinePlayers())eventBar.removePlayer(p);return;}List<Score> top=topScores();String text="Mob Kill Event | "+formatDuration(start+config(ZoneType.HAVEN).eventDurationMillis()-now);for(int i=0;i<3;i++)text+=" | #"+(i+1)+" "+(i<top.size()?top.get(i).name()+": "+PERCENT.format(top.get(i).score()):"-");eventBar.setTitle(text);eventBar.setProgress(Math.max(0D,Math.min(1D,(start+config(ZoneType.HAVEN).eventDurationMillis()-now)/(double)config(ZoneType.HAVEN).eventDurationMillis())));for(Player p:Bukkit.getOnlinePlayers()){if(isIn(p,ZoneType.HAVEN)||isIn(p,ZoneType.RIFTLANDS))eventBar.addPlayer(p);else eventBar.removePlayer(p);}}

    // ---- persistence/config/inspection ----------------------------------
    public PlayerState state(Player player){return state(player.getUniqueId(),player.getName());}
    /** Cache-only lookup. Player state is loaded during asynchronous pre-login. */
    private PlayerState state(UUID uuid, String name){return players.computeIfAbsent(uuid,key->new PlayerState(key,name));}
    private void persistPlayer(PlayerState state){
        ZoneStorage.PlayerRow snapshot = state.toRow();
        persist("player:" + state.uuid, () -> storage.upsertPlayer(snapshot),
                () -> state.persistenceHealthy = true,
                error -> state.persistenceHealthy = false);
    }
    private List<ZoneStorage.LootRow> lootRows(List<LootEntry> entries){List<ZoneStorage.LootRow> rows=new ArrayList<>();for(int i=0;i<entries.size();i++)rows.add(new ZoneStorage.LootRow(i,ItemStack.serializeItemsAsBytes(new ItemStack[]{entries.get(i).item()}),entries.get(i).chance()));return rows;}
    private void persist(SqlAction action){persist("write:"+writeVersion.incrementAndGet(),action);}
    private void persist(String key, SqlAction action){persist(key,action,()->{},error->{});}
    private void persist(String key, SqlAction action, Runnable success,
            java.util.function.Consumer<Throwable> failure) {
        PendingWrite pending = new PendingWrite(key, writeVersion.incrementAndGet(), action, success, failure);
        dirtyWrites.put(key, pending);
        enqueueWrite(pending);
    }
    private void enqueueWrite(PendingWrite pending) {
        CompletableFuture<Void> write;
        synchronized(writeLock){
            write=writeChain.handle((ignored,error)->null).thenRunAsync(()->{
                try { pending.action().run(); }
                catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
            });
            writeChain=write;
            pendingWrites.add(write);
        }
        write.whenComplete((ignored,error)->{
            pendingWrites.remove(write);
            if(error==null){
                if(dirtyWrites.remove(pending.key(),pending)) pending.success().run();
                return;
            }
            pending.failure().accept(error);
            if (dirtyWrites.get(pending.key()) != pending) return;
            plugin.getLogger().log(Level.SEVERE,"Failed to persist zone state '"+pending.key()
                    +"'; it remains dirty and will retry.",error);
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                if(dirtyWrites.get(pending.key())==pending)enqueueWrite(pending);
            },100L);
        });
    }
    public void awaitWrites(){
        try{CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).get(10,TimeUnit.SECONDS);}
        catch(Exception error){plugin.getLogger().log(Level.WARNING,"Timed out waiting for pending zone writes",error);}
        // Scheduled retries do not run after disable. Give every still-dirty
        // idempotent snapshot one final synchronous attempt during shutdown.
        for(PendingWrite pending:List.copyOf(dirtyWrites.values())){
            try{pending.action().run();if(dirtyWrites.remove(pending.key(),pending))pending.success().run();}
            catch(Exception error){pending.failure().accept(error);plugin.getLogger().log(Level.SEVERE,
                    "Zone state '"+pending.key()+"' is still dirty at shutdown.",error);}
        }
    }
    @FunctionalInterface private interface SqlAction { void run() throws Exception; }
    private record PendingWrite(String key,long version,SqlAction action,Runnable success,
            java.util.function.Consumer<Throwable> failure) { }
    public ZoneConfig config(ZoneType type){return configs.getOrDefault(type,ZoneConfig.defaults(type));}
    public List<LootEntry> loot(ZoneType type){return loot.getOrDefault(type,List.of()).stream().map(entry->new LootEntry(entry.item().clone(),entry.chance())).toList();}
    public void saveLoot(ZoneType type,List<LootEntry> entries){List<LootEntry> snapshot=new ArrayList<>(entries);try{storage.replaceLoot(type,lootRows(snapshot));loot.put(type,snapshot);}catch(SQLException error){plugin.getLogger().log(Level.SEVERE,"Failed to persist "+type.displayName()+" loot pool; keeping the previous pool",error);}}
    public void normalizeLootItem(ZoneType type, ItemStack item) { if (item != null && !item.isEmpty() && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().get(chanceKey, PersistentDataType.DOUBLE) == null) setLootChance(item, config(type).defaultLootChance()); }
    public double lootChance(ZoneType type, ItemStack item){if(item==null||!item.hasItemMeta())return config(type).defaultLootChance();Double value=item.getItemMeta().getPersistentDataContainer().get(chanceKey,PersistentDataType.DOUBLE);return value==null?config(type).defaultLootChance():Math.max(0D,Math.min(100D,value));}
    /** Retained for callers that do not have a zone type; new editor code must use the typed overload. */
    public double lootChance(ItemStack item) { return lootChance(ZoneType.HAVEN, item); }
    public void setLootChance(ItemStack item,double chance){if(item==null)return;ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(chanceKey,PersistentDataType.DOUBLE,Math.max(0D,Math.min(100D,chance)));item.setItemMeta(meta);}
    /**
     * Builds an editor-only copy. The original stack is stored on that copy so
     * the GUI may show chance/help lore without saving that visual lore into
     * the actual reward item on close.
     */
    public ItemStack prepareLootEditorItem(ZoneType type, LootEntry entry) {
        ItemStack original = entry.item().clone();
        setLootChance(original, entry.chance());
        ItemStack editor = original.clone();
        ItemMeta meta = editor.getItemMeta();
        meta.getPersistentDataContainer().set(lootEditorOriginalKey, PersistentDataType.BYTE_ARRAY,
                ItemStack.serializeItemsAsBytes(new ItemStack[] { original }));
        editor.setItemMeta(meta);
        return editor;
    }
    public boolean isLootEditorItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(lootEditorOriginalKey,
                        PersistentDataType.BYTE_ARRAY);
    }
    /** Restores a clean reward stack while preserving the chance changed in the GUI. */
    public ItemStack finishLootEditorItem(ZoneType type, ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        double chance = lootChance(type, item);
        ItemStack clean = item.clone();
        if (isLootEditorItem(item)) {
            try {
                byte[] encoded = item.getItemMeta().getPersistentDataContainer().get(lootEditorOriginalKey,
                        PersistentDataType.BYTE_ARRAY);
                ItemStack[] decoded = encoded == null ? new ItemStack[0] : ItemStack.deserializeItemsFromBytes(encoded);
                if (decoded.length == 1 && decoded[0] != null && !decoded[0].isEmpty()) clean = decoded[0].clone();
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Could not restore a Zone loot-pool editor item; using its visible copy.", exception);
                ItemMeta meta = clean.getItemMeta();
                meta.getPersistentDataContainer().remove(lootEditorOriginalKey);
                clean.setItemMeta(meta);
            }
        }
        normalizeLootItem(type, clean);
        setLootChance(clean, chance);
        return clean;
    }
    public List<Score> topScoresForDisplay(){return topScores();}
    public String eventTopName(int place) { List<Score> top=topScores(); return place >= 1 && place <= top.size() ? top.get(place-1).name() : "-"; }
    public double eventTopScore(int place) { List<Score> top=topScores(); return place >= 1 && place <= top.size() ? top.get(place-1).score() : 0D; }
    public boolean eventActive() { return isEventActive(); }
    public double eventScore(Player player) { Score score = scores.get(player.getUniqueId()); return score == null ? 0D : score.score(); }
    public int eventRank(Player player) { List<Score> ordered = scores.values().stream().sorted(Comparator.comparingDouble(Score::score).reversed().thenComparingLong(Score::reachedAt).thenComparing(s -> s.uuid().toString())).toList(); for (int i=0;i<ordered.size();i++) if (ordered.get(i).uuid().equals(player.getUniqueId())) return i+1; return 0; }
    public long eventRemainingSeconds(){long now=System.currentTimeMillis(),start=currentEventStart();return isEventActive()?Math.max(0,(start+config(ZoneType.HAVEN).eventDurationMillis()-now+999)/1000):(start+config(ZoneType.HAVEN).eventCycleMillis()-now+999)/1000;}
    public String currentZone(Player player){ZoneRegion r=regionAt(player.getLocation());return r==null?"None":r.type().displayName();}
    public long kills(Player player,ZoneType type){return state(player).kills(type);}
    public long nextMilestone(Player player,ZoneType type){long kills=kills(player,type);return config(type).milestones().keySet().stream().filter(value->value>kills).findFirst().orElse(0L);}
    public long cooldownRemaining(Player player,ZoneType type){return Math.max(0,(state(player).cooldown(type)-System.currentTimeMillis()+999)/1000);}
    public void forceStartEvent() { cycleAnchor = System.currentTimeMillis(); scores.clear(); loadedScoreEvent = cycleAnchor; lastFinishedEvent = Long.MIN_VALUE; long snapshot=cycleAnchor;persist("cycle-anchor",() -> storage.saveLong("cycle_anchor", snapshot)); }
    public void forceStopEvent() { cycleAnchor = System.currentTimeMillis() - config(ZoneType.HAVEN).eventDurationMillis(); long snapshot=cycleAnchor;persist("cycle-anchor",() -> storage.saveLong("cycle_anchor", snapshot)); updateBossBar(false, currentEventStart(), System.currentTimeMillis()); }

    private ZoneConfig readConfig(ZoneType type){String name=type.configKey()+".yml";File file=new File(plugin.getDataFolder(),name);if(!file.exists())plugin.saveResource(name,false);YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file);return ZoneConfig.read(type,yaml,plugin);}
    private Optional<ZoneRoute> decodeRoute(ZoneStorage.RouteRow row){try{List<ZoneRoute.Waypoint> points=new ArrayList<>();for(String raw:row.waypoints().split(";")){if(raw.isBlank())continue;String[] p=raw.split("\\|",-1);if(p.length!=6)return Optional.empty();points.add(new ZoneRoute.Waypoint(new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8),Double.parseDouble(p[1]),Double.parseDouble(p[2]),Double.parseDouble(p[3]),Float.parseFloat(p[4]),Float.parseFloat(p[5])));}return points.isEmpty()?Optional.empty():Optional.of(new ZoneRoute(row.id(),row.regionId(),row.enabled(),row.speed(),row.autoDrop(),points));}catch(Exception e){return Optional.empty();}}
    private String encodeRoute(ZoneRoute route){StringBuilder out=new StringBuilder();for(ZoneRoute.Waypoint p:route.waypoints())out.append(Base64.getUrlEncoder().withoutPadding().encodeToString(p.world().getBytes(StandardCharsets.UTF_8))).append('|').append(p.x()).append('|').append(p.y()).append('|').append(p.z()).append('|').append(p.yaw()).append('|').append(p.pitch()).append(';');return out.toString();}
    private static String formatDuration(long millis){long seconds=Math.max(0,millis/1000L);return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}

    public record LootEntry(ItemStack item,double chance){ public LootEntry{item=item.clone();chance=Math.max(0D,Math.min(100D,chance));} }
    private record RolledLoot(ItemStack item,double chance) { }
    public record Score(UUID uuid,String name,double score,long reachedAt) { }
    private record Countdown(ZoneType type,Location origin,long until) { }
    private record FlightState(boolean allowFlight,boolean flying,float flySpeed) { }
    private record Flight(UUID playerId,ZoneType type,String regionId,FlightState before) { }
    private static final class Selection { final ZoneType type;final String regionId;final String routeId;Location first,second;final List<ZoneRoute.Waypoint> points=new ArrayList<>();private Selection(ZoneType type,String regionId,String routeId){this.type=type;this.regionId=regionId;this.routeId=routeId;}static Selection region(ZoneType type,String id){return new Selection(type,id,null);}static Selection route(ZoneType type,String region,String route){return new Selection(type,region,route);}void clear(){first=null;second=null;points.clear();} }
    public static final class PlayerState { final UUID uuid;String name;long havenKills,riftKills,havenCooldown,riftCooldown;String sessionId;double winnerBoost;long winnerCycle;volatile boolean loaded;volatile boolean persistenceHealthy=true;PlayerState(UUID uuid,String name){this.uuid=uuid;this.name=name;}static PlayerState from(ZoneStorage.PlayerRow row){PlayerState s=new PlayerState(row.uuid(),row.name());s.havenKills=row.havenKills();s.riftKills=row.riftKills();s.havenCooldown=row.havenCooldown();s.riftCooldown=row.riftCooldown();s.sessionId=row.sessionId();s.winnerBoost=row.winnerBoost();s.winnerCycle=row.winnerCycle();return s;}boolean operational(){return loaded&&persistenceHealthy;}long kills(ZoneType type){return type==ZoneType.HAVEN?havenKills:riftKills;}long cooldown(ZoneType type){return type==ZoneType.HAVEN?havenCooldown:riftCooldown;}void setCooldown(ZoneType type,long value){if(type==ZoneType.HAVEN)havenCooldown=value;else riftCooldown=value;}ZoneStorage.PlayerRow toRow(){return new ZoneStorage.PlayerRow(uuid,name,havenKills,riftKills,havenCooldown,riftCooldown,sessionId,winnerBoost,winnerCycle);} }

    /** Parsed configuration with strict safe fallbacks for every gameplay setting. */
    public record ZoneConfig(int countdownSeconds,int exitSeconds,long deathCooldownSeconds,double routeSpeed,int minSpawnDistance,int maxSpawnDistance,int baseLocalMobs,int additionalPerPlayer,int maxLocalMobs,int maxSpawnsPerPass,double spawnDistanceBias,double clusterRadius,boolean forceNormalDifficulty,long eventCycleMillis,long eventDurationMillis,long bossRefreshMillis,double havenScore,double riftScore,double firstBoost,double secondBoost,double thirdBoost,double defaultLootChance,double rareThreshold,double amplificationCap,Map<Long,Double> milestones,List<MobDefinition> mobs,Material entryMaterial,Integer entryModel,String entryName,List<String> entryLore,String entryTitle,int entrySize,Material ticketMaterial,Integer ticketModel,String ticketName,List<String> ticketLore,boolean ticketGlow,double ticketChance,boolean ticketInLoot,int ticketCandidates,int ticketBorder,int ticketRouteAvoid) {
        public ZoneConfig {
            countdownSeconds = Math.max(1, countdownSeconds); exitSeconds = Math.max(1, exitSeconds);
            deathCooldownSeconds = Math.max(0L, deathCooldownSeconds); routeSpeed = Math.max(.05D, Math.min(8D, routeSpeed));
            minSpawnDistance = Math.max(1, minSpawnDistance); maxSpawnDistance = Math.max(minSpawnDistance + 1, maxSpawnDistance);
            baseLocalMobs = Math.max(0, baseLocalMobs); additionalPerPlayer = Math.max(0, additionalPerPlayer); maxLocalMobs = Math.max(1, maxLocalMobs); maxSpawnsPerPass = Math.max(1, maxSpawnsPerPass); spawnDistanceBias = Math.max(1D, Math.min(8D, spawnDistanceBias)); clusterRadius = Math.max(4D, clusterRadius);
            eventCycleMillis = Math.max(60_000L, eventCycleMillis); eventDurationMillis = Math.max(10_000L, Math.min(eventCycleMillis, eventDurationMillis)); bossRefreshMillis = Math.max(1_000L, bossRefreshMillis);
            defaultLootChance = clamp(defaultLootChance,0D,100D); rareThreshold = clamp(rareThreshold,0D,100D); amplificationCap = Math.max(0D, amplificationCap);
            milestones = Map.copyOf(milestones == null ? Map.of() : milestones); mobs = List.copyOf(mobs == null ? List.of() : mobs);
            entryMaterial = entryMaterial == null || entryMaterial.isAir() ? Material.EMERALD_BLOCK : entryMaterial; entryLore = List.copyOf(entryLore == null ? List.of() : entryLore); entryTitle = entryTitle == null ? "Zones" : entryTitle;
            ticketMaterial = ticketMaterial == null || ticketMaterial.isAir() ? Material.PAPER : ticketMaterial; ticketLore = List.copyOf(ticketLore == null ? List.of() : ticketLore);
            ticketCandidates = Math.max(1, ticketCandidates); ticketBorder = Math.max(0, ticketBorder); ticketRouteAvoid = Math.max(0, ticketRouteAvoid);
        }
        static ZoneConfig defaults(ZoneType type){int base=type==ZoneType.HAVEN?60:72,additional=type==ZoneType.HAVEN?32:40,max=400;return new ZoneConfig(5,type==ZoneType.HAVEN?10:20,120,0.8,5,30,base,additional,max,12,2.5,80,true,120*60_000L,5*60_000L,2_000L,1D,1.5D,5D,3D,1D,20D,5D,0D,new LinkedHashMap<>(),List.of(),Material.EMERALD_BLOCK,null,"<green>Enter "+type.displayName(),List.of("<gray>Confirm entry"),"<dark_gray>Enter "+type.displayName(),27,Material.PAPER,null,"<aqua>Riftlands Ticket",List.of("<gray>Use while in Riftlands combat."),true,.75D,true,40,8,50);}
        static ZoneConfig read(ZoneType type,YamlConfiguration y,Plugin plugin){ZoneConfig d=defaults(type);ConfigurationSection mobRoot=y.getConfigurationSection("mobs");List<MobDefinition> mobs=new ArrayList<>();if(mobRoot!=null)for(String id:mobRoot.getKeys(false)){ConfigurationSection section=mobRoot.getConfigurationSection(id);if(section!=null)MobDefinition.read(id,section,plugin).ifPresent(mobs::add);}Map<Long,Double> milestones=new LinkedHashMap<>();ConfigurationSection progression=y.getConfigurationSection("progression.milestones");if(progression!=null)for(String key:progression.getKeys(false))try{milestones.put(Long.parseLong(key),Math.max(0D,progression.getDouble(key)));}catch(NumberFormatException ignored){plugin.getLogger().warning(type.configKey()+".yml: invalid milestone '"+key+"'.");}if(milestones.isEmpty())milestones.putAll(d.milestones);Material entry=material(y.getString("entry-gui.confirm-item.material"),d.entryMaterial,plugin,type);Material ticket=material(y.getString("riftlands-ticket.item.material"),d.ticketMaterial,plugin,type);return new ZoneConfig(Math.max(1,y.getInt("entry.countdown-seconds",d.countdownSeconds)),Math.max(1,y.getInt("exit.spawn-channel-seconds",d.exitSeconds)),Math.max(0,y.getLong("reentry-cooldown.on-death-seconds",d.deathCooldownSeconds)),Math.max(.05D,y.getDouble("routes.speed",d.routeSpeed)),Math.max(1,y.getInt("mob-spawning.min-spawn-distance",d.minSpawnDistance)),Math.max(2,y.getInt("mob-spawning.max-spawn-distance",d.maxSpawnDistance)),Math.max(0,y.getInt("mob-spawning.base-local-mobs",d.baseLocalMobs)),Math.max(0,y.getInt("mob-spawning.additional-mobs-per-player",d.additionalPerPlayer)),Math.max(1,y.getInt("mob-spawning.max-local-mobs",d.maxLocalMobs)),Math.max(1,y.getInt("mob-spawning.max-spawns-per-pass",d.maxSpawnsPerPass)),Math.max(1D,Math.min(8D,y.getDouble("mob-spawning.distance-bias",d.spawnDistanceBias))),Math.max(4D,y.getDouble("mob-spawning.cluster-radius",d.clusterRadius)),y.getBoolean("mob-spawning.force-normal-difficulty",d.forceNormalDifficulty),Math.max(60_000L,y.getLong("kill-event.cycle-minutes",120)*60_000L),Math.max(10_000L,y.getLong("kill-event.duration-minutes",5)*60_000L),Math.max(1_000L,y.getLong("kill-event.bossbar.refresh-seconds",2)*1_000L),y.getDouble("kill-event.scoring.haven",d.havenScore),y.getDouble("kill-event.scoring.riftlands",d.riftScore),y.getDouble("kill-event.rewards.first",d.firstBoost),y.getDouble("kill-event.rewards.second",d.secondBoost),y.getDouble("kill-event.rewards.third",d.thirdBoost),clamp(y.getDouble("loot-pool.default-item-chance",d.defaultLootChance),0,100),clamp(y.getDouble("loot-pool.rare-multi-drop-threshold-percent",d.rareThreshold),0,100),Math.max(0D,y.getDouble("loot-pool.amplification-cap-percent",d.amplificationCap)),Map.copyOf(milestones),List.copyOf(mobs),entry,y.contains("entry-gui.confirm-item.custom-model-data")?y.getInt("entry-gui.confirm-item.custom-model-data"):null,y.getString("entry-gui.confirm-item.name",d.entryName),y.getStringList("entry-gui.confirm-item.lore").isEmpty()?d.entryLore:y.getStringList("entry-gui.confirm-item.lore"),y.getString("entry-gui.title",d.entryTitle),validSize(y.getInt("entry-gui.size",d.entrySize)),ticket,y.contains("riftlands-ticket.item.custom-model-data")?y.getInt("riftlands-ticket.item.custom-model-data"):null,y.getString("riftlands-ticket.item.name",d.ticketName),y.getStringList("riftlands-ticket.item.lore").isEmpty()?d.ticketLore:y.getStringList("riftlands-ticket.item.lore"),y.getBoolean("riftlands-ticket.item.glow",d.ticketGlow),clamp(y.getDouble("riftlands-ticket.default-loot-chance",d.ticketChance),0,100),y.getBoolean("riftlands-ticket.include-in-default-loot",d.ticketInLoot),Math.max(1,y.getInt("riftlands-ticket.teleport.candidates",d.ticketCandidates)),Math.max(0,y.getInt("riftlands-ticket.teleport.border-distance",d.ticketBorder)),Math.max(0,y.getInt("riftlands-ticket.teleport.entry-route-avoid-radius",d.ticketRouteAvoid)));}
        private static Material material(String raw,Material fallback,Plugin plugin,ZoneType type){Material m=raw==null?null:Material.matchMaterial(raw);if(m==null||m.isAir()){if(raw!=null&&!raw.isBlank())plugin.getLogger().warning(type.configKey()+".yml has invalid material '"+raw+"'; using "+fallback);return fallback;}return m;}private static int validSize(int i){return i>=9&&i<=54&&i%9==0?i:27;}private static double clamp(double n,double min,double max){return Math.max(min,Math.min(max,n));}
    }
    public static final class MobDefinition { final String id;final EntityType type;final boolean enabled;final double weight,minHealth,maxHealth;final List<MobProfile> profiles;MobDefinition(String id,EntityType type,boolean enabled,double weight,double minHealth,double maxHealth,List<MobProfile> profiles){this.id=id;this.type=type;this.enabled=enabled;this.weight=Math.max(0D,weight);this.minHealth=Math.max(1D,minHealth);this.maxHealth=Math.max(this.minHealth,maxHealth);this.profiles=profiles.isEmpty()?List.of(new MobProfile(id,2D)):List.copyOf(profiles);}static Optional<MobDefinition> read(String id,ConfigurationSection s,Plugin p){try{EntityType type=EntityType.valueOf(s.getString("type","ZOMBIE").toUpperCase(Locale.ROOT));if(!type.isAlive()){p.getLogger().warning("Zone mob "+id+" is not living.");return Optional.empty();}List<String> names=s.getStringList("names");List<MobProfile> profiles=new ArrayList<>();ConfigurationSection profile=s.getConfigurationSection("profiles");if(names.isEmpty()&&profile!=null)names.addAll(profile.getKeys(false));if(names.isEmpty())names.add(id);for(String name:names)profiles.add(new MobProfile(name,profile==null?s.getDouble("damage",2D):profile.getDouble(name+".damage",s.getDouble("damage",2D))));return Optional.of(new MobDefinition(id,type,s.getBoolean("enabled",true),s.getDouble("spawn-weight",1D),s.getDouble("health.min",20D),s.getDouble("health.max",20D),profiles));}catch(IllegalArgumentException e){p.getLogger().warning("Zone mob "+id+" has invalid entity type.");return Optional.empty();}}
    }
    public record MobProfile(String name,double damage) { }
}
