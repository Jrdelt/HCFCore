package me.vertex.core.spawner;

import me.vertex.core.faction.FactionUpgradeManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Owns every tracked (placed by a player, Vertex-managed) spawner: its
 * in-memory registry, spawners.yml config (per-mob price/drops, global
 * stacking rules), and the vanilla CreatureSpawner tuning that makes a
 * stack of N actually spawn roughly N mobs per cycle instead of relying on
 * a manual re-spawn hook.
 */
public final class SpawnerManager {

    public enum BreakMode { DROP_ALL, DECREMENT }
    private static final double DEBUG_RANGE_BLOCKS = 64D;

    private final Plugin plugin;
    private final SpawnerStorage storage;
    private final File file;
    private final NamespacedKey markerKey;
    private final NamespacedKey mobTypeKey;
    private final NamespacedKey stackSizeKey;
    private final NamespacedKey ownerFactionKey;
    private final NamespacedKey placedAtDataKey;
    private final Random random = new Random();
    private final Map<String, SpawnerData> spawners = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<Void>> pendingWrites = ConcurrentHashMap.newKeySet();
    /** Serializes mutations for each physical location without making unrelated spawners wait. */
    private final Map<String, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();
    /** Next manual fallback cycle for each spawner, matching its configured vanilla delay. */
    private final Map<String, Long> nextManualSpawnTicks = new ConcurrentHashMap<>();
    /** Staff currently receiving nearby live spawn diagnostics. */
    private final java.util.Set<java.util.UUID> debugPlayers = ConcurrentHashMap.newKeySet();

    private volatile int maxStackSize;
    private volatile boolean silkTouchRequired;
    private volatile BreakMode breakMode;
    private volatile double sellRefundPercent;
    private volatile int spawnCountPerStack;
    private volatile int maxSpawnCount;
    private volatile int maxNearbyEntitiesPerStack;
    private volatile int maxNearbyEntitiesCap;
    private volatile int minSpawnDelayTicks;
    private volatile int maxSpawnDelayTicks;
    private volatile int requiredPlayerRangeBlocks;
    private volatile int spawnRangeBlocks;
    /** Whether exposed daytime spawners receive a manual, vanilla-rate fallback spawn. */
    private volatile boolean spawnInDaylight;
    /** Whether a grinder with no valid floor receives a manual in-air fallback spawn. */
    private volatile boolean spawnInAir;
    private volatile boolean overrideOtherPlugins = true;
    private volatile Map<EntityType, MobConfig> mobConfigs = Map.of();

    private volatile boolean mobStackingEnabled;
    private volatile double mergeRadiusBlocks;
    private volatile int maxStackLimit;
    private volatile java.util.Set<EntityType> stackableTypes = java.util.Set.of();
    private volatile String stackDisplayFormat;
    private volatile int dropBatchSize;
    /** Optional because spawners are also usable when faction upgrades are disabled. */
    private volatile FactionUpgradeManager factionUpgradeManager;
    /** Optional because F Top loads after spawner state during plugin startup. */
    private volatile me.vertex.core.faction.FTopManager fTopManager;

