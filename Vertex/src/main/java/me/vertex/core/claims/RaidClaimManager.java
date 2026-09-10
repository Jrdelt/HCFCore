package me.vertex.core.claims;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import dev.kitteh.factions.Board;
import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.Faction;

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

    private volatile long durationMillis;
    private volatile long sweepIntervalTicks;

    /** Only currently-tracked Raid Claim chunks -- the sweep never scans the whole world. */
    private final Map<ChunkKey, Entry> tracked = new ConcurrentHashMap<>();
    private BukkitTask task;

    public RaidClaimManager(Plugin plugin, ClaimStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "claims.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("claims.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        durationMillis = Math.max(60_000L, config.getLong("raid-claim.duration-seconds", 7L * 3_600L) * 1_000L);
        sweepIntervalTicks = Math.max(20L, config.getLong("raid-claim.sweep-interval-seconds", 30L) * 20L);
    }

    /** Loads every tracked row from storage. Expired rows are left in memory for {@link #recoverState()} to unclaim. */
    public void loadState() {
        tracked.clear();
        try {
            for (ClaimStorage.RaidClaimRow row : storage.loadRaidClaims()) {
                tracked.put(new ChunkKey(row.world(), row.chunkX(), row.chunkZ()),
                        new Entry(row.factionId(), row.expiresAtMillis()));
            }
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Raid Claim state; starting empty.", error);
        }
    }

    /** Unclaims anything that already expired while the server was down, then starts the sweep timer. */
    public void recoverState() {
        long now = System.currentTimeMillis();
        for (Map.Entry<ChunkKey, Entry> entry : List.copyOf(tracked.entrySet())) {
            if (now >= entry.getValue().expiresAtMillis()) {
                expire(entry.getKey());
            }
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, sweepIntervalTicks, sweepIntervalTicks);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
        }
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<ChunkKey, Entry> entry : List.copyOf(tracked.entrySet())) {
            if (now >= entry.getValue().expiresAtMillis()) {
                expire(entry.getKey());
            }
        }
    }

    /** Actually unclaims an expired chunk. Deliberately does not log -- see the class doc. */
    private void expire(ChunkKey key) {
        tracked.remove(key);
        try {
            storage.deleteRaidClaim(key.world(), key.x(), key.z());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove an expired Raid Claim row.", error);
        }
        World world = Bukkit.getWorld(key.world());
        if (world == null) {
            return;
        }
        try {
            FLocation flocation = new FLocation(world.getName(), key.x(), key.z());
            Faction faction = Board.board().factionAt(flocation);
            if (faction != null && !faction.isWilderness()) {
                Board.board().unclaim(flocation);
            }
        } catch (Throwable error) {
            // FactionsUUID is a soft dependency; if it isn't actually
            // running (e.g. unit tests, or a broken install) there is
            // nothing to unclaim -- the durable row is already gone above.
            plugin.getLogger().log(Level.WARNING, "Could not unclaim an expired Raid Claim chunk.", error);
        }
    }

    /** Registers a newly-claimed non-base chunk with a fresh expiration. Never logged -- see the class doc. */
    public void track(int factionId, ChunkKey chunk) {
        long expiresAt = System.currentTimeMillis() + durationMillis;
        tracked.put(chunk, new Entry(factionId, expiresAt));
        try {
            storage.upsertRaidClaim(chunk.world(), chunk.x(), chunk.z(), factionId, expiresAt);
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a new Raid Claim expiration.", error);
        }
    }

    /** Stops tracking a chunk -- it either unclaimed on its own or joined a Base Claim region. */
    public void untrack(ChunkKey chunk) {
        if (tracked.remove(chunk) == null) {
            return;
        }
        try {
            storage.deleteRaidClaim(chunk.world(), chunk.x(), chunk.z());
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove a tracked Raid Claim row.", error);
        }
    }

    public boolean isTracked(ChunkKey chunk) {
        return tracked.containsKey(chunk);
    }

    public long remainingSeconds(ChunkKey chunk) {
        Entry entry = tracked.get(chunk);
        return entry == null ? 0L : Math.max(0L, (entry.expiresAtMillis() - System.currentTimeMillis() + 999L) / 1_000L);
    }

    private record Entry(int factionId, long expiresAtMillis) { }
}
