package me.vertex.core.claims;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.spawner.SpawnerManager;
import org.bukkit.Bukkit;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Owns Base Claim anchors, their connected regions, and the upgrade-backed
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
    private volatile java.util.function.IntUnaryOperator upgradeSlotProvider = ignored -> 1;
    private volatile java.util.function.LongSupplier freshRaidExpiration =
            () -> System.currentTimeMillis() + 7L * 3_600_000L;
    private volatile RaidConversionListener raidConversionListener = (ignoredFaction, ignoredChunks, ignoredExpiry) -> { };
    private volatile java.util.function.Consumer<Set<ChunkKey>> baseAdmissionListener = ignored -> { };
    private volatile Runnable mutationPublisher = () -> { };

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

    public void setUpgradeSlotProvider(java.util.function.IntUnaryOperator provider) {
        upgradeSlotProvider = provider == null ? ignored -> 1 : provider;
    }

    public void setRaidConversion(java.util.function.LongSupplier expirationProvider,
            RaidConversionListener listener) {
        freshRaidExpiration = expirationProvider == null ? freshRaidExpiration : expirationProvider;
        raidConversionListener = listener == null ? raidConversionListener : listener;
    }

    public void setBaseAdmissionListener(java.util.function.Consumer<Set<ChunkKey>> listener) {
        baseAdmissionListener = listener == null ? ignored -> { } : listener;
    }

    public void setMutationPublisher(Runnable publisher) {
        mutationPublisher = publisher == null ? () -> { } : publisher;
    }

    /** Refreshes Base metadata after another shard commits a change. */
    public void refreshAsync() {
        CompletableFuture.runAsync(this::loadState);
    }

    /** One entry per unlocked Base Claim anchor, keyed by faction id then slot index. */
    private final Map<Integer, Map<Integer, Region>> regionsByFaction = new ConcurrentHashMap<>();
    /** O(1) chunk -> region lookup, rebuilt whenever a region's membership changes. */
    private final Map<ChunkKey, Region> chunkIndex = new ConcurrentHashMap<>();
    /** Slot #2/#3 purchases, independent of whether that slot has been anchored yet. */
    private final Map<Integer, Set<Integer>> purchasedSlots = new ConcurrentHashMap<>();
    private final Set<Integer> pendingFactions=ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> pendingDatabaseWrites=ConcurrentHashMap.newKeySet();

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
    }

    /** Rebuilds every region from durable storage. Must run after {@link #load()} and before any player interacts. */
    public synchronized void loadState() {
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
                        .add(ChunkKey.fromStorage(row.world(), row.chunkX(), row.chunkZ()));
            }
            for (ClaimStorage.BaseClaimAnchor anchor : anchors) {
                ChunkKey anchorKey = ChunkKey.fromStorage(anchor.world(), anchor.chunkX(), anchor.chunkZ());
                Set<ChunkKey> members = membershipByRegion.getOrDefault(
                        regionKey(anchor.factionId(), anchor.slotIndex()), new HashSet<>());
                members.add(anchorKey);
                Region region = new Region(anchor.factionId(), anchor.slotIndex(), anchorKey, members,
                        anchor.createdAt());
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

    /** Read-only chunk lookup used by displays such as the interactive faction map. */
    public boolean isBaseClaim(int factionId, ChunkKey chunk) {
        Region region = chunkIndex.get(chunk);
        return region != null && region.factionId() == factionId;
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
        int legacy = 1 + purchasedSlots.getOrDefault(factionId, Set.of()).size();
        return Math.max(1, Math.min(MAX_SLOTS, Math.max(legacy, upgradeSlotProvider.applyAsInt(factionId))));
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

    public Region region(int factionId, int slotIndex) {
        return regionsByFaction.getOrDefault(factionId, Map.of()).get(slotIndex);
    }

    /**
     * Repairs Base metadata against the authoritative native claim table at startup.
     * Only this backend's shard is touched: remote Base regions are reconciled by
     * their owning backend. Missing/disconnected members become fresh Raid Claims,
     * while newly connected native claims are absorbed into the surviving Base.
     */
    public synchronized void reconcileLiveClaims() {
        for (Map<Integer, Region> factionRegions : List.copyOf(regionsByFaction.values())) {
            for (Region region : List.copyOf(factionRegions.values())) {
                if (!region.anchor.isLocalShard()) continue;
                if (liveOwner(region.anchor, FactionsHook.service().factionIdAt(region.anchor))
                        != region.factionId) {
                    handleUnclaimed(region.factionId, region.anchor);
                    continue;
                }
                Set<ChunkKey> connected = connectedOwnedMembers(region, null);
                Set<ChunkKey> disconnected = region.members.stream()
                        .filter(chunk -> !connected.contains(chunk))
                        .collect(java.util.stream.Collectors.toSet());
                if (!disconnected.isEmpty()) {
                    Set<ChunkKey> converted = disconnected.stream()
                            .filter(chunk -> liveOwner(chunk, region.factionId) == region.factionId)
                            .collect(java.util.stream.Collectors.toSet());
                    long expiresAt = freshRaidExpiration.getAsLong();
                    try {
                        storage.convertDisconnectedMembersToRaid(region.factionId, region.slotIndex,
                                disconnected, converted, expiresAt);
                        region.members.removeAll(disconnected);
                        disconnected.forEach(chunk -> chunkIndex.remove(chunk, region));
                        raidConversionListener.converted(region.factionId, converted, expiresAt);
                    } catch (Exception error) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Failed to reconcile disconnected Base Claim metadata.", error);
                        continue;
                    }
                }
                growFromSeed(region, region.anchor);
                baseAdmissionListener.accept(region.members());
            }
        }
    }

    // ---- Anchor creation / removal ----

    public enum CreateResult {
        OK, NO_SLOT_AVAILABLE, NOT_YOUR_FACTIONS_CLAIM, ALREADY_BASE_CLAIM,
        NOT_AUTHORIZED, STATE_CHANGED, PERSIST_FAILED, BUSY
    }

    /** Plans Bukkit/world-sensitive growth now, then commits the region off-thread. */
    public CompletableFuture<CreateResult> createAnchorAsync(Player player,int factionId,Location location){
        return createAnchorAsync(player, factionId, location, nextAvailableSlot(factionId));
    }

    public CompletableFuture<CreateResult> createAnchorAsync(Player player, int factionId, Location location,
            int requestedSlot) {
        if(anchoredCount(factionId)>=unlockedSlots(factionId))return CompletableFuture.completedFuture(CreateResult.NO_SLOT_AVAILABLE);
        if(requestedSlot<1||requestedSlot>unlockedSlots(factionId)
                ||regionsByFaction.getOrDefault(factionId,Map.of()).containsKey(requestedSlot))return CompletableFuture.completedFuture(CreateResult.NO_SLOT_AVAILABLE);
        if(FactionsHook.getClaimFactionId(location)!=factionId)return CompletableFuture.completedFuture(CreateResult.NOT_YOUR_FACTIONS_CLAIM);
        ChunkKey anchor=ChunkKey.of(location);if(chunkIndex.containsKey(anchor))return CompletableFuture.completedFuture(CreateResult.ALREADY_BASE_CLAIM);
        if(!pendingFactions.add(factionId))return CompletableFuture.completedFuture(CreateResult.BUSY);
        int slot=requestedSlot;Set<ChunkKey> members=discoverConnectedRegion(factionId,anchor);
        CompletableFuture<CreateResult> result=new CompletableFuture<>();long now=System.currentTimeMillis();
        CompletableFuture<ClaimStorage.BaseMutationResult> write=CompletableFuture.supplyAsync(()->{
            try{return storage.insertBaseClaimRegionAuthorized(player.getUniqueId(),factionId,slot,
                    anchor,now,members);}
            catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
        });
        track(write);write.whenComplete((saved,error)->Bukkit.getScheduler().runTask(plugin,()->{
                    pendingFactions.remove(factionId);
                    if(error!=null){plugin.getLogger().log(Level.SEVERE,"Failed to persist a new Base Claim anchor.",error);result.complete(CreateResult.PERSIST_FAILED);return;}
                    if(saved!=ClaimStorage.BaseMutationResult.OK){
                        result.complete(saved==ClaimStorage.BaseMutationResult.NOT_AUTHORIZED
                                ?CreateResult.NOT_AUTHORIZED:CreateResult.STATE_CHANGED);return;
                    }
                    Region region=new Region(factionId,slot,anchor,members,now);regionsByFaction.computeIfAbsent(factionId,k->new ConcurrentHashMap<>()).put(slot,region);for(ChunkKey member:members)chunkIndex.put(member,region);baseAdmissionListener.accept(Set.copyOf(members));mutationPublisher.run();result.complete(CreateResult.OK);
                }));
        return result;
    }

    private Set<ChunkKey> discoverConnectedRegion(int factionId,ChunkKey anchor){
        Set<ChunkKey> found=new HashSet<>();Deque<ChunkKey> queue=new ArrayDeque<>();found.add(anchor);queue.add(anchor);
        while(!queue.isEmpty()&&found.size()<maxChunksPerRegion){ChunkKey current=queue.poll();for(ChunkKey neighbor:current.neighbors()){
            if(found.contains(neighbor)||chunkIndex.containsKey(neighbor))continue;World world=neighbor.isLocalShard()?Bukkit.getWorld(neighbor.localWorld()):null;if(world==null)continue;
            Location probe=new Location(world,(neighbor.x()<<4)+8,64,(neighbor.z()<<4)+8);if(FactionsHook.getClaimFactionId(probe)!=factionId)continue;
            found.add(neighbor);queue.add(neighbor);if(found.size()>=maxChunksPerRegion)break;
        }}return found;
    }

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
            storage.insertBaseClaimWithAnchorChunk(factionId, slotIndex, anchorKey.world(), anchorKey.x(),
                    anchorKey.z(), System.currentTimeMillis());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a new Base Claim anchor.", error);
            return CreateResult.PERSIST_FAILED;
        }
        Set<ChunkKey> members = new HashSet<>();
        members.add(anchorKey);
        Region region = new Region(factionId, slotIndex, anchorKey, members, System.currentTimeMillis());
        regionsByFaction.computeIfAbsent(factionId, k -> new ConcurrentHashMap<>()).put(slotIndex, region);
        chunkIndex.put(anchorKey, region);
        // Immediately try to absorb any already-claimed same-faction chunks touching the new anchor.
        growFromSeed(region, anchorKey);
        baseAdmissionListener.accept(region.members());
        mutationPublisher.run();
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

    public enum RemoveResult {
        OK, SHIELDED, HAS_SPAWNERS, NOT_FOUND, NOT_AUTHORIZED, STATE_CHANGED, PERSIST_FAILED, BUSY
    }

    public CompletableFuture<RemoveResult> removeAnchorAsync(Player player,int factionId,int slotIndex){
        Region region=regionsByFaction.getOrDefault(factionId,Map.of()).get(slotIndex);if(region==null)return CompletableFuture.completedFuture(RemoveResult.NOT_FOUND);
        if(shieldActiveQuery.test(factionId))return CompletableFuture.completedFuture(RemoveResult.SHIELDED);
        if(regionHasSpawners(region))return CompletableFuture.completedFuture(RemoveResult.HAS_SPAWNERS);
        if(!pendingFactions.add(factionId))return CompletableFuture.completedFuture(RemoveResult.BUSY);
        CompletableFuture<RemoveResult> result=new CompletableFuture<>();
        Set<ChunkKey> claimed=region.members.stream().filter(chunk->liveOwner(chunk,factionId)==factionId).collect(java.util.stream.Collectors.toSet());
        long expiresAt=freshRaidExpiration.getAsLong();
        CompletableFuture<ClaimStorage.BaseMutationResult> write=CompletableFuture.supplyAsync(()->{
            try{return storage.convertBaseRegionToRaidAuthorized(player.getUniqueId(),factionId,
                    slotIndex,claimed,expiresAt);}
            catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
        });
        track(write);write.whenComplete((saved,error)->Bukkit.getScheduler().runTask(plugin,()->{
                    pendingFactions.remove(factionId);
                    if(error!=null){plugin.getLogger().log(Level.SEVERE,"Failed to persist a Base Claim removal.",error);result.complete(RemoveResult.PERSIST_FAILED);return;}
                    if(saved!=ClaimStorage.BaseMutationResult.OK){
                        result.complete(saved==ClaimStorage.BaseMutationResult.NOT_AUTHORIZED
                                ?RemoveResult.NOT_AUTHORIZED
                                :saved==ClaimStorage.BaseMutationResult.SHIELDED
                                        ?RemoveResult.SHIELDED:RemoveResult.STATE_CHANGED);return;
                    }
                    Map<Integer,Region> factionRegions=regionsByFaction.get(factionId);
                    if(factionRegions!=null)factionRegions.remove(slotIndex,region);
                    for(ChunkKey member:region.members)chunkIndex.remove(member,region);
                    raidConversionListener.converted(factionId,claimed,expiresAt);mutationPublisher.run();result.complete(RemoveResult.OK);
                }));
        return result;
    }

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
        if (shieldActiveQuery.test(factionId)) return RemoveResult.SHIELDED;
        if (regionHasSpawners(region)) return RemoveResult.HAS_SPAWNERS;
        Set<ChunkKey> claimed=region.members.stream().filter(chunk->liveOwner(chunk,factionId)==factionId).collect(java.util.stream.Collectors.toSet());
        long expiresAt=freshRaidExpiration.getAsLong();
        try {
            storage.convertBaseRegionToRaid(factionId, slotIndex, claimed, expiresAt);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a Base Claim removal.", error);
            return RemoveResult.PERSIST_FAILED;
        }
        Map<Integer,Region> factionRegions=regionsByFaction.get(factionId);
        if(factionRegions!=null)factionRegions.remove(slotIndex,region);
        for (ChunkKey member : region.members) {
            chunkIndex.remove(member);
        }
        raidConversionListener.converted(factionId, claimed, expiresAt);
        mutationPublisher.run();
        return RemoveResult.OK;
    }

    private boolean regionHasSpawners(Region region) {
        for (ChunkKey key : region.members) {
            if (spawners.hasSpawnerInChunk(key.localWorld(), key.x(), key.z())) {
                return true;
            }
        }
        return false;
    }

    /** True when removing this chunk would turn still-owned Base members into Raid Claims. */
    public boolean wouldDisconnectOnUnclaim(int factionId, ChunkKey removed) {
        Region region = chunkIndex.get(removed);
        if (region == null || region.factionId != factionId) return false;
        if (region.anchor.equals(removed)) return region.members.stream()
                .anyMatch(chunk -> !chunk.equals(removed) && liveOwner(chunk, factionId) == factionId);
        Set<ChunkKey> connected = connectedOwnedMembers(region, removed);
        return region.members.stream().anyMatch(chunk -> !chunk.equals(removed)
                && liveOwner(chunk, factionId) == factionId && !connected.contains(chunk));
    }

    /** Rebuilds one Base graph after the core claim was durably unclaimed. */
    public synchronized boolean handleUnclaimed(int factionId, ChunkKey removed) {
        Region region = chunkIndex.get(removed);
        if (region == null || region.factionId != factionId) return true;
        long expiresAt = freshRaidExpiration.getAsLong();
        if (region.anchor.equals(removed)) {
            Set<ChunkKey> converted = region.members.stream()
                    .filter(chunk -> !chunk.equals(removed) && liveOwner(chunk, factionId) == factionId)
                    .collect(java.util.stream.Collectors.toSet());
            try {
                storage.convertBaseRegionToRaid(factionId, region.slotIndex, converted, expiresAt);
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Failed to convert a Base after its anchor was unclaimed.", error);
                return false;
            }
            regionsByFaction.getOrDefault(factionId, Map.of()).remove(region.slotIndex, region);
            for (ChunkKey member : region.members) chunkIndex.remove(member, region);
            raidConversionListener.converted(factionId, converted, expiresAt);
            mutationPublisher.run();
            return true;
        }
        Set<ChunkKey> connected = connectedOwnedMembers(region, removed);
        Set<ChunkKey> disconnected = region.members.stream()
                .filter(chunk -> chunk.equals(removed) || !connected.contains(chunk))
                .collect(java.util.stream.Collectors.toSet());
        Set<ChunkKey> converted = disconnected.stream()
                .filter(chunk -> liveOwner(chunk, factionId) == factionId)
                .collect(java.util.stream.Collectors.toSet());
        try {
            storage.convertDisconnectedMembersToRaid(factionId, region.slotIndex, disconnected, converted, expiresAt);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to update disconnected Base Claim chunks.", error);
            return false;
        }
        region.members.removeAll(disconnected);
        for (ChunkKey member : disconnected) chunkIndex.remove(member, region);
        raidConversionListener.converted(factionId, converted, expiresAt);
        mutationPublisher.run();
        return true;
    }

    private Set<ChunkKey> connectedOwnedMembers(Region region, ChunkKey excluded) {
        Set<ChunkKey> connected = new HashSet<>();
        if ((excluded != null && region.anchor.equals(excluded))
                || liveOwner(region.anchor, region.factionId) != region.factionId) return connected;
        Deque<ChunkKey> queue = new ArrayDeque<>();
        connected.add(region.anchor);
        queue.add(region.anchor);
        while (!queue.isEmpty()) {
            for (ChunkKey neighbor : queue.remove().neighbors()) {
                if ((excluded != null && neighbor.equals(excluded)) || !region.members.contains(neighbor) || connected.contains(neighbor)
                        || liveOwner(neighbor, region.factionId) != region.factionId) continue;
                connected.add(neighbor);
                queue.add(neighbor);
            }
        }
        return connected;
    }

    private static int liveOwner(ChunkKey chunk, int fallback) {
        return FactionsHook.isInstalled() ? FactionsHook.service().factionIdAt(chunk) : fallback;
    }

    // ---- Connection growth, called by ClaimEventListener on every new faction claim ----

    public enum ConnectResult { NOT_APPLICABLE, JOINED, REGION_FULL, PERSIST_FAILED }

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
        return admit(touching, claimed) ? ConnectResult.JOINED : ConnectResult.PERSIST_FAILED;
    }

    /** Flood-fills outward from a freshly created anchor, absorbing already-claimed same-faction neighbors. */
    private void growFromSeed(Region region, ChunkKey seed) {
        Deque<ChunkKey> queue = new ArrayDeque<>();
        Set<ChunkKey> additions = new HashSet<>();
        queue.add(seed);
        while (!queue.isEmpty()) {
            ChunkKey current = queue.poll();
            for (ChunkKey neighbor : current.neighbors()) {
                if (region.members.contains(neighbor) || additions.contains(neighbor) || chunkIndex.containsKey(neighbor)) {
                    continue;
                }
                World world = neighbor.isLocalShard() ? Bukkit.getWorld(neighbor.localWorld()) : null;
                if (world == null) {
                    continue;
                }
                Location probe = new Location(world, (neighbor.x() << 4) + 8, 64, (neighbor.z() << 4) + 8);
                if (FactionsHook.getClaimFactionId(probe) != region.factionId) {
                    continue;
                }
                if (region.members.size()+additions.size() >= maxChunksPerRegion) {
                    queue.clear();
                    break;
                }
                additions.add(neighbor);
                queue.add(neighbor);
            }
        }
        if(additions.isEmpty())return;
        try{
            storage.insertRegionChunks(region.factionId,region.slotIndex,additions);
            region.members.addAll(additions);
            for(ChunkKey chunk:additions)chunkIndex.put(chunk,region);
            mutationPublisher.run();
        }catch(Exception error){
            plugin.getLogger().log(Level.SEVERE,"Failed to persist Base Claim flood growth; no chunks were admitted.",error);
        }
    }

    private boolean admit(Region region, ChunkKey chunk) {
        try {
            storage.insertRegionChunk(region.factionId, region.slotIndex, chunk.world(), chunk.x(), chunk.z());
            region.members.add(chunk);
            chunkIndex.put(chunk, region);
            mutationPublisher.run();
            return true;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a Base Claim region chunk.", error);
            return false;
        }
    }

    /** Removes every cached and durable Base Claim record for a disbanded faction. */
    public boolean deleteFactionData(int factionId) {
        try {
            storage.deleteFactionData(factionId);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to delete Base Claim data for faction " + factionId, error);
            return false;
        }
        Map<Integer, Region> removed = regionsByFaction.remove(factionId);
        if (removed != null) {
            for (Region region : removed.values()) {
                for (ChunkKey member : region.members) {
                    chunkIndex.remove(member, region);
                }
            }
        }
        purchasedSlots.remove(factionId);
        mutationPublisher.run();
        return true;
    }

    /** Clears live regions after the owning claim transaction removed their SQL metadata. */
    public synchronized void forgetFactionRegions(int factionId) {
        Map<Integer, Region> removed = regionsByFaction.remove(factionId);
        if (removed == null) return;
        for (Region region : removed.values()) {
            for (ChunkKey member : region.members) chunkIndex.remove(member, region);
        }
        mutationPublisher.run();
    }

    private void track(CompletableFuture<?> write){pendingDatabaseWrites.add(write);write.whenComplete((ignored,error)->pendingDatabaseWrites.remove(write));}
    public void awaitWrites(){try{CompletableFuture.allOf(pendingDatabaseWrites.toArray(new CompletableFuture[0])).get(10,TimeUnit.SECONDS);}catch(Exception error){plugin.getLogger().log(Level.WARNING,"Timed out waiting for Base Claim writes.",error);}}

    /** One Base Claim anchor and every chunk currently admitted into its connected region. */
    public static final class Region {
        private final int factionId;
        private final int slotIndex;
        private final ChunkKey anchor;
        private final Set<ChunkKey> members;

        private final long createdAtMillis;

        Region(int factionId, int slotIndex, ChunkKey anchor, Set<ChunkKey> members, long createdAtMillis) {
            this.factionId = factionId;
            this.slotIndex = slotIndex;
            this.anchor = anchor;
            this.members = ConcurrentHashMap.newKeySet();
            this.members.addAll(members);
            this.createdAtMillis = createdAtMillis;
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

        public long createdAtMillis() { return createdAtMillis; }
    }

    @FunctionalInterface
    public interface RaidConversionListener {
        void converted(int factionId, Set<ChunkKey> chunks, long expiresAt);
    }
}
