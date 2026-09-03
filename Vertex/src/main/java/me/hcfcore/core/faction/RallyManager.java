package me.hcfcore.core.faction;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RallyManager implements Listener {
    private final Plugin plugin;
    private final Messages messages;
    private final Map<Integer, Long> rallyExpires = new ConcurrentHashMap<>(); // factionId -> expiry time
    private final Map<Integer, org.bukkit.Location> rallyLocations = new ConcurrentHashMap<>(); // factionId -> location
    private final Map<Player, BossBar> playerBossBars = new ConcurrentHashMap<>();
    private BukkitTask updateTask;
    private static final long RALLY_DURATION_MILLIS = 4 * 60 * 1000; // 4 minutes

    public RallyManager(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        startUpdateTask();
    }

    public void setRally(int factionId, org.bukkit.Location location) {
        rallyLocations.put(factionId, location.clone());
        rallyExpires.put(factionId, System.currentTimeMillis() + RALLY_DURATION_MILLIS);
    }

    public void setRallyExpiry(int factionId) {
        rallyExpires.put(factionId, System.currentTimeMillis() + RALLY_DURATION_MILLIS);
    }

    public void clearRally(int factionId) {
        rallyExpires.remove(factionId);
        rallyLocations.remove(factionId);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (FactionsHook.getFactionId(player) == factionId) {
                hideBossBarForPlayer(player);
            }
        }
    }

    private boolean isRallyActive(int factionId) {
        Long expiry = rallyExpires.get(factionId);
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            rallyExpires.remove(factionId);
            return false;
        }
        return true;
    }

    private RallyPoint getRallyFromFaction(int factionId) {
        org.bukkit.Location location = rallyLocations.get(factionId);
        if (location != null) {
            return new RallyPoint(factionId, location, System.currentTimeMillis());
        }
        return null;
    }

    private void startUpdateTask() {
        updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateRallyDisplay, 0L, 5L);
    }

    private void updateRallyDisplay() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int factionId = FactionsHook.getFactionId(player);
            if (factionId == FactionsHook.NO_FACTION) {
                hideBossBarForPlayer(player);
                continue;
            }

            if (!isRallyActive(factionId)) {
                hideBossBarForPlayer(player);
                continue;
            }

            RallyPoint rally = getRallyFromFaction(factionId);
            if (rally == null) {
                hideBossBarForPlayer(player);
                continue;
            }

            updatePlayerRallyDisplay(player, rally);
        }
    }

    private void updatePlayerRallyDisplay(Player player, RallyPoint rally) {
        org.bukkit.Location rallyLoc = rally.getLocation();
        if (rallyLoc == null) return;

        double distance = rally.getDistance(player);
        int distanceInt = (int) Math.round(distance);

        // Calculate direction towards rally
        float directionToRally = getDirectionToRally(player, rally);
        float playerYaw = player.getYaw() % 360;

        // Calculate relative angle (opposite of where player is facing)
        float relativeAngle = playerYaw - directionToRally;

        // Normalize to -180 to 180
        while (relativeAngle > 180) relativeAngle -= 360;
        while (relativeAngle < -180) relativeAngle += 360;

        // Determine arrow based on relative direction
        String arrowSymbol = getArrowForDirection(relativeAngle);

        Component text = Component.empty()
                .append(Component.text("FACTION RALLY ")
                        .color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(distanceInt + " Blocks Away ")
                        .color(NamedTextColor.WHITE))
                .append(Component.text(arrowSymbol)
                        .color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD));

        // Update or create bossbar
        BossBar bar = playerBossBars.get(player);
        if (bar == null) {
            bar = BossBar.bossBar(text, 1.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
            playerBossBars.put(player, bar);
            player.showBossBar(bar);
        } else {
            bar.name(text);
        }

        // Point compass towards rally
        player.setCompassTarget(rallyLoc);
    }

    private float getDirectionToRally(Player player, RallyPoint rally) {
        org.bukkit.Location from = player.getLocation();
        org.bukkit.Location to = rally.getLocation();

        if (from == null || to == null) return 0;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();

        float yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
        return yaw;
    }

    private String getArrowForDirection(float relativeAngle) {
        // Determine arrow based on where rally is relative to player's facing direction
        if (relativeAngle >= -22.5 && relativeAngle < 22.5) {
            return "↑"; // Rally ahead
        } else if (relativeAngle >= 22.5 && relativeAngle < 67.5) {
            return "↗"; // Rally forward-right
        } else if (relativeAngle >= 67.5 && relativeAngle < 112.5) {
            return "→"; // Rally right
        } else if (relativeAngle >= 112.5 && relativeAngle < 157.5) {
            return "↘"; // Rally back-right
        } else if (relativeAngle >= 157.5 || relativeAngle < -157.5) {
            return "↓"; // Rally behind
        } else if (relativeAngle >= -157.5 && relativeAngle < -112.5) {
            return "↙"; // Rally back-left
        } else if (relativeAngle >= -112.5 && relativeAngle < -67.5) {
            return "←"; // Rally left
        } else if (relativeAngle >= -67.5 && relativeAngle < -22.5) {
            return "↖"; // Rally forward-left
        }
        return "↑";
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        hideBossBarForPlayer(event.getPlayer());
    }

    private void hideBossBarForPlayer(Player player) {
        BossBar bar = playerBossBars.remove(player);
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    public void shutdown() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        rallyExpires.clear();
        rallyLocations.clear();
        playerBossBars.clear();
    }
}
