package me.vertex.core.grace;

import me.vertex.core.factions.FactionsHook;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.UUID;
import java.util.logging.Level;

/** Global, restart-safe explosion protection for all ordinary faction claims. */
public final class GraceManager {
    private final Plugin plugin;
    private final GraceStorage storage;
    private final File file;
    private volatile long activeUntil;
    private volatile long maximumSeconds;

    public GraceManager(Plugin plugin, GraceStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "shield.yml");
    }

    public void load() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        maximumSeconds = Math.max(1, config.getLong("grace.maximum-duration-seconds", 30L * 86_400L));
        try { activeUntil = storage.load().map(GraceStorage.State::activeUntil).orElse(0L); }
        catch (Exception error) { plugin.getLogger().log(Level.SEVERE, "Failed to load Grace state.", error); }
    }

    public boolean isActive() { return activeUntil > System.currentTimeMillis(); }
    public long secondsRemaining() { return Math.max(0, (activeUntil - System.currentTimeMillis() + 999) / 1_000); }
    public long activeUntil() { return activeUntil; }

    public boolean isClaimProtected(Location location) {
        return isActive() && FactionsHook.getClaimFactionId(location) != FactionsHook.NO_FACTION;
    }

    public synchronized Result enable(long seconds, UUID actor) {
        if (seconds <= 0 || seconds > maximumSeconds) return Result.INVALID_DURATION;
        long now = System.currentTimeMillis();
        long until;
        try { until = Math.addExact(now, Math.multiplyExact(seconds, 1_000L)); }
        catch (ArithmeticException error) { return Result.INVALID_DURATION; }
        try {
            storage.saveAndLog(new GraceStorage.State(until, now, actor == null ? null : actor.toString()),
                    "ENABLED", "activeUntil=" + until);
            activeUntil = until;
            return Result.OK;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to enable Grace.", error);
            return Result.STORAGE_ERROR;
        }
    }

    public enum Result { OK, INVALID_DURATION, STORAGE_ERROR }

    public synchronized Result disable(UUID actor) {
        long now = System.currentTimeMillis();
        try {
            storage.saveAndLog(new GraceStorage.State(0, now, actor == null ? null : actor.toString()),
                    "DISABLED", null);
            activeUntil = 0;
            return Result.OK;
        } catch (Exception error) {
            plugin.getLogger().log(Level.SEVERE, "Failed to disable Grace.", error);
            return Result.STORAGE_ERROR;
        }
    }

}
