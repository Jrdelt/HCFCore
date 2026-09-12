package me.vertex.core.claims;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntPredicate;
import java.util.logging.Level;

import me.vertex.core.factions.FactionsHook;

/**
 * Tracks the independent, real-world-elapsed-time expiration of every Raid
 * Claim chunk (any faction claim that is not part of a Base Claim region).
 *
 * <p>Deadlines are stored as absolute epoch millis, the same
 * "persist the deadline, not a countdown" idiom {@code FTopManager} and
 * {@code HotZoneManager} use -- a chunk claimed at server-down time keeps
 * expiring in real time, and on the next startup {@link #recoverState()}
 * unclaims anything already overdue instead of granting it a fresh timer.
 *
 * <p>Per spec, Raid Claim creation and automatic expiration/unclaiming are
 * never logged anywhere (no {@code /f logs}-style entry for either event).
 */
public final class RaidClaimManager {

    private final Plugin plugin;
    private final ClaimStorage storage;
    private final File file;
    private final java.util.function.BiFunction<ChunkKey, Integer, Boolean> claimRemover;
    private final java.util.function.ToIntFunction<ChunkKey> ownerQuery;
    private static final int OWNER_QUERY_UNAVAILABLE = Integer.MIN_VALUE;

    private volatile long durationMillis;
    private volatile long sweepIntervalTicks;
    private volatile Runnable mutationPublisher = () -> { };

    /** Only currently-tracked Raid Claim chunks -- the sweep never scans the whole world. */
    private final Map<ChunkKey, Entry> tracked = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<?>> pendingSweeps = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean sweepInProgress = new AtomicBoolean();
    private BukkitTask task;

    public RaidClaimManager(Plugin plugin, ClaimStorage storage) {
        this(plugin, storage,
                (key, expectedOwner) -> !FactionsHook.isInstalled()
                        || FactionsHook.service().forceUnclaim(key, expectedOwner),
                key -> FactionsHook.isInstalled()
                        ? FactionsHook.service().factionIdAt(key) : OWNER_QUERY_UNAVAILABLE);
    }

    /**
     * The explicit remover keeps persisted-deadline recovery independently
     * testable and avoids requiring a fully booted faction service just to
     * clear stale raid-expiration rows during storage maintenance.
     */
    RaidClaimManager(Plugin plugin, ClaimStorage storage,
                     java.util.function.Function<ChunkKey, Boolean> claimRemover) {
        this(plugin, storage, (key, ignoredOwner) -> claimRemover.apply(key),
                ignored -> OWNER_QUERY_UNAVAILABLE);
    }

    RaidClaimManager(Plugin plugin, ClaimStorage storage,
                     java.util.function.BiFunction<ChunkKey, Integer, Boolean> claimRemover,
                     java.util.function.ToIntFunction<ChunkKey> ownerQuery) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = me.vertex.core.factions.FactionConfigManager.file(plugin);
        this.claimRemover = claimRemover;
        this.ownerQuery = ownerQuery;
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("factions.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        durationMillis = Math.max(60_000L, config.getLong("raid-claim.duration-seconds", 7L * 3_600L) * 1_000L);
        sweepIntervalTicks = Math.max(20L, config.getLong("raid-claim.sweep-interval-seconds", 30L) * 20L);
    }

    /** Loads every tracked row from storage. Expired rows are left in memory for {@link #recoverState()} to unclaim. */
    public synchronized void loadState() {
        tracked.clear();
        try {
            for (ClaimStorage.RaidClaimRow row : storage.loadRaidClaims()) {
                tracked.put(ChunkKey.fromStorage(row.world(), row.chunkX(), row.chunkZ()),
                        new Entry(row.factionId(), row.expiresAtMillis()));
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Raid Claim state; starting empty.", error);
        }
    }

    public void setMutationPublisher(Runnable publisher) {
        mutationPublisher = publisher == null ? () -> { } : publisher;
    }

    /** Refreshes Raid deadlines after another shard commits claim metadata. */
    public void refreshAsync() {
        CompletableFuture.runAsync(this::loadState);
    }

    /** Unclaims anything that already expired while the server was down, then starts the sweep timer. */
    public void recoverState() {
        // Startup recovery is deliberately complete before enable returns, so
        // an overdue claim is never briefly usable after a restart.
        sweep();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::startSweep,
                sweepIntervalTicks, sweepIntervalTicks);
    }

    /**
     * Restores the invariant that every local, non-system native claim is
     * either a Base member or has exactly one persisted Raid expiration.
     */
    public synchronized void reconcile(Map<ChunkKey, Integer> liveClaims,
            BaseClaimManager baseClaims, IntPredicate systemFaction) {
        for (Map.Entry<ChunkKey, Entry> row : List.copyOf(tracked.entrySet())) {
            if (!row.getKey().isLocalShard()) continue;
            Integer owner = liveClaims.get(row.getKey());
            if (owner == null || owner != row.getValue().factionId()
                    || systemFaction.test(owner) || baseClaims.isBaseClaim(owner, row.getKey())) {
                deleteTrackedRow(row.getKey(), row.getValue(), "stale startup Raid Claim");
            }
        }
        for (Map.Entry<ChunkKey, Integer> claim : liveClaims.entrySet()) {
            if (!claim.getKey().isLocalShard() || systemFaction.test(claim.getValue())
                    || baseClaims.isBaseClaim(claim.getValue(), claim.getKey())) continue;
            Entry existing = tracked.get(claim.getKey());
            if (existing == null || existing.factionId() != claim.getValue()) track(claim.getValue(), claim.getKey());
        }
    }