    public SpawnerManager(Plugin plugin, SpawnerStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "spawners.yml");
        this.markerKey = new NamespacedKey(plugin, "tracked_spawner");
        this.mobTypeKey = new NamespacedKey(plugin, "spawner_mob_type");
        this.stackSizeKey = new NamespacedKey(plugin, "spawner_stack_size");
        this.ownerFactionKey = new NamespacedKey(plugin, "spawner_owner_faction");
        this.placedAtDataKey = new NamespacedKey(plugin, "spawner_placed_at_data");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("spawners.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        // A spawners.yml saved to disk before a config section existed (e.g.
        // mob-stacking, added after this file was first generated on an
        // existing server) would otherwise silently read as empty/disabled
        // for anyone who already has an on-disk copy -- falling back to the
        // bundled resource's own values for anything the on-disk file is
        // missing keeps it working without ever touching/reformatting the
        // user's actual file.
        try (java.io.InputStream defaultStream = plugin.getResource("spawners.yml")) {
            if (defaultStream != null) {
                config.setDefaults(YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(defaultStream, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load bundled spawners.yml defaults.", e);
        }

        maxStackSize = Math.max(1, config.getInt("max-stack-size", 64));
        silkTouchRequired = config.getBoolean("silk-touch-required", true);
        breakMode = "decrement".equalsIgnoreCase(config.getString("break-mode", "drop-all"))
                ? BreakMode.DECREMENT : BreakMode.DROP_ALL;
        double configuredRefund = config.getDouble("sell-refund-percent", 50);
        sellRefundPercent = Math.max(0, Math.min(100, configuredRefund));
        if (configuredRefund != sellRefundPercent) {
            plugin.getLogger().warning("Clamped spawners.yml sell-refund-percent from " + configuredRefund
                    + " to " + sellRefundPercent + " (valid range: 0-100).");
        }

        spawnCountPerStack = Math.max(1, config.getInt("spawn-count-per-stack", 1));
        maxSpawnCount = Math.max(1, config.getInt("max-spawn-count", 8));
        maxNearbyEntitiesPerStack = Math.max(1, config.getInt("max-nearby-entities-per-stack", 2));
        maxNearbyEntitiesCap = Math.max(1, config.getInt("max-nearby-entities-cap", 32));
        minSpawnDelayTicks = Math.max(1, config.getInt("min-spawn-delay-ticks", 200));
        maxSpawnDelayTicks = Math.max(minSpawnDelayTicks, config.getInt("max-spawn-delay-ticks", 400));
        requiredPlayerRangeBlocks = Math.max(1, config.getInt("required-player-range-blocks", 32));
        spawnRangeBlocks = Math.max(1, config.getInt("spawn-range-blocks", 4));
        spawnInDaylight = config.getBoolean("spawn-in-daylight", true);
        spawnInAir = config.getBoolean("spawn-in-air", true);
        overrideOtherPlugins = config.getBoolean("override-other-plugins", true);

        mobConfigs = readMobConfigs(config);

        mobStackingEnabled = config.getBoolean("mob-stacking.enabled", true);
        mergeRadiusBlocks = Math.max(0, config.getDouble("mob-stacking.merge-radius-blocks", 50));
        maxStackLimit = Math.max(1, config.getInt("mob-stacking.max-stack-limit", 100));
        stackDisplayFormat = config.getString("mob-stacking.display-format", "<gray>[x{count}] <white>{name}");
        dropBatchSize = Math.max(1, config.getInt("mob-stacking.drop-batch-size", 64));
        java.util.Set<EntityType> types = new java.util.HashSet<>();
        for (String typeName : config.getStringList("mob-stacking.stackable-types")) {
            try {
                types.add(EntityType.valueOf(typeName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown entity type '" + typeName + "' in spawners.yml mob-stacking.stackable-types");
            }
        }
        stackableTypes = types;

        // A no-op on first boot (nothing loaded into `spawners` yet -- that
        // happens after this call, see loadSpawnersFromDatabase); on a
        // later /vertex reload, this is what actually makes changed
        // tuning values (spawn-count-per-stack etc.) apply to spawners
        // that already exist instead of only ones stacked/unstacked after.
        retuneAll();
    }

    /** Lets faction-rate bonuses retune existing spawners immediately after a purchase. */
    public void setFactionUpgradeManager(FactionUpgradeManager factionUpgradeManager) {
        this.factionUpgradeManager = factionUpgradeManager;
    }

    public void setFTopManager(me.vertex.core.faction.FTopManager fTopManager) {
        this.fTopManager = fTopManager;
    }

    public me.vertex.core.faction.FTopManager.StackValue getFTopValue(Location location, SpawnerData data) {
        me.vertex.core.faction.FTopManager manager = fTopManager;
        return manager == null ? new me.vertex.core.faction.FTopManager.StackValue(0D, 0D, 0D, 0L)
                : manager.stackValue(location, data);
    }

    /** Re-applies the current tuning to every tracked spawner whose chunk is loaded right now. */
    public void retuneAll() {
        for (Map.Entry<String, SpawnerData> entry : spawners.entrySet()) {
            String[] parts = entry.getKey().split(":", 4);
            World world = plugin.getServer().getWorld(parts[0]);
            if (world == null) {
                continue;
            }
            int blockX = Integer.parseInt(parts[1]);
            int blockZ = Integer.parseInt(parts[3]);
            if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                continue;
            }
            Location location = new Location(world, blockX, Integer.parseInt(parts[2]), blockZ);
            applyTuning(location, entry.getValue());
        }
    }

    private Map<EntityType, MobConfig> readMobConfigs(YamlConfiguration config) {
        Map<EntityType, MobConfig> result = new LinkedHashMap<>();
        ConfigurationSection mobsSection = config.getConfigurationSection("mobs");
        org.bukkit.configuration.Configuration defaults = config.getDefaults();
        ConfigurationSection defaultMobsSection = defaults != null ? defaults.getConfigurationSection("mobs") : null;

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        if (mobsSection != null) {
            keys.addAll(mobsSection.getKeys(false));
        }
        if (defaultMobsSection != null) {
            // getKeys() only ever enumerates a section's own on-disk
            // children, never the separate defaults tree set up in load()
            // -- so a mob type added to the bundled catalog after this
            // server's spawners.yml already had a "mobs:" section (which
            // has existed since day one) would otherwise silently never
            // appear here, no matter how new spawners.yml's own
            // setDefaults() fallback is elsewhere.
            keys.addAll(defaultMobsSection.getKeys(false));
        }

        for (String key : keys) {
            EntityType type;
            try {
                type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown entity type '" + key + "' in spawners.yml");
                continue;
            }
            ConfigurationSection section = mobsSection != null ? mobsSection.getConfigurationSection(key) : null;
            if (section == null && defaultMobsSection != null) {
                section = defaultMobsSection.getConfigurationSection(key);
            }
            if (section == null) {
                continue;
            }
            String displayName = section.getString("display-name", key);
            double configuredPrice = section.getDouble("price", 0);
            double price = Math.max(0, configuredPrice);
            if (configuredPrice < 0) {
                plugin.getLogger().warning("Clamped negative price for " + key + " spawner to 0 in spawners.yml.");
            }
            List<DropEntry> drops = readDrops(section.getMapList("drops"), key);
            result.put(type, new MobConfig(type, displayName, price, drops));
        }
        return result;
    }

    private List<DropEntry> readDrops(List<Map<?, ?>> rawDrops, String mobKey) {
        List<DropEntry> drops = new ArrayList<>();
        for (Map<?, ?> map : rawDrops) {
            Object materialValue = map.get("material");
            if (materialValue == null) {
                continue;
            }
            Material material;
            try {
                material = Material.valueOf(String.valueOf(materialValue).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown drop material '" + materialValue + "' for " + mobKey + " in spawners.yml");
                continue;
            }
            int min = asInt(map.get("min"), 0);
            int max = Math.max(min, asInt(map.get("max"), min));
            double chance = map.get("chance") instanceof Number number ? number.doubleValue() : 1.0;
            drops.add(new DropEntry(material, min, max, Math.max(0, Math.min(1, chance))));
        }
        return drops;
    }

    private static int asInt(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** Loads every persisted spawner from the database into memory. */
    public void loadSpawnersFromDatabase() {
        try {
            for (SpawnerStorage.StoredSpawner stored : storage.loadAll()) {
                World world = plugin.getServer().getWorld(stored.world());
                if (world == null) {
                    continue;
                }
                Location location = new Location(world, stored.x(), stored.y(), stored.z());
                SpawnerData data = new SpawnerData(stored.mobType(), stored.placedAtMillis(),
                        stored.ownerFactionTag());
                spawners.put(key(location), data);
                if (world.isChunkLoaded(stored.x() >> 4, stored.z() >> 4)) {
                    reconcileChunk(world.getChunkAt(stored.x() >> 4, stored.z() >> 4));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load spawners from the database.", e);
        }
    }

    public SpawnerData get(Location location) {
        return spawners.get(key(location));
    }

    public boolean isTracked(Location location) {
        return spawners.containsKey(key(location));
    }

    /**
     * Reconciles one loaded chunk's physical spawners against both PDC and
     * SQL. PDC is the recovery source when a background SQL write was
     * interrupted; SQL supplies PDC for older spawners placed before this
     * marker existed. Never call this for an unloaded chunk.
     */
    public void reconcileChunk(Chunk chunk) {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof CreatureSpawner spawner)) {
                continue;
            }
            Location location = spawner.getLocation();
            String locationKey = key(location);
            SpawnerData pdcData = readData(spawner.getPersistentDataContainer());
            SpawnerData known = spawners.get(locationKey);
            SpawnerData resolved = pdcData != null ? pdcData : known;
            if (resolved == null) {
                continue;
            }
            found.add(locationKey);
            spawners.put(locationKey, resolved);
            if (pdcData == null || !spawner.getPersistentDataContainer().has(placedAtDataKey, PersistentDataType.STRING)) {
                writeData(spawner.getPersistentDataContainer(), resolved);
                spawner.update(true, false);
            }
            applyTuning(location, resolved);
            if (known == null || pdcData != null) {
                persist(location, resolved);
            }
        }
        for (Location indexed : List.copyOf(locationsInChunk(chunk))) {
            if (!found.contains(key(indexed))) {
                remove(indexed);
            }
        }
    }

    private List<Location> locationsInChunk(Chunk chunk) {
        List<Location> result = new ArrayList<>();
        for (Location location : locations()) {
            if (location.getWorld().equals(chunk.getWorld())
                    && (location.getBlockX() >> 4) == chunk.getX()
                    && (location.getBlockZ() >> 4) == chunk.getZ()) {
                result.add(location);
            }
        }
        return result;
    }

    private List<Location> locations() {
        List<Location> result = new ArrayList<>();
        for (String locationKey : spawners.keySet()) {
            String[] parts = locationKey.split(":", 4);
            World world = plugin.getServer().getWorld(parts[0]);
            if (world != null) {
                result.add(new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3])));
            }
        }
        return result;
    }

    public MobConfig getMobConfig(EntityType type) {
        return mobConfigs.get(type);
    }

    public Collection<MobConfig> getMobConfigs() {
        return mobConfigs.values();
    }

    public int maxStackSize() {
        return maxStackSize;
    }

    public boolean isSilkTouchRequired() {
        return silkTouchRequired;
    }

    public BreakMode breakMode() {
        return breakMode;
    }

    public double sellRefundPercent() {
        return sellRefundPercent;
    }

    /** How far (in blocks) from a spawner block vanilla will actually spawn a mob. */
    public int spawnRangeBlocks() {
        return spawnRangeBlocks;
    }

    /** True when exposed spawners supplement vanilla spawning during the day. */
    /** Whether Vertex reinstates its own spawns that another plugin cancelled. */
    public boolean overrideOtherPlugins() {
        return overrideOtherPlugins;
    }

    public boolean spawnInDaylight() {
        return spawnInDaylight;
    }

    /** True when floorless grinders may use Vertex's in-air fallback spawns. */
    public boolean spawnInAir() {
        return spawnInAir;
    }

    public boolean isMobStackingEnabled() {
        return mobStackingEnabled;
    }

    public double mergeRadiusBlocks() {
        return mergeRadiusBlocks;
    }

    public int maxStackLimit() {
        return maxStackLimit;
    }

    public java.util.Set<EntityType> stackableTypes() {
        return stackableTypes;
    }

    public String stackDisplayFormat() {
        return stackDisplayFormat;
    }

    public int dropBatchSize() {
        return dropBatchSize;
    }

    /**
     * Registers a brand-new single-count spawner at the given location and
     * tunes its vanilla block state to match. The caller is responsible for
     * actually placing/confirming the block itself first.
     *
     * @param ownerFactionTag the placing player's faction tag, recorded so
     *                        an overclaim can tell "this land changed hands"
     *                        apart from "the same faction re-claimed it"
     */
    public void place(Location location, EntityType mobType, String ownerFactionTag) {
        SpawnerData data = new SpawnerData(mobType, 1, ownerFactionTag);
        spawners.put(key(location), data);
        nextManualSpawnTicks.remove(key(location));
        writeData(location, data);
        // Deferred a tick on purpose. place() runs inside BlockPlaceEvent, and
        // the server writes the item's own block-entity data to the new
        // spawner after the event returns -- so tuning applied here was being
        // overwritten by the item's defaults, leaving the spawner running at
        // vanilla rates (or worse, an unset type) no matter what was
        // configured. Tuning after the placement has settled is what actually
        // sticks.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (spawners.containsKey(key(location))) {
                applyTuning(location, data);
            }
        });
        persist(location, data);
    }

    /**
     * Transfers a placed spawner's faction ownership without changing its
     * type or stack size. Used when land is successfully overclaimed so the
     * new claim owner can manage the spawner and a later old-faction disband
     * cannot remove property from land it no longer owns.
     */
    public void transferOwnership(Location location, String ownerFactionTag) {
        SpawnerData current = get(location);
        if (current == null || ownerFactionTag == null || ownerFactionTag.isBlank()
                || ownerFactionTag.equalsIgnoreCase(current.ownerFactionTag())) {
            return;
        }
        SpawnerData transferred = new SpawnerData(current.mobType(), current.placedAtMillis(), ownerFactionTag);
        spawners.put(key(location), transferred);
        writeData(location, transferred);
        applyTuning(location, transferred);
        persist(location, transferred);
    }

    /**
     * @return the new stack size, capped at {@link #maxStackSize()}.
     */
    public int increaseStack(Location location, int amount) {
        SpawnerData data = spawners.get(key(location));
        if (data == null) {
            return 0;
        }
        int actuallyAdded = Math.min(Math.max(0, amount), maxStackSize - data.stackSize());
        data.addFresh(actuallyAdded);
        int newSize = data.stackSize();
        writeData(location, data);
        applyTuning(location, data);
        persist(location, data);
        return newSize;
    }

    /**
     * Lowers the stack by amount; if it reaches 0, the spawner is untracked
     * (and the caller should remove the physical block).
     *
     * @return the new stack size (0 meaning it was fully removed).
     */
    public int decreaseStack(Location location, int amount) {
        SpawnerData data = spawners.get(key(location));
        if (data == null) {
            return 0;
        }
        data.removeYoungest(amount);
        int newSize = data.stackSize();
        if (newSize <= 0) {
            remove(location);
            return 0;
        }
        writeData(location, data);
        applyTuning(location, data);
        persist(location, data);
        return newSize;
    }

    /** Untracks a spawner without touching the physical block. */
    public void remove(Location location) {
        String locationKey = key(location);
        if (spawners.remove(locationKey) == null) {
            return;
        }
        nextManualSpawnTicks.remove(locationKey);
        queue(location, () -> {
            try {
                storage.delete(location);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to delete spawner from the database.", e);
            }
        });
    }

    private SpawnerData readData(PersistentDataContainer pdc) {
        if (!pdc.has(markerKey, PersistentDataType.BYTE)) {
            return null;
        }
        String mobTypeName = pdc.get(mobTypeKey, PersistentDataType.STRING);
        EntityType mobType;
        try {
            mobType = mobTypeName == null ? null : EntityType.valueOf(mobTypeName);
        } catch (IllegalArgumentException ignored) {
            mobType = null;
        }
        if (mobType == null || mobType == EntityType.UNKNOWN) {
            return null;
        }
        int stackSize = Math.max(1, Math.min(maxStackSize,
                pdc.getOrDefault(stackSizeKey, PersistentDataType.INTEGER, 1)));
        return new SpawnerData(mobType, parseAges(pdc.get(placedAtDataKey, PersistentDataType.STRING), stackSize),
                pdc.get(ownerFactionKey, PersistentDataType.STRING));
    }

    private void writeData(Location location, SpawnerData data) {
        if (!(location.getBlock().getState() instanceof CreatureSpawner spawner)) {
            return;
        }
        writeData(spawner.getPersistentDataContainer(), data);
        spawner.update(true, false);
    }

    private void writeData(PersistentDataContainer pdc, SpawnerData data) {
        pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(mobTypeKey, PersistentDataType.STRING, data.mobType().name());
        pdc.set(stackSizeKey, PersistentDataType.INTEGER, data.stackSize());
        pdc.set(placedAtDataKey, PersistentDataType.STRING, encodeAges(data.placedAtMillis()));
        if (data.ownerFactionTag() == null || data.ownerFactionTag().isBlank()) {
            pdc.remove(ownerFactionKey);
        } else {
            pdc.set(ownerFactionKey, PersistentDataType.STRING, data.ownerFactionTag());
        }
    }

    private static String encodeAges(List<Long> ages) {
        return ages.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private static List<Long> parseAges(String encoded, int stackSize) {
        List<Long> ages = new ArrayList<>();
        if (encoded != null && !encoded.isBlank()) {
            for (String token : encoded.split(",")) {
                try {
                    ages.add(Long.parseLong(token));
                } catch (NumberFormatException ignored) {
                    // A malformed PDC value is repaired as a new spawner
                    // below, not trusted as an unknown historical age.
                }
            }
        }
        while (ages.size() < Math.max(1, stackSize)) {
            ages.add(System.currentTimeMillis());
        }
        if (ages.size() > Math.max(1, stackSize)) {
            return new ArrayList<>(ages.subList(0, Math.max(1, stackSize)));
        }
        return ages;
    }

    private void persist(Location location, SpawnerData data) {
        SpawnerData snapshot = new SpawnerData(data.mobType(), data.placedAtMillis(), data.ownerFactionTag());
        queue(location, () -> {
            try {
                storage.save(location, snapshot);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save spawner to the database.", e);
            }
        });
    }

    private void queue(Location location, Runnable operation) {
        String locationKey = key(location);
        CompletableFuture<Void> write = writeChains.compute(locationKey, (ignored, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((done, error) -> null))
                        .thenRunAsync(operation));
        track(write);
        write.whenComplete((ignored, error) -> writeChains.remove(locationKey, write));
    }

    private void track(CompletableFuture<Void> write) {
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
    }

    /**
     * Blocks briefly for any in-flight DB write to finish -- without this,
     * a place/upgrade/break right before a restart or reload could have its
     * write still in flight (or not yet even submitted) when the shared
     * connection pool is torn down moments later, silently losing that
     * spawner's last state change even though the in-memory map (and any
     * physical block change) already reflected it.
     */
    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            plugin.getLogger().warning("Timed out waiting for spawner writes during shutdown.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed while waiting for spawner writes.", e);
        }
    }

    /**
     * Scales the vanilla CreatureSpawner's own spawn-count/nearby-entity
     * cap by stack size -- lets vanilla's own spawn cycle do the work of
     * producing roughly N mobs per stack of N, rather than this plugin
     * manually spawning extras on every cycle.
     */
    public void applyTuning(Location location, SpawnerData data) {
        Block block = location.getBlock();
        if (block.getType() != Material.SPAWNER) {
            return;
        }
        BlockState state = block.getState();
        if (!(state instanceof CreatureSpawner spawner)) {
            return;
        }
        spawner.setSpawnedType(data.mobType());
        double rateMultiplier = factionRateMultiplier(location);
        int spawnCap = scaledLimit(maxSpawnCount, rateMultiplier);
        int nearbyCap = scaledLimit(maxNearbyEntitiesCap, rateMultiplier);
        spawner.setSpawnCount(Math.min(spawnCap, scaledCount(spawnCountPerStack * data.stackSize(), rateMultiplier)));
        spawner.setMaxNearbyEntities(Math.min(nearbyCap,
                scaledCount(maxNearbyEntitiesPerStack * data.stackSize(), rateMultiplier)));
        spawner.setMinSpawnDelay(minSpawnDelayTicks);
        spawner.setMaxSpawnDelay(maxSpawnDelayTicks);
        spawner.setRequiredPlayerRange(requiredPlayerRangeBlocks);
        spawner.setSpawnRange(spawnRangeBlocks);
        spawner.update(true, false);
    }

    /**
     * Entity types vanilla's own CreatureSpawner tick refuses to produce no
     * matter how its block state is tuned -- Iron Golems specifically have
     * a built-in spawn-rule predicate that a monster-spawner block can
     * never satisfy. They always use the manual fallback. Exposed spawners
     * additionally use it in daylight because hostile mobs reject vanilla's
     * bright-light spawn check even when they came from a player spawner.
     * A floorless grinder receives the same fallback, so its mobs can appear
     * in the air and fall rather than requiring an artificial platform.
     */
    private static final java.util.Set<EntityType> MANUAL_SPAWN_TYPES = java.util.Set.of(EntityType.IRON_GOLEM);

    /** Minecraft's full midday sky light is 15; 8+ reliably means daylight rather than moonlight. */
    private static final int DAYLIGHT_SKY_LIGHT = 8;

    /**
     * Supplements the vanilla spawn cycle for {@link #MANUAL_SPAWN_TYPES}
     * by spawning them directly, on a fixed schedule (see VertexPlugin),
     * respecting the same player-range/nearby-entity-cap/spawn-count
     * tuning as a real spawner would.
     */
    public void manualSpawnTick() {
        if (spawners.isEmpty()) {
            return;
        }
        long nowTicks = plugin.getServer().getCurrentTick();
        for (Map.Entry<String, SpawnerData> entry : spawners.entrySet()) {
            SpawnerData data = entry.getValue();
            String[] parts = entry.getKey().split(":", 4);
            World world = plugin.getServer().getWorld(parts[0]);
            if (world == null) {
                continue;
            }
            int blockX = Integer.parseInt(parts[1]);
            int blockY = Integer.parseInt(parts[2]);
            int blockZ = Integer.parseInt(parts[3]);
            if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                continue;
            }
            Location spawnerLocation = new Location(world, blockX, blockY, blockZ);
            if (spawnerLocation.getBlock().getType() != Material.SPAWNER) {
                continue;
            }
            boolean floorlessGrinder = spawnInAir && hasNoVanillaSpawnFloor(spawnerLocation);
            if (!MANUAL_SPAWN_TYPES.contains(data.mobType())
                    && !(spawnInDaylight && isExposedToDaylight(spawnerLocation))
                    && !floorlessGrinder) {
                continue;
            }
            if (!isManualSpawnDue(entry.getKey(), nowTicks)) {
                continue;
            }
            manualSpawnAt(world, spawnerLocation, data);
        }
    }

    /**
     * Keeps fallback spawns on the spawner's configured delay rather than
     * spawning another full batch every scheduler pass (which runs every five
     * seconds). The first fallback is immediate so an exposed spawner starts
     * working as soon as it becomes active.
     */
    private boolean isManualSpawnDue(String locationKey, long nowTicks) {
        Long next = nextManualSpawnTicks.get(locationKey);
        if (next != null && next > nowTicks) {
            return false;
        }
        int delayRange = maxSpawnDelayTicks - minSpawnDelayTicks;
        int delay = minSpawnDelayTicks + (delayRange > 0 ? random.nextInt(delayRange + 1) : 0);
        nextManualSpawnTicks.put(locationKey, nowTicks + delay);
        return true;
    }

    /**
     * Uses sky light instead of only world time, so a covered underground
     * spawner keeps vanilla's normal cycle while a genuinely sunlit one is
     * given the fallback it needs. This avoids doubling ordinary dark-room
     * spawner production during the overworld daytime.
     */
    private static boolean isExposedToDaylight(Location spawnerLocation) {
        return spawnerLocation.getBlock().getLightFromSky() >= DAYLIGHT_SKY_LIGHT;
    }

    /**
     * Vanilla mob spawners need a solid floor beneath a clear two-block-tall
     * position. When a grinder deliberately has none, use Vertex's direct
     * spawn path instead of leaving the cage permanently at delay zero.
     */
    private boolean hasNoVanillaSpawnFloor(Location spawnerLocation) {
        World world = spawnerLocation.getWorld();
        int centerX = spawnerLocation.getBlockX();
        int y = spawnerLocation.getBlockY();
        int centerZ = spawnerLocation.getBlockZ();
        for (int x = centerX - spawnRangeBlocks; x <= centerX + spawnRangeBlocks; x++) {
            for (int z = centerZ - spawnRangeBlocks; z <= centerZ + spawnRangeBlocks; z++) {
                Block ground = world.getBlockAt(x, y - 1, z);
                Block feet = world.getBlockAt(x, y, z);
                Block head = world.getBlockAt(x, y + 1, z);
                if (ground.getType().isSolid() && feet.isPassable() && head.isPassable()) {
                    return false;
                }
            }
        }
        return true;
    }

    private void manualSpawnAt(World world, Location spawnerLocation, SpawnerData data) {
        double rangeSquared = (double) requiredPlayerRangeBlocks * requiredPlayerRangeBlocks;
        boolean playerNearby = world.getPlayers().stream()
                .anyMatch(player -> player.getLocation().distanceSquared(spawnerLocation) <= rangeSquared);
        if (!playerNearby) {
            trace(spawnerLocation, "manual fallback skipped: no player within " + requiredPlayerRangeBlocks + " blocks");
            return;
        }

        int nearbyCount = 0;
        for (org.bukkit.entity.Entity nearby : world.getNearbyEntities(spawnerLocation,
                spawnRangeBlocks, spawnRangeBlocks, spawnRangeBlocks)) {
            if (nearby.getType() == data.mobType()) {
                nearbyCount++;
            }
        }
        double rateMultiplier = factionRateMultiplier(spawnerLocation);
        int nearbyCap = Math.min(scaledLimit(maxNearbyEntitiesCap, rateMultiplier),
                scaledCount(maxNearbyEntitiesPerStack * data.stackSize(), rateMultiplier));
        int toSpawn = Math.min(nearbyCap - nearbyCount, Math.min(scaledLimit(maxSpawnCount, rateMultiplier),
                scaledCount(spawnCountPerStack * data.stackSize(), rateMultiplier)));
        if (toSpawn <= 0) {
            trace(spawnerLocation, "manual fallback skipped: " + nearbyCount + " " + data.mobType()
                    + " nearby (cap " + nearbyCap + ")");
            return;
        }
        Class<? extends Entity> entityClass = data.mobType().getEntityClass();
        if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass)) {
            trace(spawnerLocation, "manual fallback skipped: " + data.mobType() + " is not a living spawnable entity");
            return;
        }
        @SuppressWarnings("unchecked")
        Class<? extends LivingEntity> livingEntityClass = (Class<? extends LivingEntity>) entityClass;
        int spawned = 0;
        int blockedLocations = 0;
        for (int i = 0; i < toSpawn; i++) {
            Location spawnAt = randomSpawnLocation(spawnerLocation);
            if (spawnAt == null) {
                blockedLocations++;
                continue;
            }
            world.spawn(spawnAt, livingEntityClass,
                    org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER, false, entity -> { });
            spawned++;
        }
        trace(spawnerLocation, "manual fallback requested " + toSpawn + " " + data.mobType() + "; created "
                + spawned + (blockedLocations == 0 ? "" : " (" + blockedLocations + " had no clear in-air space)"));
    }

    /**
     * A plain-text report of why a spawner is or is not producing mobs.
     *
     * <p>Every condition vanilla checks before a spawner fires is invisible
     * from in-game, so diagnosing a quiet spawner otherwise means guessing.
     * This reads the live block state rather than Vertex's own records, so a
     * spawner whose tuning never actually applied shows up as exactly that.
     */
    public List<String> describe(Location location) {
        List<String> report = new ArrayList<>();
        Block block = location.getBlock();
        if (block.getType() != Material.SPAWNER) {
            report.add("Not a spawner block (" + block.getType() + ").");
            return report;
        }
        SpawnerData data = get(location);
        report.add("Tracked by Vertex: " + (data != null
                ? "yes -- " + data.mobType() + " x" + data.stackSize() + ", owner " + data.ownerFactionTag()
                : "NO. It is an untracked vanilla spawner, so none of Vertex's tuning applies."));
        if (data != null && getMobConfig(data.mobType()) == null) {
            report.add("MISSING CONFIG: " + data.mobType() + " is not in spawners.yml mobs, so its custom drops are unavailable.");
        }

        if (!(block.getState() instanceof CreatureSpawner spawner)) {
            report.add("Block state is not a CreatureSpawner -- nothing can spawn.");
            return report;
        }
        EntityType spawned = spawner.getSpawnedType();
        report.add("Live spawned type: " + (spawned == null
                ? "NONE. An empty spawner never spawns anything." : spawned.name()));
        report.add("Live spawn count: " + spawner.getSpawnCount()
                + " | max nearby: " + spawner.getMaxNearbyEntities()
                + " | player range: " + spawner.getRequiredPlayerRange()
                + " | spawn range: " + spawner.getSpawnRange());
        report.add("Live delay: " + spawner.getDelay() + " ticks (min "
                + spawner.getMinSpawnDelay() + ", max " + spawner.getMaxSpawnDelay() + ")");

        if (data != null && spawned != data.mobType()) {
            report.add("MISMATCH: Vertex thinks this is " + data.mobType()
                    + " but the block says " + spawned + ". Its tuning did not apply.");
        }

        EntityType counted = spawned != null ? spawned : (data != null ? data.mobType() : null);
        if (counted != null) {
            int nearby = 0;
            for (org.bukkit.entity.Entity entity : location.getWorld().getNearbyEntities(location,
                    spawner.getSpawnRange(), spawner.getSpawnRange(), spawner.getSpawnRange())) {
                if (entity.getType() == counted) {
                    nearby++;
                }
            }
            report.add("Nearby " + counted + ": " + nearby + " of max " + spawner.getMaxNearbyEntities()
                    + (nearby >= spawner.getMaxNearbyEntities()
                            ? "  <-- AT THE CAP, so vanilla will not spawn more" : ""));
        }

        double nearestPlayer = Double.MAX_VALUE;
        for (org.bukkit.entity.Player player : location.getWorld().getPlayers()) {
            nearestPlayer = Math.min(nearestPlayer, player.getLocation().distance(location));
        }
        report.add("Nearest player: " + (nearestPlayer == Double.MAX_VALUE
                ? "none in this world" : String.format("%.1f", nearestPlayer) + " blocks")
                + " (needs to be within " + spawner.getRequiredPlayerRange() + ")");
        if (nearestPlayer > spawner.getRequiredPlayerRange()) {
            report.add("  <-- OUT OF RANGE, so vanilla will not spawn");
        }

        report.add("Sky light here: " + block.getLightFromSky()
                + (isExposedToDaylight(location)
                        ? " (daylight-exposed: uses Vertex's manual fallback)"
                        : " (dark: uses vanilla's own spawn cycle)"));
        if (data != null && (MANUAL_SPAWN_TYPES.contains(data.mobType()) || isExposedToDaylight(location)
                || (spawnInAir && hasNoVanillaSpawnFloor(location)))) {
            report.add("Manual fallback: active for this spawner; use /vertex spawnerdebug for its next attempt.");
        }
        report.add("Mob stacking: " + (mobStackingEnabled ? "on, merge radius "
                + mergeRadiusBlocks + " blocks -- spawns may merge into a distant stack" : "off"));
        return report;
    }

    /** Toggles a staff member's live trace for tracked spawners within 64 blocks. */
    public boolean toggleDebug(java.util.UUID playerUuid) {
        if (debugPlayers.remove(playerUuid)) {
            return false;
        }
        debugPlayers.add(playerUuid);
        return true;
    }

    /** Emits concise diagnostics only to staff who opted in and are near the affected spawner. */
    public void trace(Location location, String detail) {
        for (java.util.UUID uuid : debugPlayers) {
            org.bukkit.entity.Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || player.getWorld() != location.getWorld()
                    || player.getLocation().distanceSquared(location) > DEBUG_RANGE_BLOCKS * DEBUG_RANGE_BLOCKS) {
                continue;
            }
            player.sendMessage(net.kyori.adventure.text.Component.text("[Spawner Debug] " + detail,
                    net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
    }

    private double factionRateMultiplier(Location location) {
        FactionUpgradeManager upgrades = factionUpgradeManager;
        return upgrades == null ? 1D : Math.max(1D, upgrades.spawnerMultiplier(location));
    }

    /** Keeps the configured value as the baseline while allowing earned faction rate above it. */
    private static int scaledLimit(int configuredLimit, double multiplier) {
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, Math.ceil(configuredLimit * multiplier)));
    }

    private static int scaledCount(int baseline, double multiplier) {
        return Math.max(1, (int) Math.min(Integer.MAX_VALUE, Math.ceil(baseline * multiplier)));
    }

    /** A random clear two-block-high air position within range, or null if the cage is obstructed. */
    private Location randomSpawnLocation(Location spawnerLocation) {
        for (int attempt = 0; attempt < 12; attempt++) {
            double dx = (random.nextDouble() * 2 - 1) * spawnRangeBlocks;
            double dz = (random.nextDouble() * 2 - 1) * spawnRangeBlocks;
            Location candidate = spawnerLocation.clone().add(dx + 0.5, 0, dz + 0.5);
            Block feet = candidate.getBlock();
            Block head = candidate.clone().add(0, 1, 0).getBlock();
            if (feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The closest tracked spawner within radius blocks of a location, or
     * null if none -- used to identify which spawner produced a freshly
     * spawned mob, since vanilla spawns it at a random offset within the
     * spawner's own spawn-range, not at the block itself.
     */
    public Map.Entry<Location, SpawnerData> findNearby(Location location, double radius) {
        double radiusSquared = radius * radius;
        String worldName = location.getWorld().getName();
        Map.Entry<Location, SpawnerData> closest = null;
        double closestDistanceSquared = Double.MAX_VALUE;
        for (Map.Entry<String, SpawnerData> entry : spawners.entrySet()) {
            String[] parts = entry.getKey().split(":", 4);
            if (!parts[0].equals(worldName)) {
                continue;
            }
            Location candidate = new Location(location.getWorld(),
                    Integer.parseInt(parts[1]) + 0.5, Integer.parseInt(parts[2]) + 0.5, Integer.parseInt(parts[3]) + 0.5);
            double distanceSquared = candidate.distanceSquared(location);
            if (distanceSquared <= radiusSquared && distanceSquared < closestDistanceSquared) {
                closest = Map.entry(candidate, entry.getValue());
                closestDistanceSquared = distanceSquared;
            }
        }
        return closest;
    }

    /** Every tracked spawner whose block sits in this chunk. */
    public List<Map.Entry<Location, SpawnerData>> getSpawnersInChunk(Chunk chunk) {
        List<Map.Entry<Location, SpawnerData>> found = new ArrayList<>();
        String worldName = chunk.getWorld().getName();
        for (Map.Entry<String, SpawnerData> entry : spawners.entrySet()) {
            String[] parts = entry.getKey().split(":", 4);
            if (!parts[0].equals(worldName)) {
                continue;
            }
            int blockX = Integer.parseInt(parts[1]);
            int blockZ = Integer.parseInt(parts[3]);
            if ((blockX >> 4) == chunk.getX() && (blockZ >> 4) == chunk.getZ()) {
                World world = chunk.getWorld();
                int blockY = Integer.parseInt(parts[2]);
                found.add(Map.entry(new Location(world, blockX, blockY, blockZ), entry.getValue()));
            }
        }
        return found;
    }

    /**
     * Every tracked spawner originally placed by {@code factionTag}. This
     * avoids loading/scanning every claim during /f unclaimall or disband;
     * only chunks that actually contain that faction's spawners are touched.
     */
    public List<Map.Entry<Location, SpawnerData>> getSpawnersOwnedBy(String factionTag) {
        if (factionTag == null || factionTag.isBlank()) {
            return List.of();
        }
        List<Map.Entry<Location, SpawnerData>> found = new ArrayList<>();
        for (Map.Entry<String, SpawnerData> entry : spawners.entrySet()) {
            SpawnerData data = entry.getValue();
            if (data.ownerFactionTag() == null || !data.ownerFactionTag().equalsIgnoreCase(factionTag)) {
                continue;
            }
            String[] parts = entry.getKey().split(":", 4);
            World world = plugin.getServer().getWorld(parts[0]);
            if (world == null) {
                continue;
            }
            found.add(Map.entry(new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3])), data));
        }
        return found;
    }

    /**
     * Snapshot of every tracked stack, used only by bounded administration
     * tasks such as the ten-minute F Top validation. Callers must not mutate
     * the returned entries or use this in a tick loop.
     */
    public List<Map.Entry<Location, SpawnerData>> getAllSpawners() {
        List<Map.Entry<Location, SpawnerData>> found = new ArrayList<>();
        for (Map.Entry<String, SpawnerData> entry : spawners.entrySet()) {
            String[] parts = entry.getKey().split(":", 4);
            World world = plugin.getServer().getWorld(parts[0]);
            if (world == null) {
                continue;
            }
            found.add(Map.entry(new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3])), entry.getValue()));
        }
        return found;
    }

    /** Rolls this mob type's configured drop table; empty if none configured. */
    public List<ItemStack> rollDrops(EntityType type) {
        MobConfig config = mobConfigs.get(type);
        if (config == null || config.drops().isEmpty()) {
            return List.of();
        }
        List<ItemStack> drops = new ArrayList<>();
        for (DropEntry entry : config.drops()) {
            if (random.nextDouble() >= entry.chance()) {
                continue;
            }
            int amount = entry.min() + (entry.max() > entry.min() ? random.nextInt(entry.max() - entry.min() + 1) : 0);
            if (amount > 0) {
                drops.add(new ItemStack(entry.material(), amount));
            }
        }
        return drops;
    }

    /** A spawner item pre-configured for this mob type via vanilla block-state NBT. */
    public static ItemStack createSpawnerItem(EntityType type, net.kyori.adventure.text.Component displayName) {
        ItemStack item = new ItemStack(Material.SPAWNER);
        org.bukkit.inventory.meta.BlockStateMeta meta = (org.bukkit.inventory.meta.BlockStateMeta) item.getItemMeta();
        CreatureSpawner state = (CreatureSpawner) meta.getBlockState();
        state.setSpawnedType(type);
        meta.setBlockState(state);
        meta.displayName(displayName);
        item.setItemMeta(meta);
        return item;
    }

    /** The mob type an unplaced spawner item is configured for, or null if not a spawner item. */
    public static EntityType readSpawnedType(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER
                || !(item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta meta)) {
            return null;
        }
        if (!(meta.getBlockState() instanceof CreatureSpawner spawner)) {
            return null;
        }
        return spawner.getSpawnedType();
    }

    private static String key(Location location) {
        return key(location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public record MobConfig(EntityType mobType, String displayName, double price, List<DropEntry> drops) {
    }

    public record DropEntry(Material material, int min, int max, double chance) {
    }
}
