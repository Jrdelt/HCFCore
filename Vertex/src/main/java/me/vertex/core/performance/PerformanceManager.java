package me.vertex.core.performance;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Vertex's one profiling/monitoring framework: an OFF/BASIC/DETAILED level
 * loaded from {@code performance.yml} (following this codebase's ad hoc
 * {@code YamlConfiguration} + clamped-value {@code load()} idiom -- see
 * {@code GcManager#load()}), a bounded in-memory stats store, and a single
 * timing-wrapper API ({@link #time(String, Runnable)}) any manager can call.
 *
 * <p><b>OFF is a real no-op, not just "no output."</b> Every instrumentation
 * entry point ({@link #time}, {@link #registerScheduledTask}) starts with a
 * single volatile-field read and branch; at OFF (and, for {@link #time},
 * also at BASIC -- see below) it calls straight through to the caller's work
 * with no {@link System#nanoTime()} call, no map lookup, and no allocation.
 * There is no separate "disabled" code path to keep in sync -- the guard
 * *is* the no-op.
 *
 * <p><b>BASIC vs. DETAILED.</b> BASIC is deliberately limited to the
 * high-level picture the spec asks for -- which scheduled/periodic
 * operations exist and how often they're configured to run ({@link
 * #registerScheduledTask}) -- and costs nothing per execution. Only
 * DETAILED actually times individual calls through {@link #time}; a label's
 * count/last/average/max are tracked with a bounded map ({@code
 * max-tracked-labels} in {@code performance.yml}) so a runaway or
 * dynamically-generated label set can never grow this without limit.
 *
 * <p><b>Diagnostic only.</b> Nothing here ever changes gameplay behavior --
 * {@link #time} always runs the supplied task exactly as given and never
 * swallows or alters what it does; monitoring can be flipped on/off at any
 * time via {@code /vertex reload} with zero effect beyond what this class
 * reports.
 *
 * <p><b>Scope.</b> Per the spec's own "do not add unnecessary optimization
 * complexity" instruction, this phase does not retrofit timing into every
 * existing manager -- only a couple of natural call sites (Chunk Buster's
 * batched removal tick, the dupe-investigation reconciliation pass) call
 * {@link #time} today. Any future manager can opt in the same way.
 */
public final class PerformanceManager {

    public enum Level { OFF, BASIC, DETAILED }

    private static final PerformanceManager DISABLED = new PerformanceManager();

    private final Plugin plugin;
    private final File file;

    private volatile Level level = Level.OFF;
    private volatile int maxTrackedLabels = 200;
    private volatile long monitoringSinceMillis;

    private final Map<String, Long> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<String, TaskStats> stats = new ConcurrentHashMap<>();

    public PerformanceManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "performance.yml");
    }

    /** Only for {@link #DISABLED} -- never touches disk, {@link #load()} is a no-op on it. */
    private PerformanceManager() {
        this.plugin = null;
        this.file = null;
    }

    /**
     * A shared, permanently-OFF instance for a manager that has not (yet, or
     * ever, e.g. in a unit test) been wired to the real one via a {@code
     * setPerformanceManager} setter -- calling {@link #time}/{@link
     * #registerScheduledTask} on it is always the same real no-op described
     * in the class doc.
     */
    public static PerformanceManager disabled() {
        return DISABLED;
    }

    public void load() {
        if (plugin == null) {
            return;
        }
        if (!file.exists()) {
            plugin.saveResource("performance.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Level parsed;
        String raw = normalizeLevelToken(config.get("monitoring-level"));
        try {
            parsed = Level.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("performance.yml: unknown monitoring-level '" + raw + "', using OFF.");
            parsed = Level.OFF;
        }
        maxTrackedLabels = Math.max(10, Math.min(2_000, config.getInt("max-tracked-labels", 200)));

        boolean wasActive = level != Level.OFF;
        level = parsed;
        if (level == Level.OFF) {
            scheduledTasks.clear();
            stats.clear();
        } else if (!wasActive) {
            monitoringSinceMillis = System.currentTimeMillis();
        }
    }

    /**
     * YAML 1.1 (which {@link YamlConfiguration} implements via SnakeYAML)
     * treats several unquoted scalars -- {@code true}/{@code false}/{@code
     * yes}/{@code no}/{@code on}/{@code off}, case-insensitively -- as
     * booleans rather than strings. {@code OFF} is both this framework's
     * valid, default level name <em>and</em> one of those tokens, so an
     * unquoted {@code monitoring-level: OFF} (exactly what this class's own
     * bundled {@code performance.yml} ships, and what any admin would
     * naturally type) parses as {@code Boolean.FALSE}, not the string
     * "OFF" -- without this, every default install would log a spurious
     * "unknown monitoring-level" warning on every load despite ending up at
     * the correct OFF level anyway. Recovers the intended token instead of
     * ever seeing that happen.
     */
    static String normalizeLevelToken(Object rawValue) {
        if (rawValue == null) {
            return "OFF";
        }
        if (rawValue instanceof Boolean bool) {
            return bool ? "TRUE" : "OFF";
        }
        return String.valueOf(rawValue);
    }

    public Level level() {
        return level;
    }

    public long monitoringSinceMillis() {
        return monitoringSinceMillis;
    }

    // ---- BASIC: task registry (no per-execution cost at any level) ----

    /**
     * Records that a periodic/scheduled operation exists and how often it is
     * configured to run -- the "which scheduled tasks exist and their
     * configured intervals" BASIC view the spec asks for. Safe to call every
     * time the owning manager's {@code load()} runs; a later call with the
     * same label simply updates its interval.
     */
    public void registerScheduledTask(String label, long intervalTicks) {
        if (level == Level.OFF) {
            return;
        }
        scheduledTasks.put(label, intervalTicks);
    }

    public List<ScheduledTaskInfo> scheduledTasks() {
        List<ScheduledTaskInfo> result = new ArrayList<>();
        scheduledTasks.forEach((label, interval) -> result.add(new ScheduledTaskInfo(label, interval)));
        result.sort(Comparator.comparing(ScheduledTaskInfo::label));
        return result;
    }

    public record ScheduledTaskInfo(String label, long intervalTicks) {
    }

    // ---- DETAILED: per-call timing ----

    /**
     * Runs {@code task}, timing it only when monitoring is at DETAILED. At
     * OFF or BASIC this is exactly {@code task.run()} -- no clock read, no
     * map access, no allocation -- so calling this unconditionally from a
     * hot path costs nothing unless an operator has explicitly opted into
     * DETAILED profiling.
     */
    public void time(String label, Runnable task) {
        if (level != Level.DETAILED) {
            task.run();
            return;
        }
        long startNanos = System.nanoTime();
        try {
            task.run();
        } finally {
            record(label, System.nanoTime() - startNanos);
        }
    }

    private void record(String label, long elapsedNanos) {
        TaskStats existing = stats.get(label);
        if (existing == null) {
            if (stats.size() >= maxTrackedLabels) {
                // Bounded on purpose -- see the class doc. A label that
                // arrives after the cap is simply not tracked rather than
                // evicting an older one, which is fine for a diagnostics
                // tool whose labels are a small, code-controlled set today.
                return;
            }
            existing = stats.computeIfAbsent(label, ignored -> new TaskStats());
        }
        existing.record(elapsedNanos);
    }

    public List<TaskStatsSnapshot> taskStats() {
        List<TaskStatsSnapshot> result = new ArrayList<>();
        stats.forEach((label, taskStats) -> result.add(taskStats.snapshot(label)));
        result.sort(Comparator.comparing(TaskStatsSnapshot::label));
        return result;
    }

    public record TaskStatsSnapshot(String label, long count, double avgMillis, double lastMillis, double maxMillis) {
    }

    private static final class TaskStats {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private volatile long lastNanos;
        private volatile long maxNanos;

        void record(long elapsedNanos) {
            count.increment();
            totalNanos.add(elapsedNanos);
            lastNanos = elapsedNanos;
            // Best-effort, not compare-and-swap-guarded: a lost update under
            // a genuine race would only under-report an outlier by one
            // sample on a diagnostics-only metric, never affect gameplay.
            if (elapsedNanos > maxNanos) {
                maxNanos = elapsedNanos;
            }
        }

        TaskStatsSnapshot snapshot(String label) {
            long samples = count.sum();
            double avgNanos = samples == 0 ? 0D : (double) totalNanos.sum() / samples;
            return new TaskStatsSnapshot(label, samples, avgNanos / 1_000_000D, lastNanos / 1_000_000D,
                    maxNanos / 1_000_000D);
        }
    }
}
