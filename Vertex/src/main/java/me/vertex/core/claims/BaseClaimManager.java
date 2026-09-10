package me.vertex.core.claims;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.spawner.SpawnerManager;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Owns Base Claim anchors, their connected regions, and the free/purchasable
 * slot count per faction. Kept entirely in memory once loaded (rebuilt from
 * {@link ClaimStorage} at startup), the same "durable table, live cache"
 * split {@code FTopManager}/{@code GcManager} use elsewhere in this codebase.
 */
public final class BaseClaimManager {

    public static final int MAX_SLOTS = 3;

    private final Plugin plugin;
    private final ClaimStorage storage;
    private final SpawnerManager spawners;
    private final File file;

    private volatile int maxChunksPerRegion;
    private volatile double slot2Price;
    private volatile double slot3Price;

    /**
     * Set once by {@code VertexPlugin} after both managers exist (Shield is
     * built after Base Claims). Left null-safe so this class never needs a
     * hard compile dependency on the {@code shield} package beyond this one
     * functional query.
     */
    private volatile java.util.function.IntPredicate shieldActiveQuery = factionId -> false;

    public void setShieldActiveQuery(java.util.function.IntPredicate shieldActiveQuery) {
        this.shieldActiveQuery = shieldActiveQuery == null ? factionId -> false : shieldActiveQuery;
    }

    /** One entry per unlocked Base Claim anchor, keyed by faction id then slot index. */
    private final Map<Integer, Map<Integer, Region>> regionsByFaction = new ConcurrentHashMap<>();
    /** O(1) chunk -> region lookup, rebuilt whenever a region's membership changes. */
    private final Map<ChunkKey, Region> chunkIndex = new ConcurrentHashMap<>();
    /** Slot #2/#3 purchases, independent of whether that slot has been anchored yet. */
    private final Map<Integer, Set<Integer>> purchasedSlots = new ConcurrentHashMap<>();

