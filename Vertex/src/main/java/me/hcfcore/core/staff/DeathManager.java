package me.hcfcore.core.staff;

import me.hcfcore.core.storage.Storage;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class DeathManager {

    private final Plugin plugin;
    private final Storage storage;
    private final AtomicInteger pendingWrites = new AtomicInteger(0);
    // A death saved via saveDeath() and a /rollback run moments later both
    // used to go through Bukkit's async scheduler pool independently, which
    // has no ordering guarantee between separate submissions -- a rollback
    // run right after a death could race ahead of that death's own INSERT
    // and see it as "no previous deaths". Routing both through this single
    // thread makes them execute strictly in call order instead.
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "HCFCore-DeathIO");
        thread.setDaemon(true);
        return thread;
    });

    public DeathManager(Plugin plugin, Storage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public void saveDeath(UUID uuid, Death death) {
        pendingWrites.incrementAndGet();
        ioExecutor.submit(() -> {
            try {
                storage.saveDeath(uuid, death);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save death for " + uuid, e);
            } finally {
                pendingWrites.decrementAndGet();
            }
        });
    }

    public void loadDeaths(UUID uuid, int limit, DeathLoadCallback callback) {
        ioExecutor.submit(() -> {
            try {
                List<Death> deaths = storage.loadDeaths(uuid, limit);
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.onDeathsLoaded(deaths));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to load deaths for " + uuid, e);
                // A real DB error must not look identical to "genuinely no
                // deaths" -- the caller can't tell the difference otherwise,
                // and staff would be told a player has no death history when
                // the truth is the lookup itself failed.
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.onDeathsLoadFailed());
            }
        });
    }

    public void awaitWrites() {
        long timeout = System.currentTimeMillis() + 5000;
        while (pendingWrites.get() > 0 && System.currentTimeMillis() < timeout) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (pendingWrites.get() > 0) {
            plugin.getLogger().warning("Death writes did not complete within timeout");
        }
        ioExecutor.shutdown();
    }

    public interface DeathLoadCallback {
        void onDeathsLoaded(List<Death> deaths);

        /** Called instead of onDeathsLoaded when the lookup itself failed (not just empty). */
        default void onDeathsLoadFailed() {
            onDeathsLoaded(List.of());
        }
    }
}
