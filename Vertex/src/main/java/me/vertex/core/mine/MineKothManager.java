package me.vertex.core.mine;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.preferences.AnnouncementCategory;
import me.vertex.core.preferences.AnnouncementPreferenceManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Runs the Mine KOTHs: who is standing in each capture zone, what that does
 * to control, and what the holding faction earns for it.
 *
 * <p>Ownership is stored as the moment a faction took the point rather than
 * how long they have held it, so elapsed hold time is derived from the clock
 * and a reboot cannot hand anyone a fresh booster ladder.
 */
public final class MineKothManager {

    private final Plugin plugin;
    private final MineManager mines;
    private final MineKothStorage storage;
    private final Messages messages;
    private final AnnouncementPreferenceManager announcements;

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private CompletableFuture<Void> writeTail = CompletableFuture.completedFuture(null);

    private volatile long tickIntervalTicks = 20L;
    private BukkitTask task;
    /** Warns once per failure streak instead of once per tick. */
    private final java.util.Set<String> hologramFailures = ConcurrentHashMap.newKeySet();

    public MineKothManager(Plugin plugin, MineManager mines, MineKothStorage storage, Messages messages) {
        this(plugin, mines, storage, messages, null);
    }

    public MineKothManager(Plugin plugin, MineManager mines, MineKothStorage storage, Messages messages,
            AnnouncementPreferenceManager announcements) {
        this.plugin = plugin;
        this.mines = mines;
        this.storage = storage;
        this.messages = messages;
        this.announcements = announcements;
    }

