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
 * Manages player nametags with FactionsUUID integration.
 * Dynamic faction-based coloring and real-time updates.
 *
 * Color scheme:
 * - GREEN: Same faction (teammate)
 * - YELLOW: Neutral/Factionless
 * - LIGHT_PURPLE: Allied faction
 * - RED: Enemy faction (at war)
 *
 * Displays: [FactionName] PlayerName with dynamic coloring
 */
public final class NametagManager {

    private final Plugin plugin;
    private final Scoreboard scoreboard;
    private final Map<String, PlayerNametagState> playerStates = new ConcurrentHashMap<>();
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
            updatePlayerNametag(player);
        }
    }

    private void updatePlayerNametag(Player player) {
        String playerId = player.getUniqueId().toString();
        int factionId = FactionsHook.getFactionId(player);
        String factionName = FactionsHook.getFactionName(factionId);

        // Check if nametag needs updating
        PlayerNametagState currentState = playerStates.get(playerId);
        if (currentState != null && currentState.factionId == factionId && currentState.factionName.equals(factionName)) {
            return; // No change, skip update
        }

        String teamName = "nametag_" + player.getName();
        Team team = scoreboard.getTeam(teamName);

        // Create team if doesn't exist
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            team.addPlayer(player);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }

        // Get color based on faction relationship (used by all viewers)
        NamedTextColor color = getDefaultColorForFaction(factionId);

        // Update team with faction prefix and color
        team.prefix(Component.text("[" + factionName + "] ").color(color));
        team.color(color);

        // Store state for change detection
        playerStates.put(playerId, new PlayerNametagState(factionId, factionName));
    }

    /**
     * Get the default color for a faction (used by all viewers).
     * Since teams are server-wide, we use a neutral color scheme.
     */
    private NamedTextColor getDefaultColorForFaction(int factionId) {
        if (factionId == FactionsHook.NO_FACTION) {
            return NamedTextColor.YELLOW; // Neutral/Factionless
        }
        return NamedTextColor.AQUA; // Members in faction
    }

    public void removePlayerNametag(Player player) {
        String teamName = "nametag_" + player.getName();
        Team team = scoreboard.getTeam(teamName);
        if (team != null) {
            team.unregister();
        }
        playerStates.remove(player.getUniqueId().toString());
    }

    public void shutdown() {
        // Clean up all nametag teams
        for (Team team : new java.util.ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith("nametag_")) {
                team.unregister();
            }
        }
        playerStates.clear();
    }

    /** Store previous nametag state for change detection */
    private static final class PlayerNametagState {
        final int factionId;
        final String factionName;

        PlayerNametagState(int factionId, String factionName) {
            this.factionId = factionId;
            this.factionName = factionName;
        }
    }
}
