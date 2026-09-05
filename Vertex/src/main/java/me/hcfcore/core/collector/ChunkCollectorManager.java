package me.hcfcore.core.collector;

import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Owns every tracked Chunk Collector: config, the in-memory location index
 * (rediscovered from {@link ChunkCollectorStorage} on startup, since Bukkit
 * has no "list every placed block of type X" hook), and the PDC schema
 * shared between a collector's placed block and its dropped-item form --
 * the per-item-type storage counts and upgrade tier live entirely on PDC
 * (which already survives chunk save/load and item pickup/drop on its
 * own), so the index only needs to remember WHERE collectors are.
 */
public final class ChunkCollectorManager {

    private static final String STORED_PREFIX = "stored_";

    private final Plugin plugin;
    private final ChunkCollectorStorage storage;
    private final File file;
    private final NamespacedKey markerKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey ownerUuidKey;
    private final NamespacedKey ownerFactionKey;

    /** Location-key (see {@link #key(Location)}) -> the location itself. */
    private final Map<String, Location> collectors = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile boolean silkTouchRequired;
    private volatile int maxPerChunk;
    private volatile int maxPerPlayer;
    private volatile long baseCapacityPerType;
    private volatile long capacityPerUpgrade;
    private volatile int maxUpgradeTier;
    private volatile double upgradeCostBase;
    private volatile double upgradeCostMultiplier;
    private volatile int hopperBlockRadius;
    private volatile int scanIntervalTicks;

    public ChunkCollectorManager(Plugin plugin, ChunkCollectorStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "collectors.yml");
        this.markerKey = new NamespacedKey(plugin, "chunk_collector");
        this.tierKey = new NamespacedKey(plugin, "collector_tier");
        this.ownerUuidKey = new NamespacedKey(plugin, "collector_owner_uuid");
        this.ownerFactionKey = new NamespacedKey(plugin, "collector_owner_faction");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("collectors.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        silkTouchRequired = config.getBoolean("silk-touch-required", true);
        maxPerChunk = Math.max(1, config.getInt("max-per-chunk", 1));
        maxPerPlayer = Math.max(1, config.getInt("max-per-player", 3));
        baseCapacityPerType = Math.max(1, config.getLong("base-capacity-per-type", 50000));
        capacityPerUpgrade = Math.max(0, config.getLong("capacity-per-upgrade", 25000));
        maxUpgradeTier = Math.max(0, config.getInt("max-upgrade-tier", 5));
        upgradeCostBase = Math.max(0, config.getDouble("upgrade-cost-base", 100000.0));
        upgradeCostMultiplier = Math.max(1.0, config.getDouble("upgrade-cost-multiplier", 1.75));
        hopperBlockRadius = Math.max(0, config.getInt("hopper-block-radius", 2));
        scanIntervalTicks = Math.max(20, config.getInt("scan-interval-ticks", 100));
    }