    private void startSweep() {
        if (!sweepInProgress.compareAndSet(false, true)) return;
        CompletableFuture<Void> sweep = CompletableFuture.runAsync(this::sweep);
        pendingSweeps.add(sweep);
        sweep.whenComplete((ignored, error) -> {
            pendingSweeps.remove(sweep);
            sweepInProgress.set(false);
            if (error != null) plugin.getLogger().log(Level.SEVERE, "Raid Claim expiration sweep failed.", error);
        });
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
        awaitWrites();
    }

    /** Waits for an expiration sweep that is already in progress. */
    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingSweeps.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for Raid Claim expiration writes.", error);
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<ChunkKey, Entry> entry : List.copyOf(tracked.entrySet())) {
            if (entry.getKey().isLocalShard() && now >= entry.getValue().expiresAtMillis()) {
                expire(entry.getKey());
            }
        }
    }

    /** Actually unclaims an expired chunk. Deliberately does not log -- see the class doc. */
    private synchronized void expire(ChunkKey key) {
        Entry entry = tracked.get(key);
        if (entry == null) {
            return;
        }
        int currentOwner = ownerQuery.applyAsInt(key);
        if (currentOwner != OWNER_QUERY_UNAVAILABLE && currentOwner != entry.factionId()) {
            deleteTrackedRow(key, entry, "stale Raid Claim expiration");
            return;
        }
        if (!claimRemover.apply(key, entry.factionId())) {
            plugin.getLogger().warning("Could not unclaim expired Raid Claim " + key
                    + "; retaining its timer so the next sweep can retry.");
            return;
        }
        deleteTrackedRow(key, entry, "expired Raid Claim");
    }

    private boolean deleteTrackedRow(ChunkKey key, Entry expected, String reason) {
        try {
            storage.deleteRaidClaim(key.world(), key.x(), key.z());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove " + reason + " row; it will be retried.", error);
            return false;
        }
        tracked.remove(key, expected);
        mutationPublisher.run();
        return true;
    }

    /** Registers a newly-claimed non-base chunk with a fresh expiration. Never logged -- see the class doc. */
    public synchronized void track(int factionId, ChunkKey chunk) {
        long expiresAt = freshExpirationMillis();
        try {
            storage.upsertRaidClaim(chunk.world(), chunk.x(), chunk.z(), factionId, expiresAt);
            tracked.put(chunk, new Entry(factionId, expiresAt));
            mutationPublisher.run();
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a new Raid Claim expiration.", error);
        }
    }

    public long freshExpirationMillis() {
        try { return Math.addExact(System.currentTimeMillis(), durationMillis); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    /** Updates the live cache after ClaimStorage committed a Base-to-Raid transaction. */
    public synchronized void adoptConverted(int factionId, java.util.Collection<ChunkKey> chunks,
            long expiresAt) {
        if (chunks == null) return;
        for (ChunkKey chunk : chunks) tracked.put(chunk, new Entry(factionId, expiresAt));
    }

    /** Updates only the live cache after FactionStorage committed the Raid row. */
    public synchronized void adoptPreclassified(int factionId, ChunkKey chunk, long expiresAt) {
        if (chunk != null && expiresAt > 0L) tracked.put(chunk, new Entry(factionId, expiresAt));
    }

    /** ClaimStorage already deleted these Raid rows while admitting them into a Base. */
    public synchronized void adoptBase(java.util.Collection<ChunkKey> chunks) {
        if (chunks != null) chunks.forEach(tracked::remove);
    }

    /** Drops cache state when another transaction already deleted the SQL row. */
    public synchronized void forget(ChunkKey chunk) {
        if (chunk != null) tracked.remove(chunk);
    }

    public synchronized void forgetFaction(int factionId) {
        tracked.entrySet().removeIf(entry -> entry.getValue().factionId() == factionId);
    }

    /** Stops tracking a chunk -- it either unclaimed on its own or joined a Base Claim region. */
    public synchronized void untrack(ChunkKey chunk) {
        Entry entry = tracked.get(chunk);
        if (entry == null) {
            return;
        }
        try {
            storage.deleteRaidClaim(chunk.world(), chunk.x(), chunk.z());
            tracked.remove(chunk, entry);
            mutationPublisher.run();
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to remove a tracked Raid Claim row; retaining it for retry.", error);
        }
    }

    /** Stops tracking every raid-claim row belonging to a faction before /f unclaimall or disband. */
    public synchronized void untrackFaction(int factionId) {
        for (Map.Entry<ChunkKey, Entry> entry : List.copyOf(tracked.entrySet())) {
            if (entry.getValue().factionId() == factionId) untrack(entry.getKey());
        }
    }

    public boolean isTracked(ChunkKey chunk) {
        return tracked.containsKey(chunk);
    }

    public long remainingSeconds(ChunkKey chunk) {
        Entry entry = tracked.get(chunk);
        return entry == null ? 0L : Math.max(0L, (entry.expiresAtMillis() - System.currentTimeMillis() + 999L) / 1_000L);
    }

    /** Absolute persisted expiry used by map/tooltips without exposing mutable claim state. */
    public long expiresAtMillis(ChunkKey chunk) {
        Entry entry = tracked.get(chunk);
        return entry == null ? 0L : entry.expiresAtMillis();
    }

    private record Entry(int factionId, long expiresAtMillis) { }
}