    public BaseClaimManager(Plugin plugin, ClaimStorage storage, SpawnerManager spawners) {
        this.plugin = plugin;
        this.storage = storage;
        this.spawners = spawners;
        this.file = new File(plugin.getDataFolder(), "claims.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("claims.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        maxChunksPerRegion = Math.max(1, config.getInt("base-claim.max-chunks-per-region", 2000));
        slot2Price = Math.max(0D, config.getDouble("base-claim.slot-2-price", 50_000D));
        slot3Price = Math.max(0D, config.getDouble("base-claim.slot-3-price", 150_000D));
    }

    /** Rebuilds every region from durable storage. Must run after {@link #load()} and before any player interacts. */
    public void loadState() {
        regionsByFaction.clear();
        chunkIndex.clear();
        purchasedSlots.clear();
        try {
            for (int[] row : storage.loadAllPurchasedSlots()) {
                purchasedSlots.computeIfAbsent(row[0], k -> ConcurrentHashMap.newKeySet()).add(row[1]);
            }
            List<ClaimStorage.BaseClaimAnchor> anchors = storage.loadBaseClaims();
            Map<String, Set<ChunkKey>> membershipByRegion = new java.util.HashMap<>();
            for (ClaimStorage.RegionChunk row : storage.loadRegionChunks()) {
                membershipByRegion.computeIfAbsent(regionKey(row.factionId(), row.slotIndex()), k -> new HashSet<>())
                        .add(new ChunkKey(row.world(), row.chunkX(), row.chunkZ()));
            }
            for (ClaimStorage.BaseClaimAnchor anchor : anchors) {
                ChunkKey anchorKey = new ChunkKey(anchor.world(), anchor.chunkX(), anchor.chunkZ());
                Set<ChunkKey> members = membershipByRegion.getOrDefault(
                        regionKey(anchor.factionId(), anchor.slotIndex()), new HashSet<>());
                members.add(anchorKey);
                Region region = new Region(anchor.factionId(), anchor.slotIndex(), anchorKey, members);
                regionsByFaction.computeIfAbsent(anchor.factionId(), k -> new ConcurrentHashMap<>())
                        .put(anchor.slotIndex(), region);
                for (ChunkKey member : members) {
                    chunkIndex.put(member, region);
                }
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Base Claim state; starting empty.", error);
        }
    }

    private static String regionKey(int factionId, int slotIndex) {
        return factionId + ":" + slotIndex;
    }

    // ---- Queries used by later phases (Shield, TNT rules, Chunk Busters, Source Buckets) ----

    public boolean isBaseClaim(Location location) {
        return isPartOfBaseClaimRegion(location);
    }

    public boolean isPartOfBaseClaimRegion(Location location) {
        Region region = chunkIndex.get(ChunkKey.of(location));
        return region != null && FactionsHook.getClaimFactionId(location) == region.factionId();
    }

    /** The Base Claim region containing this location, or null if it isn't part of one. */
    public Region regionAt(Location location) {
        Region region = chunkIndex.get(ChunkKey.of(location));
        return region != null && FactionsHook.getClaimFactionId(location) == region.factionId() ? region : null;
    }

    public boolean isAnchor(int factionId, ChunkKey chunk) {
        Map<Integer, Region> regions = regionsByFaction.get(factionId);
        if (regions == null) {
            return false;
        }
        return regions.values().stream().anyMatch(region -> region.anchor.equals(chunk));
    }

    public int unlockedSlots(int factionId) {
        return 1 + purchasedSlots.getOrDefault(factionId, Set.of()).size();
    }

    public int anchoredCount(int factionId) {
        return regionsByFaction.getOrDefault(factionId, Map.of()).size();
    }

    public int maxChunksPerRegion() {
        return maxChunksPerRegion;
    }

    public List<Region> regionsOf(int factionId) {
        return List.copyOf(regionsByFaction.getOrDefault(factionId, Map.of()).values());
    }

    // ---- Slot purchase ----

    public enum PurchaseResult { OK, ALL_UNLOCKED, NO_ECONOMY, CANNOT_AFFORD, PERSIST_FAILED }

    public PurchaseResult purchaseNextSlot(Player player, int factionId) {
        int unlocked = unlockedSlots(factionId);
        if (unlocked >= MAX_SLOTS) {
            return PurchaseResult.ALL_UNLOCKED;
        }
        int nextSlot = unlocked + 1;
        double price = nextSlot == 2 ? slot2Price : slot3Price;
        if (!EconomyHook.isAvailable()) {
            return PurchaseResult.NO_ECONOMY;
        }
        EconomyResponse response = EconomyHook.getEconomy().withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            return PurchaseResult.CANNOT_AFFORD;
        }
        try {
            storage.insertPurchasedSlot(factionId, nextSlot, System.currentTimeMillis());
            purchasedSlots.computeIfAbsent(factionId, k -> ConcurrentHashMap.newKeySet()).add(nextSlot);
            return PurchaseResult.OK;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a Base Claim slot purchase; refunding.", error);
            EconomyHook.getEconomy().depositPlayer(player, price);
            return PurchaseResult.PERSIST_FAILED;
        }
    }

    public double priceForNextSlot(int factionId) {
        int unlocked = unlockedSlots(factionId);
        if (unlocked >= MAX_SLOTS) {
            return -1D;
        }
        return unlocked + 1 == 2 ? slot2Price : slot3Price;
    }

    // ---- Anchor creation / removal ----

    public enum CreateResult { OK, NO_SLOT_AVAILABLE, NOT_YOUR_FACTIONS_CLAIM, ALREADY_BASE_CLAIM, PERSIST_FAILED }

    public CreateResult createAnchor(Player player, int factionId, Location location) {
        if (anchoredCount(factionId) >= unlockedSlots(factionId)) {
            return CreateResult.NO_SLOT_AVAILABLE;
        }
        if (FactionsHook.getClaimFactionId(location) != factionId) {
            return CreateResult.NOT_YOUR_FACTIONS_CLAIM;
        }
        ChunkKey anchorKey = ChunkKey.of(location);
        if (chunkIndex.containsKey(anchorKey)) {
            return CreateResult.ALREADY_BASE_CLAIM;
        }
        int slotIndex = nextAvailableSlot(factionId);
        try {
            storage.insertBaseClaim(factionId, slotIndex, anchorKey.world(), anchorKey.x(), anchorKey.z(),
                    System.currentTimeMillis());
            storage.insertRegionChunk(factionId, slotIndex, anchorKey.world(), anchorKey.x(), anchorKey.z());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a new Base Claim anchor.", error);
            return CreateResult.PERSIST_FAILED;
        }
        Set<ChunkKey> members = new HashSet<>();
        members.add(anchorKey);
        Region region = new Region(factionId, slotIndex, anchorKey, members);
        regionsByFaction.computeIfAbsent(factionId, k -> new ConcurrentHashMap<>()).put(slotIndex, region);
        chunkIndex.put(anchorKey, region);
        // Immediately try to absorb any already-claimed same-faction chunks touching the new anchor.
        growFromSeed(region, anchorKey);
        return CreateResult.OK;
    }

    /**
     * The lowest slot number (1..{@link #unlockedSlots}) not currently
     * occupied by an anchored region. Deliberately not just {@code
     * anchoredCount(factionId) + 1} -- {@link #removeAnchor} can free any
     * specific slot, not only the highest-numbered one (e.g. removing slot
     * 1 while slot 2/3 stay anchored), and {@code anchoredCount + 1} would
     * then recompute a slot number that is still occupied instead of
     * reusing the one that was actually freed, permanently colliding with
     * {@code base_claims}' {@code (faction_id, slot_index)} primary key on
     * every subsequent attempt.
     */
    int nextAvailableSlot(int factionId) {
        Map<Integer, Region> existing = regionsByFaction.getOrDefault(factionId, Map.of());
        int unlocked = unlockedSlots(factionId);
        for (int candidate = 1; candidate <= unlocked; candidate++) {
            if (!existing.containsKey(candidate)) {
                return candidate;
            }
        }
        // Defensive only -- the anchoredCount < unlockedSlots guard in
        // createAnchor should make this unreachable.
        return unlocked + 1;
    }

    public enum RemoveResult { OK, SHIELDED, HAS_SPAWNERS, NOT_FOUND }

    /**
     * Removes a Base Claim entirely (all connected chunks revert to being
     * plain faction claims, i.e. Raid Claims). Blocked while the faction's
     * Shield is active, via the query {@code VertexPlugin} wires in with
     * {@link #setShieldActiveQuery}.
     */
    public RemoveResult removeAnchor(int factionId, int slotIndex) {
        Region region = regionsByFaction.getOrDefault(factionId, Map.of()).get(slotIndex);
        if (region == null) {
            return RemoveResult.NOT_FOUND;
        }
        if (shieldActiveQuery.test(factionId)) {
            return RemoveResult.SHIELDED;
        }
        if (regionHasSpawners(region)) {
            return RemoveResult.HAS_SPAWNERS;
        }
        try {
            storage.deleteBaseClaim(factionId, slotIndex);
            storage.deleteRegionChunks(factionId, slotIndex);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a Base Claim removal.", error);
        }
        regionsByFaction.getOrDefault(factionId, Map.of()).remove(slotIndex);
        for (ChunkKey member : region.members) {
            chunkIndex.remove(member);
        }
        return RemoveResult.OK;
    }

    private boolean regionHasSpawners(Region region) {
        for (ChunkKey key : region.members) {
            World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) {
                continue;
            }
            Chunk chunk = world.getChunkAt(key.x(), key.z());
            if (!spawners.getSpawnersInChunk(chunk).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---- Connection growth, called by ClaimEventListener on every new faction claim ----

    public enum ConnectResult { NOT_APPLICABLE, JOINED, REGION_FULL }

    /**
     * Called after a faction claims a new chunk. If that chunk touches an
     * existing Base Claim region of the same faction and the region has
     * capacity, the chunk joins the region (becoming part of the Base
     * Claim rather than a Raid Claim). Otherwise it's left alone --
     * {@code RaidClaimManager} is responsible for tracking it as a Raid
     * Claim in that case.
     */
    public ConnectResult tryConnect(int factionId, ChunkKey claimed) {
        Region touching = null;
        for (ChunkKey neighbor : claimed.neighbors()) {
            Region candidate = chunkIndex.get(neighbor);
            if (candidate != null && candidate.factionId == factionId) {
                touching = candidate;
                break;
            }
        }
        if (touching == null) {
            return ConnectResult.NOT_APPLICABLE;
        }
        if (touching.members.contains(claimed)) {
            return ConnectResult.JOINED; // already tracked (e.g. reclaim of a previously-admitted chunk)
        }
        if (touching.members.size() >= maxChunksPerRegion) {
            return ConnectResult.REGION_FULL;
        }
        admit(touching, claimed);
        return ConnectResult.JOINED;
    }

    /** Flood-fills outward from a freshly created anchor, absorbing already-claimed same-faction neighbors. */
    private void growFromSeed(Region region, ChunkKey seed) {
        Deque<ChunkKey> queue = new ArrayDeque<>();
        queue.add(seed);
        while (!queue.isEmpty()) {
            ChunkKey current = queue.poll();
            for (ChunkKey neighbor : current.neighbors()) {
                if (region.members.contains(neighbor) || chunkIndex.containsKey(neighbor)) {
                    continue;
                }
                World world = Bukkit.getWorld(neighbor.world());
                if (world == null) {
                    continue;
                }
                Location probe = new Location(world, (neighbor.x() << 4) + 8, 64, (neighbor.z() << 4) + 8);
                if (FactionsHook.getClaimFactionId(probe) != region.factionId) {
                    continue;
                }
                if (region.members.size() >= maxChunksPerRegion) {
                    return;
                }
                admit(region, neighbor);
                queue.add(neighbor);
            }
        }
    }

    private void admit(Region region, ChunkKey chunk) {
        region.members.add(chunk);
        chunkIndex.put(chunk, region);
        try {
            storage.insertRegionChunk(region.factionId, region.slotIndex, chunk.world(), chunk.x(), chunk.z());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a Base Claim region chunk.", error);
        }
    }

    /** One Base Claim anchor and every chunk currently admitted into its connected region. */
    public static final class Region {
        private final int factionId;
        private final int slotIndex;
        private final ChunkKey anchor;
        private final Set<ChunkKey> members;

        Region(int factionId, int slotIndex, ChunkKey anchor, Set<ChunkKey> members) {
            this.factionId = factionId;
            this.slotIndex = slotIndex;
            this.anchor = anchor;
            this.members = ConcurrentHashMap.newKeySet();
            this.members.addAll(members);
        }

        public int factionId() {
            return factionId;
        }

        public int slotIndex() {
            return slotIndex;
        }

        public ChunkKey anchor() {
            return anchor;
        }

        public Set<ChunkKey> members() {
            return Set.copyOf(members);
        }
    }
}
