package me.vertex.core.performance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the one property that matters most for a monitoring framework that
 * is OFF by default: OFF must be a genuine no-op (nothing recorded, no
 * matter how many times the timing API is called), not merely "quiet."
 * Also covers BASIC vs. DETAILED's different scope, config parsing/clamping,
 * the {@link PerformanceManager#disabled()} sentinel every manager falls
 * back to before being wired up, and that the stats API holds up under
 * concurrent-ish usage (the real shape {@link PerformanceManager#time}
 * is called in -- from whatever thread a Bukkit task runs on).
 */
class PerformanceManagerTest {

    @TempDir
    Path dataFolder;

    private PluginMock plugin;
    private PerformanceManager manager;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        manager = new PerformanceManager(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void withLevel(String level) throws Exception {
        withLevel(level, 200);
    }

    private void withLevel(String level, int maxTrackedLabels) throws Exception {
        File file = new File(plugin.getDataFolder(), "performance.yml");
        Files.writeString(file.toPath(),
                "monitoring-level: " + level + "\nmax-tracked-labels: " + maxTrackedLabels + "\n",
                StandardCharsets.UTF_8);
        manager.load();
    }

    @Test
    void defaultsToOffWithNoConfigFileOnDisk() {
        manager.load();
        assertEquals(PerformanceManager.Level.OFF, manager.level());
    }

    @Test
    void offNeverRecordsAnythingNoMatterHowManyTimesTimeIsCalled() throws Exception {
        withLevel("OFF");
        for (int i = 0; i < 500; i++) {
            manager.time("some.task", () -> {
            });
        }
        assertTrue(manager.taskStats().isEmpty(), "OFF must record no stats whatsoever");
    }

    @Test
    void offStillRunsTheWrappedWorkItself() throws Exception {
        withLevel("OFF");
        AtomicInteger ran = new AtomicInteger();
        manager.time("some.task", ran::incrementAndGet);
        assertEquals(1, ran.get(), "the timing wrapper must never skip the actual work, at any level");
    }

    @Test
    void offIgnoresScheduledTaskRegistration() throws Exception {
        withLevel("OFF");
        manager.registerScheduledTask("some.task", 20L);
        assertTrue(manager.scheduledTasks().isEmpty());
    }

    @Test
    void basicRecordsScheduledTasksButNotPerCallTiming() throws Exception {
        withLevel("BASIC");
        manager.registerScheduledTask("chunkbuster.batch-removal", 5L);

        List<PerformanceManager.ScheduledTaskInfo> tasks = manager.scheduledTasks();
        assertEquals(1, tasks.size());
        assertEquals("chunkbuster.batch-removal", tasks.get(0).label());
        assertEquals(5L, tasks.get(0).intervalTicks());

        // BASIC is deliberately limited to the task registry -- no
        // per-execution timing overhead, so calling time() must not record
        // anything even though monitoring is active.
        for (int i = 0; i < 100; i++) {
            manager.time("some.task", () -> {
            });
        }
        assertTrue(manager.taskStats().isEmpty(), "BASIC must not record per-call timing stats");
    }

    @Test
    void basicStillRunsTheWrappedWorkItself() throws Exception {
        withLevel("BASIC");
        AtomicInteger ran = new AtomicInteger();
        manager.time("some.task", ran::incrementAndGet);
        assertEquals(1, ran.get());
    }

    @Test
    void detailedRecordsCountAndTiming() throws Exception {
        withLevel("DETAILED");
        for (int i = 0; i < 7; i++) {
            manager.time("chunkbuster.batch-removal", () -> {
            });
        }
        List<PerformanceManager.TaskStatsSnapshot> stats = manager.taskStats();
        assertEquals(1, stats.size());
        PerformanceManager.TaskStatsSnapshot snapshot = stats.get(0);
        assertEquals("chunkbuster.batch-removal", snapshot.label());
        assertEquals(7L, snapshot.count());
        assertTrue(snapshot.avgMillis() >= 0D);
        assertTrue(snapshot.maxMillis() >= 0D);
    }

    @Test
    void detailedTracksSeparateLabelsIndependently() throws Exception {
        withLevel("DETAILED");
        manager.time("a", () -> {
        });
        manager.time("a", () -> {
        });
        manager.time("b", () -> {
        });

        List<PerformanceManager.TaskStatsSnapshot> stats = manager.taskStats();
        assertEquals(2, stats.size());
        long countA = stats.stream().filter(s -> s.label().equals("a")).findFirst().orElseThrow().count();
        long countB = stats.stream().filter(s -> s.label().equals("b")).findFirst().orElseThrow().count();
        assertEquals(2L, countA);
        assertEquals(1L, countB);
    }

    @Test
    void switchingBackToOffClearsPreviouslyRecordedStatsAndTasks() throws Exception {
        withLevel("DETAILED");
        manager.time("some.task", () -> {
        });
        manager.registerScheduledTask("some.scheduled", 20L);
        assertEquals(1, manager.taskStats().size());
        assertEquals(1, manager.scheduledTasks().size());

        withLevel("OFF");
        assertTrue(manager.taskStats().isEmpty(), "turning monitoring off must drop stale in-memory stats");
        assertTrue(manager.scheduledTasks().isEmpty());
    }

    @Test
    void unknownMonitoringLevelFallsBackToOff() throws Exception {
        withLevel("NONSENSE");
        assertEquals(PerformanceManager.Level.OFF, manager.level());
    }

    // ---- YAML 1.1 boolean-coercion regression (see normalizeLevelToken's doc) ----
    //
    // YamlConfiguration parses an unquoted "OFF" -- exactly what an admin
    // would naturally type, and what this class's own bundled
    // performance.yml originally shipped -- as Boolean.FALSE, not the
    // string "OFF". Without normalizeLevelToken recovering the token, this
    // path would log a spurious "unknown monitoring-level 'false'" warning
    // on every single default-config load while still, coincidentally,
    // ending up at Level.OFF via the warning's own fallback -- which is why
    // an end-to-end level() assertion alone (see the "OFF" tests above)
    // cannot tell a correct parse apart from that fallback. These test the
    // normalization directly instead.

    @Test
    void normalizeLevelTokenRecoversOffFromYamlsUnquotedBooleanCoercion() {
        assertEquals("OFF", PerformanceManager.normalizeLevelToken(Boolean.FALSE),
                "an unquoted 'OFF'/'off' in performance.yml parses as Boolean.FALSE and must be recovered as OFF");
    }

    @Test
    void normalizeLevelTokenPassesThroughOrdinaryStringValuesUnchanged() {
        assertEquals("BASIC", PerformanceManager.normalizeLevelToken("BASIC"));
        assertEquals("DETAILED", PerformanceManager.normalizeLevelToken("DETAILED"));
    }

    @Test
    void normalizeLevelTokenDefaultsToOffWhenTheKeyIsAbsent() {
        assertEquals("OFF", PerformanceManager.normalizeLevelToken(null));
    }

    @Test
    void maxTrackedLabelsIsClampedToASaneRange() throws Exception {
        withLevel("DETAILED", 1);
        manager.time("first", () -> {
        });
        manager.time("second", () -> {
        });
        // The configured cap (1) is below the clamp floor (10), so both
        // labels should be tracked rather than the raw configured value
        // silently starving the framework down to a single label.
        assertEquals(2, manager.taskStats().size());
    }

    @Test
    void aRunawayLabelCountIsBoundedRatherThanGrowingWithoutLimit() throws Exception {
        withLevel("DETAILED", 10);
        for (int i = 0; i < 50; i++) {
            String label = "label-" + i;
            manager.time(label, () -> {
            });
        }
        assertTrue(manager.taskStats().size() <= 10, "the tracked-label count must never exceed the configured cap");
    }

    @Test
    void disabledSentinelIsAlwaysOffAndSafeToCallDirectly() {
        PerformanceManager disabled = PerformanceManager.disabled();
        assertEquals(PerformanceManager.Level.OFF, disabled.level());

        AtomicInteger ran = new AtomicInteger();
        disabled.time("some.task", ran::incrementAndGet);
        disabled.registerScheduledTask("some.task", 20L);

        assertEquals(1, ran.get(), "the disabled sentinel must still run the wrapped work");
        assertTrue(disabled.taskStats().isEmpty());
        assertTrue(disabled.scheduledTasks().isEmpty());
    }

    @Test
    void timeHoldsUpUnderConcurrentCallsFromMultipleThreads() throws Exception {
        withLevel("DETAILED");
        int threadCount = 8;
        int callsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < callsPerThread; i++) {
                        manager.time("concurrent.task", () -> {
                        });
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "all timing calls must finish without hanging");
        } finally {
            executor.shutdownNow();
        }

        List<PerformanceManager.TaskStatsSnapshot> stats = manager.taskStats();
        assertEquals(1, stats.size());
        assertEquals((long) threadCount * callsPerThread, stats.get(0).count(),
                "every concurrent call must be counted exactly once, with none lost or double-counted");
    }
}