    /** Rebuilds the in-memory location index from the database on startup. */
    public void loadIndexFromDatabase() {
        try {
            for (ChunkCollectorStorage.StoredCollector stored : storage.loadAll()) {
                World world = plugin.getServer().getWorld(stored.world());
                if (world == null) {
                    continue;
                }
                Location location = new Location(world, stored.x(), stored.y(), stored.z());
                collectors.put(key(location), location);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load chunk collectors from the database.", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isSilkTouchRequired() {
        return silkTouchRequired;
    }

    public int maxPerChunk() {
        return maxPerChunk;
    }

    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    public int maxUpgradeTier() {
        return maxUpgradeTier;
    }

    public int hopperBlockRadius() {
        return hopperBlockRadius;
    }

    public int scanIntervalTicks() {
        return scanIntervalTicks;
    }

    public long capacityFor(int upgradeTier) {
        return baseCapacityPerType + (long) upgradeTier * capacityPerUpgrade;
    }

    /** Cost to go from `currentTier` to `currentTier + 1`, or -1 if already at the max. */
    public double upgradeCost(int currentTier) {
        if (currentTier >= maxUpgradeTier) {
            return -1;
        }
        return upgradeCostBase * Math.pow(upgradeCostMultiplier, currentTier);
    }

    public boolean isTracked(Location location) {
        return collectors.containsKey(key(location));
    }

    /** A snapshot of every tracked collector's location, for the periodic fallback sweep. */
    public List<Location> allLocations() {
        return new ArrayList<>(collectors.values());
    }

    public int countInChunk(Chunk chunk) {
        int count = 0;
        for (Location location : collectors.values()) {
            if (inChunk(location, chunk)) {
                count++;
            }
        }
        return count;
    }

    public int countForOwner(UUID ownerUuid) {
        int count = 0;
        for (Location location : collectors.values()) {
            ChunkCollectorData data = readData(location);
            if (data != null && data.ownerUuid().equals(ownerUuid)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Every tracked collector in `chunk` whose Y level is at or below
     * `maxY`, closest (highest Y) first -- used to find which collector a
     * dropped item strictly above it should fall into.
     */
    public List<Location> collectorsBelow(Chunk chunk, double maxY) {
        List<Location> found = new ArrayList<>();
        for (Location location : collectors.values()) {
            if (inChunk(location, chunk) && location.getBlockY() < maxY) {
                found.add(location);
            }
        }
        found.sort((a, b) -> Integer.compare(b.getBlockY(), a.getBlockY()));
        return found;
    }

    /** Every tracked collector within `radius` blocks (any direction) of `center`. */
    public List<Location> collectorsNear(Location center, int radius) {
        List<Location> found = new ArrayList<>();
        for (Location location : collectors.values()) {
            if (!location.getWorld().equals(center.getWorld())) {
                continue;
            }
            if (Math.abs(location.getBlockX() - center.getBlockX()) <= radius
                    && Math.abs(location.getBlockY() - center.getBlockY()) <= radius
                    && Math.abs(location.getBlockZ() - center.getBlockZ()) <= radius) {
                found.add(location);
            }
        }
        return found;
    }

    private static boolean inChunk(Location location, Chunk chunk) {
        return location.getWorld().equals(chunk.getWorld())
                && (location.getBlockX() >> 4) == chunk.getX()
                && (location.getBlockZ() >> 4) == chunk.getZ();
    }

    /** Registers a newly-placed collector at `location`, applying `data` to the block's PDC. */
    public void register(Location location, ChunkCollectorData data) {
        collectors.put(key(location), location);
        writeData(location, data);
        persist(location, data);
    }

    /** Untracks a collector without touching the physical block. */
    public void unregister(Location location) {
        if (collectors.remove(key(location)) == null) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                storage.delete(location);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to delete chunk collector from the database.", e);
            }
        });
    }

    private void persist(Location location, ChunkCollectorData data) {
        String ownerFaction = data.ownerFactionTag();
        String ownerUuid = data.ownerUuid().toString();
        CompletableFuture.runAsync(() -> {
            try {
                storage.save(location, ownerFaction, ownerUuid);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save chunk collector to the database.", e);
            }
        });
    }

    /** Reads a tracked collector's live state off its block's PDC, or null if the block isn't actually a collector right now. */
    public ChunkCollectorData readData(Location location) {
        Block block = location.getBlock();
        if (!(block.getState() instanceof ShulkerBox shulkerBox)) {
            return null;
        }
        return readData(shulkerBox.getPersistentDataContainer());
    }

    private ChunkCollectorData readData(PersistentDataContainer pdc) {
        if (!pdc.has(markerKey, PersistentDataType.BYTE)) {
            return null;
        }
        int tier = pdc.getOrDefault(tierKey, PersistentDataType.INTEGER, 0);
        String ownerUuidRaw = pdc.get(ownerUuidKey, PersistentDataType.STRING);
        UUID ownerUuid = ownerUuidRaw != null ? UUID.fromString(ownerUuidRaw) : new UUID(0, 0);
        String ownerFaction = pdc.get(ownerFactionKey, PersistentDataType.STRING);
        ChunkCollectorData data = new ChunkCollectorData(tier, ownerUuid, ownerFaction);
        for (NamespacedKey namespacedKey : pdc.getKeys()) {
            if (!namespacedKey.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))
                    || !namespacedKey.getKey().startsWith(STORED_PREFIX)) {
                continue;
            }
            String materialName = namespacedKey.getKey().substring(STORED_PREFIX.length()).toUpperCase(Locale.ROOT);
            Material material;
            try {
                material = Material.valueOf(materialName);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Long amount = pdc.get(namespacedKey, PersistentDataType.LONG);
            if (amount != null && amount > 0) {
                data.setStored(material, amount);
            }
        }
        return data;
    }

    /** Writes `data` onto the block currently at `location`, if it's actually a shulker box. */
    public void writeData(Location location, ChunkCollectorData data) {
        Block block = location.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof ShulkerBox shulkerBox)) {
            return;
        }
        writeData(shulkerBox.getPersistentDataContainer(), data);
        shulkerBox.update(true, false);
    }

    private void writeData(PersistentDataContainer pdc, ChunkCollectorData data) {
        pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(tierKey, PersistentDataType.INTEGER, data.upgradeTier());
        pdc.set(ownerUuidKey, PersistentDataType.STRING, data.ownerUuid().toString());
        if (data.ownerFactionTag() != null) {
            pdc.set(ownerFactionKey, PersistentDataType.STRING, data.ownerFactionTag());
        }
        for (NamespacedKey existing : List.copyOf(pdc.getKeys())) {
            if (existing.getKey().startsWith(STORED_PREFIX)) {
                pdc.remove(existing);
            }
        }
        for (Map.Entry<Material, Long> entry : data.stored().entrySet()) {
            pdc.set(new NamespacedKey(plugin, STORED_PREFIX + entry.getKey().name().toLowerCase(Locale.ROOT)),
                    PersistentDataType.LONG, entry.getValue());
        }
    }

    /** Whether `item` is a marked Chunk Collector (rather than a plain green shulker box). */
    public boolean isCollectorItem(ItemStack item) {
        if (item == null || item.getType() != Material.GREEN_SHULKER_BOX) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /** The state carried by a Chunk Collector item (from its BlockStateMeta PDC), or a fresh empty one. */
    public ChunkCollectorData readItemData(ItemStack item, UUID fallbackOwner, String fallbackFaction) {
        if (item.getItemMeta() instanceof BlockStateMeta blockStateMeta
                && blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox) {
            ChunkCollectorData fromBlockState = readData(shulkerBox.getPersistentDataContainer());
            if (fromBlockState != null) {
                return fromBlockState;
            }
        }
        return new ChunkCollectorData(0, fallbackOwner, fallbackFaction);
    }

    /** A fresh, unmarked-in-storage Green Shulker Box item, tagged so onPlace() recognizes it as a Chunk Collector. */
    public ItemStack createCollectorItem(Component displayName, ChunkCollectorData data) {
        ItemStack item = new ItemStack(Material.GREEN_SHULKER_BOX);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        meta.displayName(displayName);
        item.setItemMeta(meta);
        if (item.getItemMeta() instanceof BlockStateMeta blockStateMeta) {
            BlockState state = blockStateMeta.getBlockState();
            if (state instanceof ShulkerBox shulkerBox) {
                writeData(shulkerBox.getPersistentDataContainer(), data);
                blockStateMeta.setBlockState(shulkerBox);
                blockStateMeta.displayName(displayName);
                item.setItemMeta(blockStateMeta);
            }
        }
        return item;
    }

    private static String key(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }
}
