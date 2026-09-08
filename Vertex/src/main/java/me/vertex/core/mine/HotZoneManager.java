package me.vertex.core.mine;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Schedules and runs Mining Hot Zones.
 *
 * <p>A Hot Zone covers a whole mining world rather than a patch inside it,
 * so there is no region to check -- being in the world is the condition.
 *
 * <p>Timing is stored as absolute start/end timestamps, so a restart resumes
 * one mid-flight with the correct time left, and one that expired while the
 * server was down is simply over rather than resuming with its full duration.
 */
public final class HotZoneManager {

    private final Plugin plugin;
    private final MineManager mines;
    private final HotZoneStorage storage;
    private final Messages messages;

    private volatile boolean enabled;
    private volatile long minimumMinutes;
    private volatile long maximumMinutes;
    private volatile long durationMinutes;
    private volatile long preAnnounceMinutes;
    private volatile boolean revealWorldEarly;
    private volatile boolean preventImmediateRepeat;
    private volatile double oreDropPercent;
    private volatile boolean generationEnabled;
    private volatile double intensity;
    private volatile double rarityExponent;
    private volatile Map<Material, Double> overrides = Map.of();

    /** The mine currently hot, or null. */
    private volatile String activeMineId;
    private volatile long activeEndsAt;
    private volatile String lastMineId;
    private volatile long nextStartAt;
    private volatile boolean preAnnounced;

    private BukkitTask task;

    public HotZoneManager(Plugin plugin, MineManager mines, HotZoneStorage storage, Messages messages) {
        this.plugin = plugin;
        this.mines = mines;
        this.storage = storage;
        this.messages = messages;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "hotzones.yml");
        if (!file.exists()) {
            plugin.saveResource("hotzones.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        minimumMinutes = Math.max(1L, config.getLong("schedule.minimum-minutes", 60L));
        maximumMinutes = Math.max(minimumMinutes, config.getLong("schedule.maximum-minutes", 120L));
        durationMinutes = Math.max(1L, config.getLong("schedule.duration-minutes", 20L));
        preAnnounceMinutes = Math.max(0L, config.getLong("schedule.pre-announce-minutes", 5L));
        revealWorldEarly = config.getBoolean("schedule.reveal-world-early", false);
        preventImmediateRepeat = config.getBoolean("schedule.prevent-immediate-repeat", true);
        oreDropPercent = Math.max(0D, config.getDouble("booster.ore-drop-percent", 20D));
        generationEnabled = config.getBoolean("generation.enabled", true);
        intensity = Math.max(0D, config.getDouble("generation.intensity", 0.20D));
        rarityExponent = Math.max(0D, config.getDouble("generation.rarity-exponent", 0.5D));

        Map<Material, Double> loaded = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("generation.overrides");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material material = Material.matchMaterial(key.trim().toUpperCase(Locale.ROOT));
                if (material == null) {
                    plugin.getLogger().warning("hotzones.yml: unknown override material '" + key + "', ignoring it.");
                    continue;
                }
                loaded.put(material, section.getDouble(key, 1D));
            }
        }
        overrides = Map.copyOf(loaded);

