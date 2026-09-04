package me.hcfcore.core.faction;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.lang.MessageFormatter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
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
        updateTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateRallyDisplay, 0L, 2L);
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

        // Calculate absolute direction towards rally (where player needs to run)
        float directionToRally = getDirectionToRally(player, rally);

        // Normalize to -180 to 180
        float normalizedDirection = directionToRally % 360;
        while (normalizedDirection > 180) normalizedDirection -= 360;
        while (normalizedDirection < -180) normalizedDirection += 360;

        // Determine arrow based on absolute direction to rally
        String arrowSymbol = getArrowForDirection(normalizedDirection);

        String rallyTitle = messages.getRaw(player, "factions.rally-bossbar-title");
        String distanceLabel = messages.getRaw(player, "factions.rally-bossbar-distance");
        Component text = Component.empty()
                .append(MessageFormatter.deserialize(rallyTitle + " "))
                .append(MessageFormatter.deserialize(distanceInt + " " + distanceLabel + " "))
                .append(MessageFormatter.deserialize(arrowSymbol));

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

    /**
     * A compass bearing to the rally (0=north, 90=east, 180=south,
     * 270/-90=west), matching the labels {@link #getArrowForDirection}
     * checks against. Minecraft's +Z axis is south, the opposite of the
     * standard "+Z is north" convention most atan2-bearing formulas
     * assume -- atan2(dx, dz) without negating dz had north and south
     * (and every diagonal) swapped; east/west happened to still come out
     * right since they don't involve dz at all, which is what made the
     * arrow look correct some of the time and backwards the rest.
     */
    private float getDirectionToRally(Player player, RallyPoint rally) {
        org.bukkit.Location from = player.getLocation();
        org.bukkit.Location to = rally.getLocation();

        if (from == null || to == null) return 0;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();

        float yaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
        return yaw;
    }

    private String getArrowForDirection(float direction) {
        // Determine arrow based on absolute direction to rally (where player needs to run)
        if (direction >= -22.5 && direction < 22.5) {
            return "↑"; // North
        } else if (direction >= 22.5 && direction < 67.5) {
            return "↗"; // Northeast
        } else if (direction >= 67.5 && direction < 112.5) {
            return "→"; // East
        } else if (direction >= 112.5 && direction < 157.5) {
            return "↘"; // Southeast
        } else if (direction >= 157.5 || direction < -157.5) {
            return "↓"; // South
        } else if (direction >= -157.5 && direction < -112.5) {
            return "↙"; // Southwest
        } else if (direction >= -112.5 && direction < -67.5) {
            return "←"; // West
        } else if (direction >= -67.5 && direction < -22.5) {
            return "↖"; // Northwest
        }
        return "↑";
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        hideBossBarForPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
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
