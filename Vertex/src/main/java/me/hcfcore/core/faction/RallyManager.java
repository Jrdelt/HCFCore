package me.hcfcore.core.faction;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.FPlayers;
import dev.kitteh.factions.Faction;

import java.util.HashMap;
import java.util.Map;

public final class RallyManager {
    private final Plugin plugin;
    private final Messages messages;
    private final Map<Integer, Long> rallyExpires = new HashMap<>(); // factionId -> expiry time
    private final Map<Integer, org.bukkit.Location> rallyLocations = new HashMap<>(); // factionId -> location
    private static final long RALLY_DURATION_MILLIS = 4 * 60 * 1000; // 4 minutes

    public RallyManager(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
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
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateRallyDisplay, 0L, 5L);
    }

    private void updateRallyDisplay() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int factionId = FactionsHook.getFactionId(player);
            if (factionId == FactionsHook.NO_FACTION) {
                continue;
            }

            if (!isRallyActive(factionId)) {
                continue;
            }

            RallyPoint rally = getRallyFromFaction(factionId);
            if (rally == null) {
                continue;
            }

            updatePlayerRallyDisplay(player, rally);
        }
    }

    private void updatePlayerRallyDisplay(Player player, RallyPoint rally) {
        double distance = rally.getDistance(player);
        int distanceInt = (int) Math.round(distance);

        // Calculate direction towards rally
        float directionToRally = getDirectionToRally(player, rally);
        float playerYaw = player.getYaw() % 360;

        // Calculate relative angle (where rally is relative to player's view direction)
        float relativeAngle = directionToRally - playerYaw;

        // Normalize to -180 to 180
        while (relativeAngle > 180) relativeAngle -= 360;
        while (relativeAngle < -180) relativeAngle += 360;

        // Determine arrow based on relative direction
        String arrowSymbol = getArrowForDirection(relativeAngle);

        Component text = Component.text(arrowSymbol + " FACTION-RALLY: " + distanceInt + " Blocks Away!")
                .color(NamedTextColor.GREEN);

        player.sendActionBar(text);

        // Point compass towards rally
        player.setCompassTarget(rally.getLocation());
    }

    private float getDirectionToRally(Player player, RallyPoint rally) {
        org.bukkit.Location from = player.getLocation();
        org.bukkit.Location to = rally.getLocation();

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return yaw;
    }

    private String getArrowForDirection(float relativeAngle) {
        // Determine arrow based on where rally is relative to player's facing direction
        if (relativeAngle >= -22.5 && relativeAngle < 22.5) {
            return "⬇ "; // Rally ahead
        } else if (relativeAngle >= 22.5 && relativeAngle < 67.5) {
            return "⬇↙ "; // Rally forward-left
        } else if (relativeAngle >= 67.5 && relativeAngle < 112.5) {
            return "⬅ "; // Rally left
        } else if (relativeAngle >= 112.5 && relativeAngle < 157.5) {
            return "⬅↖ "; // Rally back-left
        } else if (relativeAngle >= 157.5 || relativeAngle < -157.5) {
            return "⬆ "; // Rally behind
        } else if (relativeAngle >= -157.5 && relativeAngle < -112.5) {
            return "⬆↗ "; // Rally back-right
        } else if (relativeAngle >= -112.5 && relativeAngle < -67.5) {
            return "➡ "; // Rally right
        } else if (relativeAngle >= -67.5 && relativeAngle < -22.5) {
            return "➡↘ "; // Rally forward-right
        }
        return "➜ ";
    }

    public void shutdown() {
        rallyExpires.clear();
        rallyLocations.clear();
    }
}
