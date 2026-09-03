package me.hcfcore.core.nametag;

import me.hcfcore.core.factions.FactionsHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages player nametags with faction-based coloring.
 * Colors: Green (member), Yellow (neutral), Purple (ally), Red (enemy)
 * Range: 64 blocks
 */
public final class NametagManager {

    private final Plugin plugin;
    private final Scoreboard scoreboard;
    private final Map<String, Team> teamCache = new HashMap<>();
    private static final int NAMETAG_RANGE = 64;
    private static final int UPDATE_INTERVAL_TICKS = 20; // Update every second

    public NametagManager(Plugin plugin) {
        this.plugin = plugin;
        this.scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        startNametagUpdateTask();
    }

    private void startNametagUpdateTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAllNametags, 20, UPDATE_INTERVAL_TICKS);
    }

    private void updateAllNametags() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateNametags(player);
        }
    }

    private void updateNametags(Player viewer) {
        int viewerFactionId = FactionsHook.getFactionId(viewer);
        String viewerFactionName = FactionsHook.getFactionName(viewerFactionId);

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) {
                continue;
            }

            double distance = viewer.getLocation().distance(target.getLocation());
            if (distance > NAMETAG_RANGE) {
                continue;
            }

            int targetFactionId = FactionsHook.getFactionId(target);
            String targetFactionName = FactionsHook.getFactionName(targetFactionId);
            NamedTextColor color = getColorForFaction(viewerFactionId, targetFactionId);

            Team team = getOrCreateTeam(target.getName(), color, targetFactionName);
            team.addPlayer(target);
        }
    }

    private NamedTextColor getColorForFaction(int viewerFactionId, int targetFactionId) {
        if (targetFactionId == FactionsHook.NO_FACTION) {
            return NamedTextColor.YELLOW; // Neutral
        }
        if (viewerFactionId == targetFactionId && viewerFactionId != FactionsHook.NO_FACTION) {
            return NamedTextColor.GREEN; // Ally (same faction)
        }
        if (isAlly(viewerFactionId, targetFactionId)) {
            return NamedTextColor.LIGHT_PURPLE; // Ally (allied faction)
        }
        return NamedTextColor.RED; // Enemy
    }

    private boolean isAlly(int factionId1, int factionId2) {
        if (factionId1 == FactionsHook.NO_FACTION || factionId2 == FactionsHook.NO_FACTION) {
            return false;
        }
        // Use FactionsUUID alliance API
        return FactionsHook.isAlly(factionId1, factionId2);
    }

    private Team getOrCreateTeam(String playerName, NamedTextColor color, String factionName) {
        String teamName = "nametag_" + playerName;
        Team team = scoreboard.getTeam(teamName);

        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }

        team.color(color);
        team.prefix(Component.text("[" + factionName + "] ").color(color));
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);

        return team;
    }

    public void updatePlayerNametag(Player player) {
        int factionId = FactionsHook.getFactionId(player);
        String factionName = FactionsHook.getFactionName(factionId);

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(player)) {
                updateNametags(viewer);
            }
        }
    }

    public void removePlayerNametag(Player player) {
        String teamName = "nametag_" + player.getName();
        Team team = scoreboard.getTeam(teamName);
        if (team != null) {
            team.unregister();
        }
        teamCache.remove(player.getName());
    }

    public void shutdown() {
        // Clean up all teams
        for (Team team : new java.util.ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("nametag_")) {
                team.unregister();
            }
        }
    }
}
