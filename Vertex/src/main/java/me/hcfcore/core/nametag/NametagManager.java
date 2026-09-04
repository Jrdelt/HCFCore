package me.hcfcore.core.nametag;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.MessageFormatter;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player nametags with FactionsUUID integration.
 * Dynamic faction-based coloring and real-time updates.
 * Configuration-driven via config.yml nametags section.
 */
public final class NametagManager {

    /**
     * Team names must fit in 16 characters -- that's the classic vanilla
     * scoreboard-team limit, and while a modern Paper-to-Paper connection
     * tolerates longer, a client on an older version (even bridged in via
     * ViaVersion) is still held to it, silently breaking nametags for
     * that client specifically. A full UUID is 36 characters on its own,
     * so teams are keyed by a 12-hex-digit hash of the UUID instead
     * ({@code nt} + hash = 14 chars) -- collisions are astronomically
     * unlikely at any real player count, and {@link #teamName} being a
     * pure function of the UUID means {@link #cleanupStaleTeams()} never
     * needs to reverse a name back into one.
     */
    private static final String TEAM_PREFIX = "nt";

    private final Plugin plugin;
    private final Scoreboard scoreboard;
    private final Map<String, PlayerNametagState> playerStates = new ConcurrentHashMap<>();
    private boolean enabled;
    private int updateIntervalTicks;
    private NamedTextColor sameFactionColor;
    private NamedTextColor neutralColor;
    private BukkitTask task;

    public NametagManager(Plugin plugin) {
        this.plugin = plugin;
        this.scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        loadConfiguration();
        if (enabled) {
            startNametagUpdateTask();
        }
    }

    /**
     * Re-reads {@code nametags.*} and applies it live -- without this,
     * {@code /hcfcore reload} left every setting here frozen at whatever
     * it was on server start, same class of bug {@link
     * me.hcfcore.core.chat.ChatFormatterListener} had for {@code chat.*}.
     * Starts or stops the update task if {@code enabled} changed, and
     * restarts it if the interval changed while already enabled.
     */
    public void reload() {
        boolean wasEnabled = enabled;
        int previousInterval = updateIntervalTicks;
        loadConfiguration();

        if (!enabled) {
            if (task != null) {
                task.cancel();
                task = null;
            }
            return;
        }
        if (!wasEnabled || task == null) {
            startNametagUpdateTask();
        } else if (updateIntervalTicks != previousInterval) {
            task.cancel();
            startNametagUpdateTask();
        }
    }

    private void loadConfiguration() {
        Configuration config = plugin.getConfig();
        enabled = config.getBoolean("nametags.enabled", true);
        updateIntervalTicks = config.getInt("nametags.update-interval-ticks", 20);

        sameFactionColor = parseColor(config.getString("nametags.colors.same-faction", "green"));
        neutralColor = parseColor(config.getString("nametags.colors.neutral", "gray"));
    }

    private NamedTextColor parseColor(String colorName) {
        NamedTextColor color = NamedTextColor.NAMES.value(colorName.toLowerCase());
        return color != null ? color : NamedTextColor.WHITE;
    }

    private static String teamName(UUID uuid) {
        long hash = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        return TEAM_PREFIX + String.format("%012x", hash & 0xFFFFFFFFFFFFL);
    }

