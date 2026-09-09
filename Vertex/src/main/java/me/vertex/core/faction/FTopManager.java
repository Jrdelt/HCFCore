package me.vertex.core.faction;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.spawner.SpawnerData;
import me.vertex.core.spawner.SpawnerManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * The authoritative Vertex F Top snapshot. It deliberately scans tracked
 * spawners only at the configured cadence; normal gameplay merely changes
 * their persisted stack records.
 */
public final class FTopManager {

    private final Plugin plugin;
    private final SpawnerManager spawners;
    private final FTopStorage storage;
    private final File file;
    private volatile long agingMillis;
    private volatile long updateIntervalMillis;
    private volatile int displayLimit;
    private volatile Map<EntityType, Double> values = Map.of();
    private volatile Map<Integer, FTopStorage.Score> scores = Map.of();
    private volatile long nextUpdateAtMillis;
    private CompletableFuture<Void> writeChain = CompletableFuture.completedFuture(null);

    public FTopManager(Plugin plugin, SpawnerManager spawners, FTopStorage storage) {
        this.plugin = plugin;
        this.spawners = spawners;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "ftop.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("ftop.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        agingMillis = Math.max(60, config.getLong("aging-seconds", 86_400L)) * 1_000L;
        updateIntervalMillis = Math.max(60, config.getLong("update-interval-seconds", 600L)) * 1_000L;
        displayLimit = Math.max(1, Math.min(50, config.getInt("display-limit", 10)));
        Map<EntityType, Double> loaded = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("values");
        if (section != null) {
            for (String rawType : section.getKeys(false)) {
                try {
                    double value = Math.max(0D, section.getDouble(rawType));
                    loaded.put(EntityType.valueOf(rawType.toUpperCase(Locale.ROOT)), value);
                } catch (IllegalArgumentException error) {
                    plugin.getLogger().warning("Ignoring unknown F Top spawner type '" + rawType + "'.");
                }
            }
        }
        values = Map.copyOf(loaded);
    }

    public void loadState() {
        try {
            FTopStorage.Snapshot snapshot = storage.load();
            scores = Map.copyOf(snapshot.scores());
            nextUpdateAtMillis = snapshot.nextUpdateAt();
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load F Top state; starting a fresh schedule.", error);
            scores = Map.of();
            nextUpdateAtMillis = 0L;
        }
    }

    public void start() {
        long now = System.currentTimeMillis();
        if (nextUpdateAtMillis <= 0) {
            nextUpdateAtMillis = now + updateIntervalMillis;
            persist();
        }
        // This lightweight due-date check does no spawner/claim scanning.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (System.currentTimeMillis() >= nextUpdateAtMillis) {
                recalculateScheduled();
            }
        }, 20L, 20L);
        if (now >= nextUpdateAtMillis) {
            Bukkit.getScheduler().runTask(plugin, this::recalculateScheduled);
        }
    }

    /** Scheduled calculation advances the durable cadence. */
    public synchronized void recalculateScheduled() {
        long now = System.currentTimeMillis();
        if (now < nextUpdateAtMillis) {
            return;
        }
        calculate(now);
        nextUpdateAtMillis = now + updateIntervalMillis;
        persist();
    }

    /** Admin calculation intentionally leaves the automatic deadline untouched. */
    public synchronized void forceCheck() {
        calculate(System.currentTimeMillis());
        persist();
    }

    private void calculate(long now) {
        Map<Integer, Double> totals = new LinkedHashMap<>();
        for (Map.Entry<Location, SpawnerData> entry : spawners.getAllSpawners()) {
            Location location = entry.getKey();
            SpawnerData data = entry.getValue();
            int factionId = validClaimFaction(location, data);
            if (factionId == FactionsHook.NO_FACTION) {
                continue;
            }
            totals.merge(factionId, currentValue(data, now), Double::sum);
        }
        List<Map.Entry<Integer, Double>> ordered = new ArrayList<>(totals.entrySet());
        ordered.sort(Map.Entry.<Integer, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry::getKey));
        Map<Integer, FTopStorage.Score> next = new LinkedHashMap<>();
        for (int index = 0; index < ordered.size(); index++) {
            int factionId = ordered.get(index).getKey();
            FTopStorage.Score old = scores.get(factionId);
            next.put(factionId, new FTopStorage.Score(ordered.get(index).getValue(),
                    old == null ? index + 1 : old.currentRank(), index + 1));
        }
        scores = Map.copyOf(next);
    }

    private int validClaimFaction(Location location, SpawnerData data) {
        String claimTag = FactionsHook.getClaimFactionTag(location);
        if (claimTag == null || data.ownerFactionTag() == null
                || !claimTag.equalsIgnoreCase(data.ownerFactionTag())) {
            return FactionsHook.NO_FACTION;
        }
        return FactionsHook.getFactionIdByTag(claimTag);
    }

    public StackValue stackValue(Location location, SpawnerData data) {
        if (validClaimFaction(location, data) == FactionsHook.NO_FACTION) {
            return new StackValue(0D, 0D, 0D, 0L);
        }
        long now = System.currentTimeMillis();
        double fullValue = data.stackSize() * valueFor(data.mobType());
        double current = currentValue(data, now);
        double percent = fullValue <= 0 ? 0D : Math.min(100D, current / fullValue * 100D);
        long oldestRemaining = data.placedAtMillis().stream().mapToLong(placedAt ->
                Math.max(0L, agingMillis - (now - placedAt))).max().orElse(0L);
        return new StackValue(current, fullValue, percent, oldestRemaining);
    }

    private double currentValue(SpawnerData data, long now) {
        double unitValue = valueFor(data.mobType());
        if (unitValue <= 0) {
            return 0D;
        }
        double total = 0D;
        for (long placedAt : data.placedAtMillis()) {
            total += unitValue * Math.min(1D, Math.max(0D, now - placedAt) / (double) agingMillis);
        }
        return total;
    }

    private double valueFor(EntityType type) {
        Double configured = values.get(type);
        if (configured != null) {
            return configured;
        }
        SpawnerManager.MobConfig mob = spawners.getMobConfig(type);
        return mob == null ? 0D : mob.price();
    }

    public List<Entry> leaderboard() {
        return scores.entrySet().stream()
                .map(entry -> new Entry(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(entry -> entry.score().currentRank()))
                .limit(displayLimit)
                .toList();
    }

    public long remainingSeconds() {
        return Math.max(0L, (nextUpdateAtMillis - System.currentTimeMillis() + 999L) / 1_000L);
    }

    private synchronized void persist() {
        Map<Integer, FTopStorage.Score> snapshot = scores;
        long deadline = nextUpdateAtMillis;
        long now = System.currentTimeMillis();
        writeChain = writeChain.handle((ignored, error) -> null).thenRunAsync(() -> {
            try {
                storage.save(snapshot, deadline, now);
            } catch (Exception error) {
                plugin.getLogger().log(Level.SEVERE, "Failed to persist F Top snapshot.", error);
            }
        });
    }

    public record Entry(int factionId, FTopStorage.Score score) { }
    public record StackValue(double currentValue, double fullValue, double percent, long longestRemainingMillis) { }
}
