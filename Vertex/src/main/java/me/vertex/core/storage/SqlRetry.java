package me.vertex.core.storage;

import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Small bounded retry policy for idempotent background persistence writes.
 *
 * <p>This must only wrap operations that are safe to execute more than once
 * (an upsert, replacement, or delete). Currency deltas and other
 * non-idempotent operations require their own durable operation key instead.
 */
public final class SqlRetry {
    private SqlRetry() {
    }

    @FunctionalInterface
    public interface Operation {
        void run() throws Exception;
    }

    public static void run(Plugin plugin, String description, Operation operation) throws Exception {
        int attempts = Math.max(1, Math.min(10,
                plugin.getConfig().getInt("storage.retry.attempts", 3)));
        long delay = Math.max(0L, Math.min(5_000L,
                plugin.getConfig().getLong("storage.retry.initial-delay-millis", 100L)));
        long maximumDelay = Math.max(delay, Math.min(30_000L,
                plugin.getConfig().getLong("storage.retry.maximum-delay-millis", 1_000L)));

        Exception latest = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                operation.run();
                if (attempt > 1) {
                    plugin.getLogger().info(description + " recovered after " + attempt + " attempts.");
                }
                return;
            } catch (Exception error) {
                latest = error;
                if (attempt >= attempts || maintenanceFailure(error)) {
                    throw error;
                }
                plugin.getLogger().warning(description + " failed (attempt " + attempt + "/" + attempts
                        + "); retrying.");
                sleepWithJitter(delay);
                delay = Math.min(maximumDelay, Math.max(delay + 1L, delay * 2L));
            }
        }
        throw latest == null ? new SQLException(description + " failed") : latest;
    }

    private static boolean maintenanceFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("exclusive maintenance mode")) {
                return true;
            }
        }
        return false;
    }

    private static void sleepWithJitter(long delay) throws InterruptedException {
        if (delay <= 0L) {
            return;
        }
        long jitter = Math.max(1L, delay / 4L);
        Thread.sleep(delay + ThreadLocalRandom.current().nextLong(jitter));
    }
}