        recoverState();
        restart();
    }

    /** Restores an in-flight Hot Zone, or treats one that expired while offline as finished. */
    private void recoverState() {
        activeMineId = null;
        activeEndsAt = 0L;
        long now = System.currentTimeMillis();
        try {
            long latestStart = Long.MIN_VALUE;
            for (HotZoneStorage.StoredHotZone stored : storage.loadAll()) {
                if (stored.startedAtMillis() > latestStart) {
                    latestStart = stored.startedAtMillis();
                    lastMineId = stored.mineId();
                }
                if (stored.isActive(now)) {
                    activeMineId = stored.mineId();
                    activeEndsAt = stored.endsAtMillis();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Hot Zone state.", e);
        }
        scheduleNext();
    }

    private void scheduleNext() {
        long span = maximumMinutes - minimumMinutes;
        long minutes = minimumMinutes + (span <= 0 ? 0 : ThreadLocalRandom.current().nextLong(span + 1));
        nextStartAt = System.currentTimeMillis() + minutes * 60_000L;
        preAnnounced = false;
    }

    private void restart() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!enabled) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (activeMineId != null) {
            if (now >= activeEndsAt) {
                end();
            }
            return;
        }
        if (preAnnounceMinutes > 0 && !preAnnounced && now >= nextStartAt - preAnnounceMinutes * 60_000L) {
            preAnnounced = true;
            Bukkit.broadcast(messages.get(Bukkit.getConsoleSender(), "mines.hotzone-soon",
                    "minutes", String.valueOf(preAnnounceMinutes),
                    "world", revealWorldEarly ? displayNameOf(peekNextMine()) : "???"));
        }
        if (now >= nextStartAt) {
            start();
        }
    }

    /** Eligible mines are placed ones; a repeat is avoided only when there is an alternative. */
    private List<String> candidates() {
        List<String> ids = new ArrayList<>();
        for (MineRegion region : mines.regions()) {
            if (region.isDefined()) {
                ids.add(region.id());
            }
        }
        if (preventImmediateRepeat && lastMineId != null && ids.size() > 1) {
            ids.remove(lastMineId);
        }
        return ids;
    }

    private String peekNextMine() {
        List<String> options = candidates();
        return options.isEmpty() ? null : options.get(0);
    }

    private void start() {
        List<String> options = candidates();
        if (options.isEmpty()) {
            // Nothing placed yet -- try again later rather than every tick.
            scheduleNext();
            return;
        }
        String chosen = options.get(ThreadLocalRandom.current().nextInt(options.size()));
        long now = System.currentTimeMillis();
        activeMineId = chosen;
        activeEndsAt = now + durationMinutes * 60_000L;
        lastMineId = chosen;
        persist(chosen, now, activeEndsAt);

        Bukkit.broadcast(messages.get(Bukkit.getConsoleSender(), "mines.hotzone-started",
                "world", displayNameOf(chosen),
                "percent", trimmed(oreDropPercent),
                "minutes", String.valueOf(durationMinutes)));
    }

    private void end() {
        String ended = activeMineId;
        activeMineId = null;
        activeEndsAt = 0L;
        scheduleNext();
        if (ended != null) {
            Bukkit.broadcast(messages.get(Bukkit.getConsoleSender(), "mines.hotzone-ended",
                    "world", displayNameOf(ended)));
        }
    }

    private void persist(String mineId, long startedAt, long endsAt) {
        try {
            storage.save(mineId, startedAt, endsAt);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to persist Hot Zone state for " + mineId, e);
        }
    }

    private String displayNameOf(String mineId) {
        MineRegion region = mineId == null ? null : mines.region(mineId);
        return region == null ? String.valueOf(mineId) : region.displayName();
    }

    public boolean isActive(String mineId) {
        return enabled && activeMineId != null && activeMineId.equals(mineId)
                && System.currentTimeMillis() < activeEndsAt;
    }

    /** The hot mine in this world, or null when that world is not hot. */
    public String activeMineInWorld(String worldName) {
        if (activeMineId == null || worldName == null) {
            return null;
        }
        MineRegion region = mines.region(activeMineId);
        return region != null && worldName.equalsIgnoreCase(region.world()) && isActive(activeMineId)
                ? activeMineId : null;
    }

    public double oreDropPercent() {
        return oreDropPercent;
    }

    public long remainingSeconds() {
        return activeMineId == null ? 0L : Math.max(0L, (activeEndsAt - System.currentTimeMillis()) / 1000L);
    }

    public long secondsUntilNext() {
        return Math.max(0L, (nextStartAt - System.currentTimeMillis()) / 1000L);
    }

    /** The mine's ore table, reweighted toward rarer ores while it is hot. */
    public MineOreTable tableFor(MineRegion region) {
        if (!generationEnabled || !isActive(region.id())) {
            return region.ores();
        }
        return region.ores().withHotZone(intensity, rarityExponent, overrides);
    }

    private static String trimmed(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