    private void startNametagUpdateTask() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateAllNametags, 20, updateIntervalTicks);
    }

    private void updateAllNametags() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerNametag(player);
        }
    }

    public void updatePlayerNametag(Player player) {
        String playerId = player.getUniqueId().toString();
        int factionId = FactionsHook.getFactionId(player);
        String factionName = FactionsHook.getFactionName(factionId);

        // Check if nametag needs updating
        PlayerNametagState currentState = playerStates.get(playerId);
        if (currentState != null && currentState.factionId == factionId && currentState.factionName.equals(factionName)) {
            return; // No change, skip update
        }

        String teamName = teamName(player.getUniqueId());
        Team team = scoreboard.getTeam(teamName);

        // Create team if doesn't exist
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            team.addPlayer(player);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }

        // Build nametag: &8[&a<ftop>&8] &8[<factioncolor>(name)&8] &e<player>
        // Note: team prefixes are shared across every viewer -- Minecraft's
        // scoreboard API has no per-viewer color, so this can't show red to
        // enemies and purple to allies of the specific viewer looking at it.
        // It uses one fixed color: green for players in a faction, gray for
        // factionless, matching nametags.colors.same-faction/neutral in config.yml.
        String ftop = factionId == FactionsHook.NO_FACTION ? "-" : String.valueOf(FactionsHook.getFactionRank(factionId));
        NamedTextColor factionColor = factionId == FactionsHook.NO_FACTION ? neutralColor : sameFactionColor;
        String factionColorCode = toLegacyCode(factionColor);

        String prefix = "&8[&a" + ftop + "&8] &8[" + factionColorCode + "(" + factionName + ")&8] &e";
        team.prefix(MessageFormatter.deserialize(prefix));
        team.color(NamedTextColor.WHITE);

        // Store state for change detection
        playerStates.put(playerId, new PlayerNametagState(factionId, factionName));
    }

    private String toLegacyCode(NamedTextColor color) {
        if (color.equals(NamedTextColor.BLACK)) return "&0";
        if (color.equals(NamedTextColor.DARK_BLUE)) return "&1";
        if (color.equals(NamedTextColor.DARK_GREEN)) return "&2";
        if (color.equals(NamedTextColor.DARK_AQUA)) return "&3";
        if (color.equals(NamedTextColor.DARK_RED)) return "&4";
        if (color.equals(NamedTextColor.DARK_PURPLE)) return "&5";
        if (color.equals(NamedTextColor.GOLD)) return "&6";
        if (color.equals(NamedTextColor.GRAY)) return "&7";
        if (color.equals(NamedTextColor.DARK_GRAY)) return "&8";
        if (color.equals(NamedTextColor.BLUE)) return "&9";
        if (color.equals(NamedTextColor.GREEN)) return "&a";
        if (color.equals(NamedTextColor.AQUA)) return "&b";
        if (color.equals(NamedTextColor.RED)) return "&c";
        if (color.equals(NamedTextColor.LIGHT_PURPLE)) return "&d";
        if (color.equals(NamedTextColor.YELLOW)) return "&e";
        return "&f";
    }

    public void removePlayerNametag(Player player) {
        String teamName = teamName(player.getUniqueId());
        Team team = scoreboard.getTeam(teamName);
        if (team != null) {
            team.unregister();
        }
        playerStates.remove(player.getUniqueId().toString());
    }

    /**
     * Removes any of our teams left behind for players who aren't currently
     * online -- covers a team orphaned by a crash, a plugin reload racing a
     * quit, or any other path that skipped {@link #removePlayerNametag}.
     * Safe to call anytime; called from {@code HCFCorePlugin.reload()} since
     * that's the one path (unlike a normal disable) where the plugin keeps
     * running afterward with these teams still registered.
     */
    public void cleanupStaleTeams() {
        // teamName() is a hash, not reversible back to a UUID -- so instead
        // of parsing names, compute the expected name for every currently
        // online player and remove any of our teams that don't match one.
        Set<String> expectedNames = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            expectedNames.add(teamName(online.getUniqueId()));
        }
        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith(TEAM_PREFIX) && !expectedNames.contains(team.getName())) {
                team.unregister();
            }
        }
        playerStates.keySet().removeIf(uuidString -> Bukkit.getPlayer(UUID.fromString(uuidString)) == null);
    }

    public void shutdown() {
        // Clean up all nametag teams
        for (Team team : new ArrayList<>(scoreboard.getTeams())) {
            if (team.getName().startsWith(TEAM_PREFIX)) {
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
