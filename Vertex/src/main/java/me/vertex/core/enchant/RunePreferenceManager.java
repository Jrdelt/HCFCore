package me.vertex.core.enchant;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Per-player rune preference overrides: the global rune sound volume, and
 * any per-rune override of a message/cooldown-message/sound/particle
 * setting -- an override inherits the corresponding global {@link
 * me.vertex.core.preferences.AnnouncementCategory} toggle when absent.
 * Mirrors {@code me.vertex.core.preferences.AnnouncementPreferenceManager}'s
 * load-on-join/cache/async-write-chain shape.
 */
public final class RunePreferenceManager implements Listener {

    public static final String GLOBAL = "";
    public static final String KEY_VOLUME = "volume";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_COOLDOWN_MESSAGE = "cooldown-message";
    public static final String KEY_SOUND = "sound";
    public static final String KEY_PARTICLE = "particle";

    private final Plugin plugin;
    private final RunePreferenceStorage storage;
    private final Map<UUID, Map<String, String>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();

    public RunePreferenceManager(Plugin plugin, RunePreferenceStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
    }

    public void load(UUID uuid) {
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, String> values = new ConcurrentHashMap<>();
                for (RunePreferenceStorage.Setting setting : storage.loadAll(uuid)) {
                    values.put(cacheKey(setting.runeId(), setting.settingKey()), setting.value());
                }
                cache.put(uuid, values);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load rune preferences for " + uuid, e);
            }
        });
    }

    /** @param runeId {@link #GLOBAL} for a global setting, or a specific enchant id for a per-rune override. */
    public String get(UUID uuid, String runeId, String settingKey, String fallback) {
        Map<String, String> values = cache.get(uuid);
        if (values == null) {
            return fallback;
        }
        String override = runeId.equals(GLOBAL) ? null : values.get(cacheKey(runeId, settingKey));
        if (override != null) {
            return override;
        }
        return values.getOrDefault(cacheKey(GLOBAL, settingKey), fallback);
    }

    public double globalVolume(UUID uuid) {
        try {
            return Math.max(0D, Math.min(100D, Double.parseDouble(get(uuid, GLOBAL, KEY_VOLUME, "100"))));
        } catch (NumberFormatException e) {
            return 100D;
        }
    }

    public void set(UUID uuid, String runeId, String settingKey, String value) {
        cache.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>()).put(cacheKey(runeId, settingKey), value);
        enqueue(uuid, () -> {
            try {
                storage.save(uuid, runeId, settingKey, value);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not save rune preference for " + uuid, e);
            }
        });
    }

    /** Clears a per-rune override so it falls back to the global toggle again. Not valid for {@link #GLOBAL} settings. */
    public void reset(UUID uuid, String runeId, String settingKey) {
        Map<String, String> values = cache.get(uuid);
        if (values != null) {
            values.remove(cacheKey(runeId, settingKey));
        }
        enqueue(uuid, () -> {
            try {
                storage.delete(uuid, runeId, settingKey);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not clear rune preference for " + uuid, e);
            }
        });
    }

    private void enqueue(UUID uuid, Runnable operation) {
        CompletableFuture<Void> previous = writeChains.getOrDefault(uuid, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> next = previous.handle((ignored, error) -> null).thenRunAsync(operation);
        writeChains.put(uuid, next);
        pendingWrites.add(next);
        next.whenComplete((ignored, error) -> {
            writeChains.remove(uuid, next);
            pendingWrites.remove(next);
        });
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(CompletableFuture[]::new)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not finish rune preference writes during shutdown", e);
        }
    }

    private static String cacheKey(String runeId, String settingKey) {
        return runeId + ":" + settingKey;
    }
}