    public void load() {
        states.clear();
        try {
            for (MineKothStorage.StoredKoth stored : storage.loadAll()) {
                states.put(stored.mineId(), new State(stored.ownerFaction(), stored.control(),
                        stored.ownedSinceMillis(), null, MineKothControl.State.IDLE));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Mine KOTH state.", e);
        }
        tickIntervalTicks = Math.max(1L, mines.kothTickIntervalTicks());
        restart();
    }

    private void restart() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (!mines.isEnabled()) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, tickIntervalTicks, tickIntervalTicks);
    }

    private void tick() {
        double seconds = tickIntervalTicks / 20D;
        for (MineKothDefinition definition : mines.kothDefinitions()) {
            if (!definition.isDefined()) {
                continue;
            }
            State before = states.getOrDefault(definition.mineId(), State.EMPTY);
            MineKothControl.Result result = MineKothControl.tick(
                    new MineKothControl.Snapshot(before.owner, before.control, before.capturing),
                    membersInZone(definition), seconds, definition.settings());

            long ownedSince = before.ownedSince;
            if (result.ownerChanged()) {
                ownedSince = System.currentTimeMillis();
            } else if (result.crossedResetThreshold() || result.lostControl()) {
                // Progression is wiped rather than paused: the ladder starts
                // again from its first stage if they win the point back.
                ownedSince = 0L;
            }

            State after = new State(result.ownerFactionId(), result.controlPercent(),
                    ownedSince, result.capturingFactionId(), result.state());
            states.put(definition.mineId(), after);

            updateHologram(definition, after);
            announce(definition, before, result);
            if (result.ownerChanged() || result.lostControl() || result.crossedResetThreshold()) {
                persist(definition.mineId(), after);
            }
        }
    }

    /** Eligible members per faction inside the zone. Factionless players cannot capture. */
    private Map<Integer, Integer> membersInZone(MineKothDefinition definition) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!definition.contains(player.getLocation())) {
                continue;
            }
            int factionId = FactionsHook.getFactionId(player);
            if (factionId == FactionsHook.NO_FACTION) {
                continue;
            }
            counts.merge(factionId, 1, Integer::sum);
        }
        return counts;
    }

    private void announce(MineKothDefinition definition, State before, MineKothControl.Result result) {
        String mine = mines.region(definition.mineId()) == null
                ? definition.mineId() : mines.region(definition.mineId()).displayName();
        if (result.ownerChanged()) {
            if (announcements != null) {
                announcements.broadcast(AnnouncementCategory.KOTH, "mines.koth-captured",
                        "mine", mine, "faction", factionName(result.ownerFactionId()));
            } else {
                Bukkit.broadcast(messages.get(Bukkit.getConsoleSender(), "mines.koth-captured",
                        "mine", mine, "faction", factionName(result.ownerFactionId())));
            }
            return;
        }
        // Warn the holder only as their control crosses each theft milestone.
        // A per-tick state check would keep sending the same alert while an
        // attacker remains in the zone.
        boolean holderIsBeingTaken = before.owner != null
                && before.owner.equals(result.ownerFactionId())
                && result.state() == MineKothControl.State.CAPTURING;
        int warningThreshold = MineKothControl.theftWarningThreshold(before.control, result.controlPercent());
        if (holderIsBeingTaken && warningThreshold > 0) {
            if (announcements == null) {
                FactionsHook.messageFaction(result.ownerFactionId(),
                        messages.get(Bukkit.getConsoleSender(), "mines.koth-contested",
                                "mine", mine, "control", String.valueOf(Math.round(result.controlPercent()))));
            } else {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (FactionsHook.getFactionId(player) == result.ownerFactionId()) {
                        announcements.send(player, AnnouncementCategory.KOTH, "mines.koth-contested",
                                "mine", mine, "control", String.valueOf(Math.round(result.controlPercent())));
                    }
                }
            }
        }
    }

    private String factionName(Integer factionId) {
        if (factionId == null) {
            return "-";
        }
        String name = FactionsHook.getFactionName(factionId);
        return name == null ? String.valueOf(factionId) : name;
    }

    /**
     * The Ore Drop bonus this faction currently earns in this world.
     *
     * <p>Only at full control: a point being worn away pays nothing until it
     * has been defended back to 100%.
     */
    public double boosterPercent(String worldName, int factionId) {
        for (MineKothDefinition definition : mines.kothDefinitions()) {
            if (!definition.isDefined() || !definition.world().equalsIgnoreCase(worldName)) {
                continue;
            }
            State state = states.get(definition.mineId());
            if (state == null || state.owner == null || state.owner != factionId
                    || state.control < 100D || state.ownedSince <= 0L) {
                return 0D;
            }
            long heldSeconds = (System.currentTimeMillis() - state.ownedSince) / 1000L;
            return definition.booster().percentFor(heldSeconds);
        }
        return 0D;
    }

    /** The mine whose KOTH governs this world, or null when the world has none. */
    public MineKothDefinition definitionForWorld(String worldName) {
        for (MineKothDefinition definition : mines.kothDefinitions()) {
            if (definition.isDefined() && definition.world().equalsIgnoreCase(worldName)) {
                return definition;
            }
        }
        return null;
    }

    public Integer ownerOf(String mineId) {
        State state = states.get(mineId);
        return state == null ? null : state.owner;
    }

    public double controlOf(String mineId) {
        State state = states.get(mineId);
        return state == null ? 0D : state.control;
    }

    /** Seconds the current holder has kept the point, or 0 when nobody has. */
    public long heldSeconds(String mineId) {
        State state = states.get(mineId);
        if (state == null || state.owner == null || state.ownedSince <= 0L) {
            return 0L;
        }
        return (System.currentTimeMillis() - state.ownedSince) / 1000L;
    }

    private void persist(String mineId, State state) {
        synchronized (writeLock) {
            writeTail = writeTail.handle((ignored, error) -> null).thenRunAsync(() -> {
                try {
                    me.vertex.core.storage.SqlRetry.run(plugin, "Mine KOTH save for " + mineId,
                            () -> storage.save(mineId, state.owner, state.control, state.ownedSince));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to persist Mine KOTH state for " + mineId + " after retries.", e);
                }
            });
        }
    }

    /** Drains older saves before a final flush so stale state cannot win a race. */
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
            plugin.getLogger().log(Level.WARNING, "Timed out waiting for Mine KOTH persistence.", error);
        }
    }

    /** Flushes every point's current state; called on shutdown so hold time is not lost. */
    public void flush() {
        Map<String, State> snapshot = new HashMap<>(states);
        snapshot.forEach((mineId, state) -> {
            try {
                storage.save(mineId, state.owner, state.control, state.ownedSince);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to flush Mine KOTH state for " + mineId, e);
            }
        });
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        awaitWrites();
        flush();
        // A Mine KOTH is permanent, but its hologram is not: leaving one
        // behind on shutdown would strand a stale board in the world that
        // nothing owns and nothing updates.
        for (MineKothDefinition definition : mines.kothDefinitions()) {
            removeHologram(definition);
        }
    }

    /**
     * Draws the point's board, in the same shape a scheduled KOTH uses.
     *
     * <p>Retried every tick regardless of a past failure, so a transient
     * problem -- DecentHolograms still starting, the chunk briefly unloaded
     * -- heals itself rather than leaving the board dead for good.
     */
    private void updateHologram(MineKothDefinition definition, State state) {
        if (!mines.kothHologramsEnabled() || !hologramsAvailable()) {
            return;
        }
        Location location = definition.hologramLocation();
        if (location == null) {
            return;
        }
        MineRegion region = mines.region(definition.mineId());
        long held = state.owner == null || state.ownedSince <= 0L
                ? 0L : (System.currentTimeMillis() - state.ownedSince) / 1000L;
        double booster = state.owner == null || state.control < 100D
                ? 0D : definition.booster().percentFor(held);
        MineKothBooster.Tier next = definition.booster().nextTier(held);

        List<String> lines = mines.kothHologramLines().stream()
                .map(line -> MessageFormatter.legacyAmpersand(line
                        .replace("{name}", region == null ? definition.mineId() : region.displayName())
                        .replace("{owner}", state.owner == null
                                ? messages.getRaw(null, "mines.koth-unclaimed") : factionName(state.owner))
                        .replace("{control}", String.format("%.0f", state.control))
                        .replace("{booster}", trimmed(booster))
                        .replace("{held}", state.owner == null ? "-" : formatDuration(held))
                        .replace("{next}", next == null ? "-" : "+" + trimmed(next.percent()) + "%")
                        .replace("{status}", messages.getRaw(null, "mines.koth-state-" + state.state.name().toLowerCase(Locale.ROOT)))))
                .toList();
        try {
            eu.decentsoftware.holograms.api.holograms.Hologram hologram =
                    eu.decentsoftware.holograms.api.DHAPI.getHologram(definition.hologramName());
            if (hologram == null) {
                eu.decentsoftware.holograms.api.DHAPI.createHologram(definition.hologramName(), location, true, lines);
            } else {
                eu.decentsoftware.holograms.api.DHAPI.setHologramLines(hologram, lines);
            }
            hologramFailures.remove(definition.hologramName());
        } catch (Throwable e) {
            // Throwable, not Exception: an incompatible DecentHolograms
            // upgrade throws LinkageError, which would otherwise escape and
            // abort the whole shared tick for every other point too.
            if (hologramFailures.add(definition.hologramName())) {
                plugin.getLogger().log(Level.WARNING, "Failed to update the Mine KOTH hologram for "
                        + definition.mineId() + " -- will keep retrying silently.", e);
            }
        }
    }

    private void removeHologram(MineKothDefinition definition) {
        hologramFailures.remove(definition.hologramName());
        if (!hologramsAvailable()) {
            return;
        }
        try {
            eu.decentsoftware.holograms.api.DHAPI.removeHologram(definition.hologramName());
        } catch (Throwable ignored) {
            // Already gone, or the API changed; either way it is not ours to update.
        }
    }

    private boolean hologramsAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("DecentHolograms");
    }

    private static String trimmed(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "-";
        }
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes > 0 ? minutes + "m" : seconds + "s";
    }

    private record State(Integer owner, double control, long ownedSince, Integer capturing,
            MineKothControl.State state) {
        private static final State EMPTY = new State(null, 0D, 0L, null, MineKothControl.State.IDLE);
    }
}
