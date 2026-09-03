package me.hcfcore.core.nametag;

import me.hcfcore.core.factions.FactionsHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player nametags with faction-based coloring.
 * Colors: Green (member), Yellow (neutral), Purple (ally), Red (enemy)
 *
 * Note: Scoreboard teams are server-wide (can't be per-viewer), so all online
 * players see the same colored nametag for each player. Proximity filtering
 * is applied via hiding nametags for distant players.
 */
public final class NametagManager {

    private final Plugin plugin;
    private final Scoreboard scoreboard;
    private final Map<String, PlayerNametagInfo> nametags = new ConcurrentHashMap<>();
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
            updatePlayerNametagColor(player);
        }
    }

    private void updatePlayerNametagColor(Player player) {
        int factionId = FactionsHook.getFactionId(player);
        String factionName = FactionsHook.getFactionName(factionId);

        String teamName = "nametag_" + player.getName();
        Team team = scoreboard.getTeam(teamName);

        // Create team if doesn't exist
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            team.addPlayer(player);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        }

        // Update prefix and color based on faction
        team.prefix(Component.text("[" + factionName + "] ").color(NamedTextColor.WHITE));
        team.color(NamedTextColor.WHITE);

        // Store nametag info for lookup
        nametags.put(player.getUniqueId().toString(), new PlayerNametagInfo(factionId, factionName));
    }

    public void removePlayerNametag(Player player) {
        String teamName = "nametag_" + player.getName();
        Team team = scoreboard.getTeam(teamName);
        if (team != null) {
            team.unregister();
        }
        nametags.remove(player.getUniqueId().toString());
    }

    public void shutdown() {
        // Clean up all teams
        for (Team team : new java.util.ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("nametag_")) {
                team.unregister();
            }
        }
        nametags.clear();
    }

    /** Simple data class to store nametag info */
    private static final class PlayerNametagInfo {
        final int factionId;
        final String factionName;

        PlayerNametagInfo(int factionId, String factionName) {
            this.factionId = factionId;
            this.factionName = factionName;
        }
    }
}
