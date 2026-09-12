package me.vertex.core.faction;

import me.vertex.core.capture.CaptureEventType;
import me.vertex.core.factions.FactionsHook;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Separate objective-point leaderboard; it never reads F Top/spawner value. */
public final class PvpTopManager {
    private final Plugin plugin;
    private final PvpTopStorage storage;
    private final File file;
    private volatile Map<Integer, Long> points = Map.of();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile Runnable mutationPublisher = () -> { };
    private final Object writeLock = new Object();
    private CompletableFuture<Void> writeTail = CompletableFuture.completedFuture(null);
    private volatile long kothPoints;
    private volatile long outpostPoints;
    private volatile int displayLimit;

    public PvpTopManager(Plugin plugin, PvpTopStorage storage) {
        this.plugin = plugin; this.storage = storage; this.file = new File(plugin.getDataFolder(), "pvptop.yml");
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("pvptop.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        kothPoints = Math.max(0L, config.getLong("awards.koth-capture", 10L));
        outpostPoints = Math.max(0L, config.getLong("awards.outpost-capture", 5L));
        displayLimit = Math.max(1, Math.min(50, config.getInt("display-limit", 10)));
    }

    public void loadState() {
        try { points = Map.copyOf(storage.load()); }
        catch (Exception error) { plugin.getLogger().log(Level.SEVERE, "Failed to load PvP Top points.", error); }
    }

    public void setMutationPublisher(Runnable publisher) {
        mutationPublisher = publisher == null ? () -> { } : publisher;
    }

    /** The same lane as local awards prevents an older query replacing a newer result. */
    public CompletableFuture<Void> refreshAsync() {
        synchronized (writeLock) {
            if (!refreshing.compareAndSet(false, true)) return writeTail;
            writeTail = writeTail.handle((ignored, error) -> null).thenRunAsync(() -> {
                try { loadState(); }
                finally { refreshing.set(false); }
            });
            return writeTail;
        }
    }

    public void awardCapture(CaptureEventType type, int factionId, Player actor, String eventId,
            String activationId) {
        if (factionId == FactionsHook.NO_FACTION) return;
        long amount = type == CaptureEventType.KOTH ? kothPoints : type == CaptureEventType.OUTPOST ? outpostPoints : 0L;
        if (amount <= 0) return;
        String actorUuid = actor == null ? null : actor.getUniqueId().toString();
        long now = System.currentTimeMillis();
        synchronized (writeLock) {
            writeTail = writeTail.handle((ignored, error) -> null).thenRunAsync(() -> {
                try {
                    String source = type.id() + ":" + eventId;
                    PvpTopStorage.AwardResult result = storage.award(factionId, amount, source,
                            source + ":" + activationId, actorUuid, now);
                    // Replace the whole projection so disbanded/removed factions disappear too.
                    points = Map.copyOf(storage.load());
                    if (result.awarded()) mutationPublisher.run();
                    if (result.awarded()) plugin.getLogger().info("PvP Top: "
                            + FactionsHook.getFactionName(factionId) + " received " + amount
                            + " points for " + type.id() + " " + eventId + ".");
                } catch (Exception error) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to award PvP Top points for " + eventId + ".", error);
                }
            });
        }
    }

    public void awaitWrites() {
        CompletableFuture<Void> pending;
        synchronized (writeLock) {
            pending = writeTail;
        }
        try {
            pending.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for PvP Top persistence.", error);
        }
    }

    public List<Entry> leaderboard() {
        return points.entrySet().stream().filter(entry -> entry.getValue() > 0L)
                .sorted(Map.Entry.<Integer, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
                .limit(displayLimit).map(entry -> new Entry(entry.getKey(), entry.getValue())).toList();
    }

    public record Entry(int factionId, long points) { }
}
