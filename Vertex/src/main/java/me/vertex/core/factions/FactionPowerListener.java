package me.vertex.core.factions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Configurable native faction-power death loss and online regeneration. */
public final class FactionPowerListener implements Listener {
    private final Plugin plugin;
    private final FactionService factions;
    private long intervalMillis;
    private double deathLoss;
    private double regeneration;
    private BukkitTask regenerationTask;
    /** Prevents the one-second ticker from using an expired offline deadline before login reset commits. */
    private final Set<UUID> startingSessions = ConcurrentHashMap.newKeySet();

    public FactionPowerListener(Plugin plugin, FactionService factions) {
        this.plugin = plugin; this.factions = factions; reloadConfig();
    }

    public void reloadConfig() {
        intervalMillis = Math.max(1_000L, plugin.getConfig().getLong("factions.power.regeneration-interval-seconds", 120L) * 1_000L);
        deathLoss = Math.max(0D, plugin.getConfig().getDouble("factions.power.death-loss", 1D));
        regeneration = Math.max(0D, plugin.getConfig().getDouble("factions.power.regeneration-per-interval", 1D));
        if (regenerationTask != null) regenerationTask.cancel();
        regenerationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::regenerate, 20L, 20L);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        startingSessions.add(uuid);
        factions.submitMutation(() -> factions.startPowerRegenerationSession(uuid))
                .whenComplete((started, error) -> {
                    if (error == null && Boolean.TRUE.equals(started)) startingSessions.remove(uuid);
                    else plugin.getLogger().warning("Power regeneration remains paused for " + uuid
                            + " because the online session deadline could not be saved.");
                });
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        java.util.UUID uuid = event.getEntity().getUniqueId();
        factions.submitMutation(() -> factions.adjustPersonalPower(uuid, -deathLoss));
    }

    private void regenerate() {
        if (regeneration <= 0D) return;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            java.util.UUID uuid = player.getUniqueId();
            if (startingSessions.contains(uuid)) continue;
            FactionPowerProfile profile = factions.powerProfile(uuid);
            if (profile == null) {
                factions.submitMutation(() -> factions.ensurePowerProfile(uuid));
            } else if (profile.current() < profile.maximum() && now >= profile.nextRegenerationAtMillis()) {
                factions.submitMutation(() -> factions.adjustPersonalPower(uuid, regeneration));
            }
        }
    }
}
