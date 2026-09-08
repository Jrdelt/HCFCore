package me.vertex.core.mine;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();

    private volatile long tickIntervalTicks = 20L;
    private BukkitTask task;

    public MineKothManager(Plugin plugin, MineManager mines, MineKothStorage storage, Messages messages) {
        this.plugin = plugin;
        this.mines = mines;
        this.storage = storage;
        this.messages = messages;
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
            Bukkit.broadcast(messages.get(Bukkit.getConsoleSender(), "mines.koth-captured",
                    "mine", mine, "faction", factionName(result.ownerFactionId())));
            return;
        }
        // Tell the holder they are being taken from, once per contest rather
        // than every tick: only when the state actually turns to an attack.
        boolean nowUnderAttack = result.state() == MineKothControl.State.CAPTURING
                && before.state != MineKothControl.State.CAPTURING
                && result.ownerFactionId() != null;
        if (nowUnderAttack) {
            FactionsHook.messageFaction(result.ownerFactionId(),
                    messages.get(Bukkit.getConsoleSender(), "mines.koth-contested",
                            "mine", mine, "control", String.valueOf(Math.round(result.controlPercent()))));
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
        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> {
            try {
                storage.save(mineId, state.owner, state.control, state.ownedSince);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist Mine KOTH state for " + mineId, e);
            }
        });
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
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
        flush();
    }

    private record State(Integer owner, double control, long ownedSince, Integer capturing,
            MineKothControl.State state) {
        private static final State EMPTY = new State(null, 0D, 0L, null, MineKothControl.State.IDLE);
    }
}
