package me.vertex.core.nametag;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.Configuration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player nametags with FactionsUUID integration.
 * Dynamic faction-based coloring and real-time updates.
 * Configuration-driven via config.yml nametags section.
 *
 * <p>Teams live on each <b>viewer's own active scoreboard</b>, one team
 * per (viewer, subject) pair -- not on the main scoreboard. Every player
 * has their own {@link Scoreboard} object (assigned by
 * {@code ScoreboardManager} for the sidebar, replacing whatever scoreboard
 * they had before), and a team only renders for players whose *currently
 * active* scoreboard it's registered on. A shared main-scoreboard team was
 * tried first and only visible on that main scoreboard -- which nobody
 * stays on once {@code ScoreboardManager.setup()} gives them their own.
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
     * unlikely at any real player count.
     */
    private static final String TEAM_PREFIX = "nt";

    private final Plugin plugin;
    private final Map<String, PlayerNametagState> playerStates = new ConcurrentHashMap<>();
    private boolean enabled;
    private int updateIntervalTicks;
    private NamedTextColor sameFactionColor;
    private NamedTextColor allyColor;
    private NamedTextColor enemyColor;
    private NamedTextColor neutralColor;
    private BukkitTask task;

    public NametagManager(Plugin plugin) {
        this.plugin = plugin;
        loadConfiguration();
        if (enabled) {
            startNametagUpdateTask();
        }
    }

    /**
     * Re-reads {@code nametags.*} and applies it live -- without this,
     * {@code /vertex reload} left every setting here frozen at whatever
     * it was on server start, same class of bug {@link
     * me.vertex.core.chat.ChatFormatterListener} had for {@code chat.*}.
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
            clearVertexTeams();
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
        allyColor = parseColor(config.getString("nametags.colors.ally", "light_purple"));
        enemyColor = parseColor(config.getString("nametags.colors.enemy", "red"));
        neutralColor = parseColor(config.getString("nametags.colors.neutral", "red"));
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

    /**
     * The naive version of this is O(players^2): for every subject, push
     * their nametag to every viewer. At real HCF player counts that's the
     * single most expensive thing this plugin does on a tick. Two things
     * make it cheap in practice without changing what's rendered:
     *
     * <p>1. A subject's own faction id/name only depends on the subject,
     * not the viewer -- computed once per subject here, not once per
     * (subject, viewer) pair like {@link #buildPrefix} used to.
     *
     * <p>2. The rendered prefix (color + rank + faction name) is fully
     * determined by the pair (viewer's faction, subject's faction) -- every
     * subject sharing a faction gets byte-for-byte the same prefix for a
     * given viewer. Real servers cluster players into far fewer factions
     * than players, so caching the built {@link Component} per subject
     * faction id, once per viewer per cycle, turns what used to be one
     * MiniMessage parse and one relation lookup per player pair into one
     * per (viewer, distinct online faction) pair instead.
     */
    private void updateAllNametags() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        List<SubjectInfo> subjects = new ArrayList<>(online.size());
        for (Player subject : online) {
            int factionId = FactionsHook.getFactionId(subject);
            String factionName = FactionsHook.getFactionName(factionId);
            subjects.add(new SubjectInfo(subject, teamName(subject.getUniqueId()), factionId, factionName));
            playerStates.put(subject.getUniqueId().toString(), new PlayerNametagState(factionId, factionName));
        }

        for (Player viewer : online) {
            int viewerFactionId = FactionsHook.getFactionId(viewer);
            Scoreboard board = viewer.getScoreboard();
            Map<Integer, Component> prefixCache = new HashMap<>();
            for (SubjectInfo info : subjects) {
                Component prefix = prefixCache.computeIfAbsent(info.factionId(),
                        fid -> buildPrefix(viewerFactionId, fid, info.factionName()));
                applyTeamPrefix(board, info.teamName(), info.player(), prefix);
            }
        }
    }

    /**
     * Pushes `subject`'s current faction/nametag data to every online
     * viewer's own scoreboard. Unlike before, this can't skip on "subject's
     * own faction is unchanged" -- the color is relative to each *viewer*
     * (own faction green, ally light purple, everyone else red), so a
     * viewer's own faction changing, or a relation between two factions
     * changing, needs the same re-apply even when the subject's faction
     * itself didn't move.
     *
     * <p>Called per-player on join/faction-change, not on the bulk tick --
     * {@link #updateAllNametags()} has its own, more heavily cached path
     * for the every-tick case.
     */
    public void updatePlayerNametag(Player subject) {
        String subjectId = subject.getUniqueId().toString();
        int factionId = FactionsHook.getFactionId(subject);
        String factionName = FactionsHook.getFactionName(factionId);
        String teamName = teamName(subject.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyToViewer(viewer, subject, teamName, factionId, factionName);
        }
        playerStates.put(subjectId, new PlayerNametagState(factionId, factionName));
    }

    private record SubjectInfo(Player player, String teamName, int factionId, String factionName) {
    }

    /**
     * Populates `viewer`'s scoreboard with every currently-online subject's
     * nametag, bypassing the change-detection {@link #updatePlayerNametag}
     * does -- for a viewer whose scoreboard object was just replaced (a
     * fresh join, or {@code ScoreboardManager} rebuilding everyone's
     * sidebar scoreboard on {@code /vertex reload}), that replacement is
     * blank and needs every subject re-applied regardless of whether their
     * faction state happens to have "changed" recently.
     */
    public void applyAllNametagsTo(Player viewer) {
        for (Player subject : Bukkit.getOnlinePlayers()) {
            PlayerNametagState state = playerStates.get(subject.getUniqueId().toString());
            int factionId;
            String factionName;
            if (state != null) {
                factionId = state.factionId;
                factionName = state.factionName;
            } else {
                factionId = FactionsHook.getFactionId(subject);
                factionName = FactionsHook.getFactionName(factionId);
            }
            applyToViewer(viewer, subject, teamName(subject.getUniqueId()), factionId, factionName);
        }
    }

    private void applyToViewer(Player viewer, Player subject, String teamName, int subjectFactionId, String subjectFactionName) {
        applyTeamPrefix(viewer.getScoreboard(), teamName, subject,
                buildPrefix(FactionsHook.getFactionId(viewer), subjectFactionId, subjectFactionName));
    }

    /** Registers `subject`'s team on `board` if needed and sets its prefix -- no relation lookup here. */
    private void applyTeamPrefix(Scoreboard board, String teamName, Player subject, Component prefix) {
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
            team.addPlayer(subject);
            team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
            team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }
        team.prefix(prefix);
        team.color(NamedTextColor.WHITE);
    }

    /**
     * Build nametag: &8[&a<ftop>&8] &8[<relationcolor>name&8] &e<player>
     * The relation color is from `viewer`'s own perspective -- own faction,
     * allied faction, enemy faction, or neutral (a truce/no-relation
     * faction, or a factionless subject) -- matching
     * nametags.colors.same-faction/ally/enemy/neutral in config.yml. This
     * genuinely differs per viewer (unlike a shared main-scoreboard team
     * could) because each team lives on the *viewer's own* scoreboard
     * object -- see this class's top-level doc.
     */
    private Component buildPrefix(int viewerFactionId, int subjectFactionId, String subjectFactionName) {
        String ftop = subjectFactionId == FactionsHook.NO_FACTION ? "-" : String.valueOf(FactionsHook.getFactionRank(subjectFactionId));
        NamedTextColor relationColor;
        if (viewerFactionId != FactionsHook.NO_FACTION && viewerFactionId == subjectFactionId) {
            relationColor = sameFactionColor;
        } else if (FactionsHook.isAllyFaction(viewerFactionId, subjectFactionId)) {
            relationColor = allyColor;
        } else if (FactionsHook.isEnemyFaction(viewerFactionId, subjectFactionId)) {
            relationColor = enemyColor;
        } else {
            relationColor = neutralColor;
        }
        String prefix = "&8[&a" + ftop + "&8] &8[" + toLegacyCode(relationColor) + subjectFactionName + "&8] &e";
        return MessageFormatter.deserialize(prefix);
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

    /** Unregisters `subject`'s team from every online viewer's scoreboard. */
    public void removePlayerNametag(Player subject) {
        String teamName = teamName(subject.getUniqueId());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Team team = viewer.getScoreboard().getTeam(teamName);
            if (team != null) {
                team.unregister();
            }
        }
        playerStates.remove(subject.getUniqueId().toString());
    }

    public void shutdown() {
        clearVertexTeams();
    }

    /** Removes only Vertex relation teams, preserving teams owned by other plugins. */
    private void clearVertexTeams() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Team team : new ArrayList<>(viewer.getScoreboard().getTeams())) {
                if (team.getName().startsWith(TEAM_PREFIX)) {
                    team.unregister();
                }
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
