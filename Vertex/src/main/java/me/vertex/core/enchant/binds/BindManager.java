package me.vertex.core.enchant.binds;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.RunePreferenceManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The single authority for {@code /binds} state: owns the in-memory {@link
 * PlayerBinds} cache (load-on-join, write-through to {@link BindStorage}
 * with per-player write chains, mirroring {@code
 * me.vertex.core.preferences.AnnouncementPreferenceManager}'s shape), the
 * 3-rune-per-bind cap (enforced inside {@link PlayerBinds} itself, so every
 * mutation path -- GUI, presets, legacy data -- shares one choke point),
 * and rank-gated preset entitlement.
 */
public final class BindManager implements Listener {

    private static final String OPENER_KEY = "activation-opener";
    private static final String DOUBLE_TAP_WINDOW_KEY = "double-tap-window-ms";

    private final Plugin plugin;
    private final BindStorage storage;
    private final EnchantManager enchants;
    private final RunePreferenceManager preferences;
    private final Map<UUID, PlayerBinds> cache = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private volatile long defaultDoubleTapWindowMillis = 400L;

    public BindManager(Plugin plugin, BindStorage storage, EnchantManager enchants, RunePreferenceManager preferences) {
        this.plugin = plugin;
        this.storage = storage;
        this.enchants = enchants;
        this.preferences = preferences;
        enchants.setOnNoLongerBindable(this::pruneRuneEverywhere);
    }

    /** Server default, read from {@code config.yml}'s {@code binds.double-tap-window-ms} -- a player's own preference still overrides it. */
    public void setDefaultDoubleTapWindowMillis(long millis) {
        this.defaultDoubleTapWindowMillis = Math.max(50L, millis);
    }

    // ------------------------------------------------------------------
    // Activation opener -- a global preference, never saved inside a preset
    // ------------------------------------------------------------------

    public BindActivationOpener activationOpener(Player player) {
        String raw = preferences.get(player.getUniqueId(), RunePreferenceManager.GLOBAL, OPENER_KEY, BindActivationOpener.SHIFT_F.name());
        try {
            return BindActivationOpener.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return BindActivationOpener.SHIFT_F;
        }
    }

    public BindActivationOpener cycleActivationOpener(Player player) {
        BindActivationOpener next = activationOpener(player).next();
        preferences.set(player.getUniqueId(), RunePreferenceManager.GLOBAL, OPENER_KEY, next.name());
        return next;
    }

    public long doubleTapWindowMillis(Player player) {
        try {
            return Long.parseLong(preferences.get(player.getUniqueId(), RunePreferenceManager.GLOBAL,
                    DOUBLE_TAP_WINDOW_KEY, String.valueOf(defaultDoubleTapWindowMillis)));
        } catch (NumberFormatException e) {
            return defaultDoubleTapWindowMillis;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
    }

    public PlayerBinds get(UUID uuid) {
        return cache.get(uuid);
    }

    private void load(Player player) {
        UUID uuid = player.getUniqueId();
        CompletableFuture.runAsync(() -> {
            try {
                PlayerBinds binds = new PlayerBinds();
                for (Map.Entry<Integer, List<String>> entry : storage.loadSlots(uuid).entrySet()) {
                    binds.loadBind(entry.getKey(), entry.getValue(), plugin.getLogger());
                }
                for (Map.Entry<Integer, Map<Integer, List<String>>> entry : storage.loadAllPresets(uuid).entrySet()) {
                    binds.loadPreset(entry.getKey(), entry.getValue(), plugin.getLogger());
                }
                BindStorage.ActivePreset active = storage.loadActivePreset(uuid);
                if (active != null) {
                    binds.loadActivePreset(active.presetIndex(), active.dirty());
                }
                cache.put(uuid, binds);
                enforcePresetLimit(player, binds);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not load /binds data for " + uuid, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Bindable-rune registry integrity (see EnchantManager#setOnNoLongerBindable)
    // ------------------------------------------------------------------

    private void pruneRuneEverywhere(String runeId) {
        enqueueGlobal(() -> {
            try {
                storage.pruneRuneEverywhere(runeId);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not prune non-bindable rune '" + runeId + "' from /binds storage", e);
            }
        });
        for (PlayerBinds binds : cache.values()) {
            binds.pruneRune(runeId);
        }
    }

    // ------------------------------------------------------------------
    // Bind editing -- every path funnels through PlayerBinds' own validation
    // ------------------------------------------------------------------

    public boolean setSlot(Player player, int bindIndex, int slotIndex, String runeId) {
        var definition = enchants.definition(runeId);
        if (definition == null || !definition.isBindable()) {
            return false;
        }
        PlayerBinds binds = get(player.getUniqueId());
        if (binds == null || !binds.setSlot(bindIndex, slotIndex, runeId)) {
            return false;
        }
        persistBind(player, binds, bindIndex);
        return true;
    }

    public void removeSlot(Player player, int bindIndex, int slotIndex) {
        PlayerBinds binds = get(player.getUniqueId());
        if (binds == null) {
            return;
        }
        binds.removeSlot(bindIndex, slotIndex);
        persistBind(player, binds, bindIndex);
    }

    public void reorder(Player player, int bindIndex, int slotIndex, int direction) {
        PlayerBinds binds = get(player.getUniqueId());
        if (binds == null) {
            return;
        }
        binds.reorder(bindIndex, slotIndex, direction);
        persistBind(player, binds, bindIndex);
    }

    private void persistBind(Player player, PlayerBinds binds, int bindIndex) {
        UUID uuid = player.getUniqueId();
        List<String> snapshot = binds.bind(bindIndex);
        enqueue(uuid, () -> {
            try {
                storage.saveBind(uuid, bindIndex, snapshot);
                storage.saveActivePreset(uuid, binds.activePresetIndex(), binds.dirty());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not save bind " + bindIndex + " for " + uuid, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Presets
    // ------------------------------------------------------------------

    /** Loading a preset never needs confirmation -- immediately replaces the live layout. */
    public boolean loadPreset(Player player, int presetIndex) {
        PlayerBinds binds = get(player.getUniqueId());
        if (binds == null || !binds.presetIndexes().contains(presetIndex)) {
            return false;
        }
        binds.loadPresetIntoActive(presetIndex);
        persistWholeLayout(player, binds);
        return true;
    }

    /** "Update Preset": explicitly overwrites the active preset with the current live layout. Requires the caller's own confirmation flow. */
    public void updateActivePreset(Player player) {
        PlayerBinds binds = get(player.getUniqueId());
        if (binds == null || binds.activePresetIndex() == null) {
            return;
        }
        binds.updateActivePresetFromLive();
        persistPreset(player, binds, binds.activePresetIndex());
        persistActiveMarker(player, binds);
    }

    /** @return false if the player is already at their preset entitlement limit. */
    public boolean saveAsNewPreset(Player player, int presetIndex) {
        if (presetIndex > presetLimit(player)) {
            return false;
        }
        PlayerBinds binds = get(player.getUniqueId());
        if (binds == null) {
            return false;
        }
        binds.saveAsNewPreset(presetIndex);
        persistPreset(player, binds, presetIndex);
        persistActiveMarker(player, binds);
        return true;
    }

    /** Requires the caller's own confirmation flow. */
    public void deletePreset(Player player, int presetIndex) {
        PlayerBinds binds = get(player.getUniqueId());
        if (binds == null) {
            return;
        }
        binds.deletePreset(presetIndex);
        UUID uuid = player.getUniqueId();
        enqueue(uuid, () -> {
            try {
                storage.deletePreset(uuid, presetIndex);
                storage.saveActivePreset(uuid, binds.activePresetIndex(), binds.dirty());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not delete preset " + presetIndex + " for " + uuid, e);
            }
        });
    }

    /** {@code vertex.binds.presets.7} (the hard max) then {@code .6}, first match wins, default 3 -- the boolean-permission idiom used everywhere else in this codebase, since no tiered numeric-limit convention exists to extend. */
    public int presetLimit(Player player) {
        if (player.hasPermission("vertex.binds.presets.7")) {
            return 7;
        }
        if (player.hasPermission("vertex.binds.presets.6")) {
            return 6;
        }
        return 3;
    }

    private void enforcePresetLimit(Player player, PlayerBinds binds) {
        int limit = presetLimit(player);
        List<Integer> excess = binds.presetsAbove(limit);
        if (excess.isEmpty()) {
            return;
        }
        for (Integer presetIndex : excess) {
            binds.deletePreset(presetIndex);
        }
        UUID uuid = player.getUniqueId();
        enqueue(uuid, () -> {
            try {
                storage.deletePresetsAbove(uuid, limit);
                storage.saveActivePreset(uuid, binds.activePresetIndex(), binds.dirty());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not enforce preset limit for " + uuid, e);
            }
        });
    }

    private void persistWholeLayout(Player player, PlayerBinds binds) {
        UUID uuid = player.getUniqueId();
        Map<Integer, List<String>> snapshot = binds.binds();
        enqueue(uuid, () -> {
            try {
                for (int bindIndex = 1; bindIndex <= PlayerBinds.MAX_BIND_INDEX; bindIndex++) {
                    storage.saveBind(uuid, bindIndex, snapshot.getOrDefault(bindIndex, List.of()));
                }
                storage.saveActivePreset(uuid, binds.activePresetIndex(), binds.dirty());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not save /binds layout for " + uuid, e);
            }
        });
    }

    private void persistPreset(Player player, PlayerBinds binds, int presetIndex) {
        UUID uuid = player.getUniqueId();
        Map<Integer, List<String>> snapshot = binds.preset(presetIndex);
        enqueue(uuid, () -> {
            try {
                storage.savePreset(uuid, presetIndex, snapshot);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not save preset " + presetIndex + " for " + uuid, e);
            }
        });
    }

    private void persistActiveMarker(Player player, PlayerBinds binds) {
        UUID uuid = player.getUniqueId();
        Integer activeIndex = binds.activePresetIndex();
        boolean dirty = binds.dirty();
        enqueue(uuid, () -> {
            try {
                storage.saveActivePreset(uuid, activeIndex, dirty);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not save active preset marker for " + uuid, e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Write chains
    // ------------------------------------------------------------------

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

    /** Not scoped to one player -- used for the global rune-removal prune, which touches every row regardless of who is online. */
    private void enqueueGlobal(Runnable operation) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(operation);
        pendingWrites.add(future);
        future.whenComplete((ignored, error) -> pendingWrites.remove(future));
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not finish /binds writes during shutdown", e);
        }
    }
}
