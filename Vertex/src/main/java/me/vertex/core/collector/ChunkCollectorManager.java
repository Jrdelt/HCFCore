package me.vertex.core.collector;

import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
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
    /** Prevent malformed configuration/PDC data from overflowing capacity arithmetic. */
    private static final long MAX_CAPACITY = 1_000_000_000L;
    private static final int MAX_UPGRADE_TIER = 100;
    /** Fixed, deliberate cap: the menu exposes all 24 possible types. */
    private static final int MAX_STORED_MATERIAL_TYPES = 24;

    private final Plugin plugin;
    private final ChunkCollectorStorage storage;
    private final File file;
    private final Messages messages;
    private final NamespacedKey markerKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey ownerUuidKey;
    private final NamespacedKey ownerFactionKey;

    /** Location-key (see {@link #key(Location)}) -> the location itself. */
    private final Map<String, Location> collectors = new ConcurrentHashMap<>();
    /** Chunk location -> collector location keys, keeping farm drop lookups local. */
    private final Map<String, java.util.Set<String>> collectorsByChunk = new ConcurrentHashMap<>();
    /** Owner -> location keys.  This keeps limit checks from synchronously loading chunks. */
    private final Map<UUID, java.util.Set<String>> collectorsByOwner = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<Void>> pendingWrites = ConcurrentHashMap.newKeySet();
    /** Serializes mutations for each collector location while retaining parallel DB work elsewhere. */
    private final Map<String, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile int maxPerChunk;
    private volatile int maxPerPlayer;
    private volatile long baseCapacity;
    private volatile long capacityPerUpgrade;
    private volatile int maxUpgradeTier;
    private volatile int shiftWithdrawAmount;
    private volatile double upgradeCostBase;
    private volatile double upgradeCostMultiplier;
    private volatile int hopperBlockRadius;
    private volatile int scanIntervalTicks;
    private volatile int maxStoredMaterialTypes;

    public ChunkCollectorManager(Plugin plugin, ChunkCollectorStorage storage, Messages messages) {
        this.plugin = plugin;
        this.storage = storage;
        this.messages = messages;
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
        maxPerChunk = Math.max(1, config.getInt("max-per-chunk", 1));
        maxPerPlayer = Math.max(1, config.getInt("max-per-player", 3));
        // `base-capacity-per-type` was the old setting. Retain it as a
        // fallback for existing servers while new installs use shared
        // `base-capacity` across the entire collector.
        baseCapacity = boundedCapacity(config.contains("base-capacity")
                ? config.getLong("base-capacity")
                : config.getLong("base-capacity-per-type", 50000), 1);
        capacityPerUpgrade = boundedCapacity(config.getLong("capacity-per-upgrade", 25000), 0);
        maxUpgradeTier = Math.max(0, Math.min(MAX_UPGRADE_TIER, config.getInt("max-upgrade-tier", 5)));
        shiftWithdrawAmount = Math.max(1, config.getInt("shift-withdraw-amount", 64));
        upgradeCostBase = Math.max(0, config.getDouble("upgrade-cost-base", 100000.0));
        upgradeCostMultiplier = Math.max(1.0, config.getDouble("upgrade-cost-multiplier", 1.75));
        hopperBlockRadius = Math.max(0, config.getInt("hopper-block-radius", 2));
        scanIntervalTicks = Math.max(20, config.getInt("scan-interval-ticks", 100));
        // This is intentionally fixed rather than configurable: accepting
        // types the GUI cannot show would strand items and excessive PDC
        // entries make high-volume farms needlessly expensive to save.
        maxStoredMaterialTypes = MAX_STORED_MATERIAL_TYPES;
    }

    /** The item name is localized with the recipient's configured language. */
    public Component displayName(CommandSender recipient) {
        return messages.get(recipient, "collector.item-name");
    }

    Plugin plugin() {
        return plugin;
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
                UUID owner;
                try {
                    owner = UUID.fromString(stored.ownerUuid());
                } catch (IllegalArgumentException ignored) {
                    owner = new UUID(0L, 0L);
                }
                index(location, owner);
                if (world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    reconcileChunk(world.getChunkAt(location));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load chunk collectors from the database.", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
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

    /** Amount taken by a shift-click in the collector inventory. */
    public int shiftWithdrawAmount() {
        return shiftWithdrawAmount;
    }

    public int hopperBlockRadius() {
        return hopperBlockRadius;
    }

    public int scanIntervalTicks() {
        return scanIntervalTicks;
    }

    /** Maximum distinct materials a collector may remember (prevents PDC bloat). */
    public int maxStoredMaterialTypes() {
        return maxStoredMaterialTypes;
    }

    public long capacityFor(int upgradeTier) {
        int tier = Math.max(0, Math.min(maxUpgradeTier, upgradeTier));
        if (capacityPerUpgrade == 0 || tier == 0 || baseCapacity >= MAX_CAPACITY) {
            return baseCapacity;
        }
        long additional = capacityPerUpgrade > (MAX_CAPACITY - baseCapacity) / tier
                ? MAX_CAPACITY - baseCapacity
                : capacityPerUpgrade * tier;
        return Math.min(MAX_CAPACITY, baseCapacity + additional);
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

    /**
     * Reconciles one loaded chunk with the persistent data on its Shulker
     * Boxes. This removes stale SQL rows and recovers a valid collector whose
     * asynchronous database write was interrupted by a crash.
     */
    public void reconcileChunk(Chunk chunk) {
        java.util.Set<String> found = new java.util.HashSet<>();
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof ShulkerBox shulkerBox)) {
                continue;
            }
            ChunkCollectorData data = readData(shulkerBox.getPersistentDataContainer());
            if (data == null) {
                continue;
            }
            Location location = shulkerBox.getLocation();
            String locationKey = key(location);
            found.add(locationKey);
            if (collectors.putIfAbsent(locationKey, location) == null) {
                index(location, data.ownerUuid());
                persist(location, data);
            } else {
                reindexOwner(location, data.ownerUuid());
            }
        }
        for (Location indexed : locationsInChunk(chunk)) {
            if (inChunk(indexed, chunk) && !found.contains(key(indexed))) {
                unregister(indexed);
            }
        }
    }

    /** A snapshot of every tracked collector's location, for the periodic fallback sweep. */
    public List<Location> allLocations() {
        return new ArrayList<>(collectors.values());
    }

    public int countInChunk(Chunk chunk) {
        return collectorsByChunk.getOrDefault(chunkKey(chunk), java.util.Set.of()).size();
    }

    public int countForOwner(UUID ownerUuid) {
        return collectorsByOwner.getOrDefault(ownerUuid, java.util.Set.of()).size();
    }

    /** A material already held is always allowed; a new type respects the PDC safety cap. */
    public boolean canStore(ChunkCollectorData data, Material material) {
        return data.stored(material) > 0L || data.stored().size() < maxStoredMaterialTypes;
    }

    /** Returns a copy preserving stored contents but assigning the current placement owner. */
    public ChunkCollectorData withOwner(ChunkCollectorData source, UUID ownerUuid, String ownerFactionTag) {
        ChunkCollectorData reassigned = new ChunkCollectorData(source.upgradeTier(), ownerUuid, ownerFactionTag);
        source.stored().forEach(reassigned::setStored);
        return reassigned;
    }

    /** Transfers faction ownership after an overclaim without touching contents or tier. */
    public void transferFactionOwnership(Location location, String ownerFactionTag) {
        if (ownerFactionTag == null || ownerFactionTag.isBlank()) {
            return;
        }
        ChunkCollectorData current = readData(location);
        if (current == null || ownerFactionTag.equalsIgnoreCase(current.ownerFactionTag())) {
            return;
        }
        ChunkCollectorData transferred = withOwner(current, current.ownerUuid(), ownerFactionTag);
        writeData(location, transferred);
        persist(location, transferred);
        reindexOwner(location, transferred.ownerUuid());
    }

    public List<Map.Entry<Location, ChunkCollectorData>> getCollectorsInChunk(Chunk chunk) {
        return getCollectorsInChunk(chunk.getWorld(),chunk.getX(),chunk.getZ());
    }

    /** Indexed lookup by coordinates; does not synchronously load the chunk. */
    public List<Map.Entry<Location, ChunkCollectorData>> getCollectorsInChunk(World world,int chunkX,int chunkZ) {
        List<Map.Entry<Location, ChunkCollectorData>> found = new ArrayList<>();
        for (Location location : locationsInChunk(world,chunkX,chunkZ)) {
            ChunkCollectorData data = readData(location);
            if (data != null) {
                found.add(Map.entry(location, data));
            }
        }
        return found;
    }

    public boolean hasCollectorInChunk(World world,int chunkX,int chunkZ){return !collectorsByChunk.getOrDefault(chunkKey(world,chunkX,chunkZ),java.util.Set.of()).isEmpty();}
    public boolean queueOverflow(Player player,java.util.Collection<ItemStack> items,String source){return me.vertex.core.storage.DeliveryManager.queueOverflow(plugin,player,items,source);}

    /** Uses the index, not a full claim scan, to avoid loading empty claimed chunks. */
    public List<Map.Entry<Location, ChunkCollectorData>> getCollectorsOwnedBy(String factionTag) {
        if (factionTag == null) {
            return List.of();
        }
        List<Map.Entry<Location, ChunkCollectorData>> found = new ArrayList<>();
        for (Location location : collectors.values()) {
            ChunkCollectorData data = readData(location);
            if (data != null && factionTag.equalsIgnoreCase(data.ownerFactionTag())) {
                found.add(Map.entry(location, data));
            }
        }
        return found;
    }

    /**
     * Every tracked collector in `chunk` whose Y level is at or below
     * `maxY`, closest (highest Y) first -- used to find which collector a
     * dropped item strictly above it should fall into.
     */
    public List<Location> collectorsBelow(Chunk chunk, double maxY) {
        List<Location> found = new ArrayList<>();
        for (Location location : locationsInChunk(chunk)) {
            if (location.getBlockY() < maxY) {
                found.add(location);
            }
        }
        found.sort((a, b) -> Integer.compare(b.getBlockY(), a.getBlockY()));
        return found;
    }

    /** Every tracked collector within `radius` blocks (any direction) of `center`. */
    public List<Location> collectorsNear(Location center, int radius) {
        List<Location> found = new ArrayList<>();
        if (center == null || center.getWorld() == null) {
            return found;
        }
        int minChunkX = (center.getBlockX() - radius) >> 4;
        int maxChunkX = (center.getBlockX() + radius) >> 4;
        int minChunkZ = (center.getBlockZ() - radius) >> 4;
        int maxChunkZ = (center.getBlockZ() + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (String locationKey : collectorsByChunk.getOrDefault(chunkKey(center.getWorld(), chunkX, chunkZ),
                        java.util.Set.of())) {
                    Location location = collectors.get(locationKey);
                    if (location != null && Math.abs(location.getBlockX() - center.getBlockX()) <= radius
                            && Math.abs(location.getBlockY() - center.getBlockY()) <= radius
                            && Math.abs(location.getBlockZ() - center.getBlockZ()) <= radius) {
                        found.add(location);
                    }
                }
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
        index(location, data.ownerUuid());
        writeData(location, data);
        persist(location, data);
    }

    /** Untracks a collector without touching the physical block. */
    public void unregister(Location location) {
        String locationKey = key(location);
        if (collectors.remove(locationKey) == null) {
            return;
        }
        collectorsByChunk.computeIfPresent(chunkKey(location), (ignored, locations) -> {
            locations.remove(locationKey);
            return locations.isEmpty() ? null : locations;
        });
        collectorsByOwner.values().forEach(locations -> locations.remove(locationKey));
        queue(location, () -> {
            try {
                me.vertex.core.storage.SqlRetry.run(plugin, "Chunk Collector delete at " + key(location),
                        () -> storage.delete(location));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to delete chunk collector from the database after retries.", e);
            }
        });
    }

    private void persist(Location location, ChunkCollectorData data) {
        String ownerFaction = data.ownerFactionTag();
        String ownerUuid = data.ownerUuid().toString();
        queue(location, () -> {
            try {
                me.vertex.core.storage.SqlRetry.run(plugin, "Chunk Collector save at " + key(location),
                        () -> storage.save(location, ownerFaction, ownerUuid));
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to save chunk collector to the database after retries.", e);
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
     * collector's last state change even though the in-memory index (and
     * any physical block change) already reflected it.
     */
    public void awaitWrites() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        int stableEmptyRounds = 0;
        while (System.nanoTime() < deadline && stableEmptyRounds < 3) {
            CompletableFuture<?>[] snapshot = pendingWrites.toArray(new CompletableFuture[0]);
            if (snapshot.length == 0) {
                stableEmptyRounds++;
                Thread.onSpinWait();
                continue;
            }
            stableEmptyRounds = 0;
            try {
                CompletableFuture.allOf(snapshot).get(
                        Math.max(1L, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.TimeoutException e) {
                break;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed while waiting for chunk collector writes.", e);
            }
        }
        if (!pendingWrites.isEmpty()) {
            plugin.getLogger().warning("Timed out waiting for chunk collector writes during shutdown.");
        }
    }

    /** Reads a tracked collector's live state off its block's PDC, or null if the block isn't actually a collector right now. */
    public ChunkCollectorData readData(Location location) {
        Block block = location.getBlock();
        if (!(block.getState() instanceof ShulkerBox shulkerBox)) {
            unregister(location);
            return null;
        }
        ChunkCollectorData data = readData(shulkerBox.getPersistentDataContainer());
        if (data == null) {
            unregister(location);
        }
        return data;
    }

    private ChunkCollectorData readData(PersistentDataContainer pdc) {
        if (!pdc.has(markerKey, PersistentDataType.BYTE)) {
            return null;
        }
        int tier = Math.max(0, Math.min(maxUpgradeTier,
                pdc.getOrDefault(tierKey, PersistentDataType.INTEGER, 0)));
        String ownerUuidRaw = pdc.get(ownerUuidKey, PersistentDataType.STRING);
        UUID ownerUuid;
        try {
            ownerUuid = ownerUuidRaw != null ? UUID.fromString(ownerUuidRaw) : new UUID(0, 0);
        } catch (IllegalArgumentException ignored) {
            // A damaged legacy PDC value must not make every interaction or
            // collector scan throw; treat it as an unknown legacy owner.
            ownerUuid = new UUID(0, 0);
        }
        String ownerFaction = pdc.get(ownerFactionKey, PersistentDataType.STRING);
        ChunkCollectorData data = new ChunkCollectorData(tier, ownerUuid, ownerFaction);
        long remainingCapacity = capacityFor(tier);
        int storedTypes = 0;
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
            if (amount != null && amount > 0 && remainingCapacity > 0 && storedTypes < maxStoredMaterialTypes) {
                long accepted = Math.min(amount, remainingCapacity);
                data.setStored(material, accepted);
                remainingCapacity -= accepted;
                storedTypes++;
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
        pdc.set(tierKey, PersistentDataType.INTEGER,
                Math.max(0, Math.min(maxUpgradeTier, data.upgradeTier())));
        pdc.set(ownerUuidKey, PersistentDataType.STRING, data.ownerUuid().toString());
        if (data.ownerFactionTag() != null) {
            pdc.set(ownerFactionKey, PersistentDataType.STRING, data.ownerFactionTag());
        } else {
            pdc.remove(ownerFactionKey);
        }
        for (NamespacedKey existing : List.copyOf(pdc.getKeys())) {
            if (existing.getNamespace().equals(plugin.getName().toLowerCase(Locale.ROOT))
                    && existing.getKey().startsWith(STORED_PREFIX)) {
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
        meta.lore(itemLore(data));
        item.setItemMeta(meta);
        if (item.getItemMeta() instanceof BlockStateMeta blockStateMeta) {
            BlockState state = blockStateMeta.getBlockState();
            if (state instanceof ShulkerBox shulkerBox) {
                writeData(shulkerBox.getPersistentDataContainer(), data);
                blockStateMeta.setBlockState(shulkerBox);
                blockStateMeta.displayName(displayName);
                blockStateMeta.lore(itemLore(data));
                item.setItemMeta(blockStateMeta);
            }
        }
        return item;
    }

    /**
     * An item has no placed-world owner or faction. Its lore therefore shows
     * only portable collector state: level, stored contents, capacity, and
     * upgrade headroom. The placed collector GUI remains the detailed view.
     */
    private List<Component> itemLore(ChunkCollectorData data) {
        int tier = Math.max(0, Math.min(maxUpgradeTier, data.upgradeTier()));
        long capacity = capacityFor(tier);
        long stored = data.totalStored();
        int types = data.stored().size();
        double usedPercent = capacity <= 0 ? 0.0 : Math.min(100.0, (stored * 100.0) / capacity);
        return List.of(
                loreLine("Level: ", String.valueOf(tier)),
                loreLine("Stored: ", String.format(Locale.ROOT, "%,d items across %d type%s", stored, types,
                        types == 1 ? "" : "s")),
                loreLine("Capacity: ", String.format(Locale.ROOT, "%,d / %,d (%.1f%%)", stored, capacity, usedPercent)),
                loreLine("Upgrade: ", tier >= maxUpgradeTier
                        ? "Max level reached"
                        : "Can upgrade to level " + (tier + 1)));
    }

    private static Component loreLine(String label, String value) {
        return Component.text(label, NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.YELLOW))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static String key(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":"
                + location.getBlockY() + ":" + location.getBlockZ();
    }

    private void index(Location location, UUID ownerUuid) {
        String locationKey = key(location);
        collectors.put(locationKey, location);
        collectorsByChunk.computeIfAbsent(chunkKey(location), ignored -> ConcurrentHashMap.newKeySet()).add(locationKey);
        reindexOwner(location, ownerUuid);
    }

    private List<Location> locationsInChunk(Chunk chunk) {
        return locationsInChunk(chunk.getWorld(),chunk.getX(),chunk.getZ());
    }

    private List<Location> locationsInChunk(World world,int chunkX,int chunkZ) {
        List<Location> locations = new ArrayList<>();
        for (String locationKey : collectorsByChunk.getOrDefault(chunkKey(world,chunkX,chunkZ), java.util.Set.of())) {
            Location location = collectors.get(locationKey);
            if (location != null) {
                locations.add(location);
            }
        }
        return locations;
    }

    private static String chunkKey(Chunk chunk) {
        return chunkKey(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    private static String chunkKey(Location location) {
        return chunkKey(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private static String chunkKey(World world, int chunkX, int chunkZ) {
        return world.getUID() + ":" + chunkX + ":" + chunkZ;
    }

    private static long boundedCapacity(long configured, long minimum) {
        return Math.max(minimum, Math.min(MAX_CAPACITY, configured));
    }

    private void reindexOwner(Location location, UUID ownerUuid) {
        String locationKey = key(location);
        collectorsByOwner.values().forEach(locations -> locations.remove(locationKey));
        collectorsByOwner.computeIfAbsent(ownerUuid, ignored -> ConcurrentHashMap.newKeySet()).add(locationKey);
    }
}
