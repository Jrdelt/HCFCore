package me.hcfcore.core.spawner;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
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
import java.util.logging.Level;

/**
 * Owns every tracked (placed by a player, HCFCore-managed) spawner: its
 * in-memory registry, spawners.yml config (per-mob price/drops, global
 * stacking rules), and the vanilla CreatureSpawner tuning that makes a
 * stack of N actually spawn roughly N mobs per cycle instead of relying on
 * a manual re-spawn hook.
 */
public final class SpawnerManager {

    public enum BreakMode { DROP_ALL, DECREMENT }

    private final Plugin plugin;
    private final SpawnerStorage storage;
    private final File file;
    private final Random random = new Random();
    private final Map<String, SpawnerData> spawners = new ConcurrentHashMap<>();

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
    private volatile Map<EntityType, MobConfig> mobConfigs = Map.of();

    private volatile boolean mobStackingEnabled;
    private volatile double mergeRadiusBlocks;
    private volatile int maxStackLimit;
    private volatile java.util.Set<EntityType> stackableTypes = java.util.Set.of();
    private volatile String stackDisplayFormat;
    private volatile int dropBatchSize;

    public SpawnerManager(Plugin plugin, SpawnerStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "spawners.yml");
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
        sellRefundPercent = Math.max(0, config.getDouble("sell-refund-percent", 50));

        spawnCountPerStack = Math.max(1, config.getInt("spawn-count-per-stack", 1));
        maxSpawnCount = Math.max(1, config.getInt("max-spawn-count", 8));
        maxNearbyEntitiesPerStack = Math.max(1, config.getInt("max-nearby-entities-per-stack", 2));
        maxNearbyEntitiesCap = Math.max(1, config.getInt("max-nearby-entities-cap", 32));
        minSpawnDelayTicks = Math.max(1, config.getInt("min-spawn-delay-ticks", 200));
        maxSpawnDelayTicks = Math.max(minSpawnDelayTicks, config.getInt("max-spawn-delay-ticks", 400));
        requiredPlayerRangeBlocks = Math.max(1, config.getInt("required-player-range-blocks", 32));
        spawnRangeBlocks = Math.max(1, config.getInt("spawn-range-blocks", 4));

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
        // later /hcfcore reload, this is what actually makes changed
        // tuning values (spawn-count-per-stack etc.) apply to spawners
        // that already exist instead of only ones stacked/unstacked after.
        retuneAll();
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
        if (mobsSection == null) {
            return result;
        }
        for (String key : mobsSection.getKeys(false)) {
            EntityType type;
            try {
                type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Unknown entity type '" + key + "' in spawners.yml");
                continue;
            }
            ConfigurationSection section = mobsSection.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String displayName = section.getString("display-name", key);
            double price = section.getDouble("price", 0);
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
                spawners.put(key(stored.world(), stored.x(), stored.y(), stored.z()),
                        new SpawnerData(stored.mobType(), stored.stackSize(), stored.ownerFactionTag()));
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
        applyTuning(location, data);
        persist(location, data);
    }

    /**
     * @return the new stack size, capped at {@link #maxStackSize()}.
     */
    public int increaseStack(Location location, int amount) {
        SpawnerData data = spawners.get(key(location));
        if (data == null) {
            return 0;
        }
        int newSize = Math.min(maxStackSize, data.stackSize() + Math.max(0, amount));
        data.setStackSize(newSize);
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
        int newSize = Math.max(0, data.stackSize() - Math.max(0, amount));
        if (newSize <= 0) {
            remove(location);
            return 0;
        }
        data.setStackSize(newSize);
        applyTuning(location, data);
        persist(location, data);
        return newSize;
    }

    /** Untracks a spawner without touching the physical block. */
    public void remove(Location location) {
        if (spawners.remove(key(location)) == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                storage.delete(location);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to delete spawner from the database.", e);
            }
        });
    }

    private void persist(Location location, SpawnerData data) {
        CompletableFuture.runAsync(() -> {
            try {
                storage.save(location, data);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save spawner to the database.", e);
            }
        });
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
        spawner.setSpawnCount(Math.min(maxSpawnCount, spawnCountPerStack * data.stackSize()));
        spawner.setMaxNearbyEntities(Math.min(maxNearbyEntitiesCap, maxNearbyEntitiesPerStack * data.stackSize()));
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
     * never satisfy (a well-known vanilla limitation, not specific to this
     * plugin). These get spawned directly by manualSpawnTick() instead,
     * tagged with SpawnReason.SPAWNER so SpawnerMobListener/MobStackListener
     * treat them exactly like any other tracked-spawner mob.
     */
    private static final java.util.Set<EntityType> MANUAL_SPAWN_TYPES = java.util.Set.of(EntityType.IRON_GOLEM);

    /**
     * Supplements the vanilla spawn cycle for {@link #MANUAL_SPAWN_TYPES}
     * by spawning them directly, on a fixed schedule (see HCFCorePlugin),
     * respecting the same player-range/nearby-entity-cap/spawn-count
     * tuning as a real spawner would.
     */
    public void manualSpawnTick() {
        if (spawners.isEmpty()) {
            return;
        }
        for (Map.Entry<String, SpawnerData> entry : spawners.entrySet()) {
            SpawnerData data = entry.getValue();
            if (!MANUAL_SPAWN_TYPES.contains(data.mobType())) {
                continue;
            }
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
            manualSpawnAt(world, spawnerLocation, data);
        }
    }

    private void manualSpawnAt(World world, Location spawnerLocation, SpawnerData data) {
        double rangeSquared = (double) requiredPlayerRangeBlocks * requiredPlayerRangeBlocks;
        boolean playerNearby = world.getPlayers().stream()
                .anyMatch(player -> player.getLocation().distanceSquared(spawnerLocation) <= rangeSquared);
        if (!playerNearby) {
            return;
        }

        int nearbyCount = 0;
        for (org.bukkit.entity.Entity nearby : world.getNearbyEntities(spawnerLocation,
                spawnRangeBlocks, spawnRangeBlocks, spawnRangeBlocks)) {
            if (nearby.getType() == data.mobType()) {
                nearbyCount++;
            }
        }
        int nearbyCap = Math.min(maxNearbyEntitiesCap, maxNearbyEntitiesPerStack * data.stackSize());
        int toSpawn = Math.min(nearbyCap - nearbyCount, Math.min(maxSpawnCount, spawnCountPerStack * data.stackSize()));
        for (int i = 0; i < toSpawn; i++) {
            Location spawnAt = randomSpawnLocation(spawnerLocation);
            if (spawnAt == null) {
                continue;
            }
            world.spawn(spawnAt, org.bukkit.entity.IronGolem.class,
                    org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER, false, entity -> { });
        }
    }

    /** A random offset within spawn-range-blocks that has solid ground and headroom, or null if none found nearby. */
    private Location randomSpawnLocation(Location spawnerLocation) {
        for (int attempt = 0; attempt < 4; attempt++) {
            double dx = (random.nextDouble() * 2 - 1) * spawnRangeBlocks;
            double dz = (random.nextDouble() * 2 - 1) * spawnRangeBlocks;
            Location candidate = spawnerLocation.clone().add(dx + 0.5, 0, dz + 0.5);
            Block ground = candidate.clone().add(0, -1, 0).getBlock();
            Block feet = candidate.getBlock();
            Block head = candidate.clone().add(0, 1, 0).getBlock();
            if (ground.getType().isSolid() && !feet.getType().isSolid() && !head.getType().isSolid()) {
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
