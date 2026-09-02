package me.hcfcore.core.scoreboard;

import me.hcfcore.core.factions.FactionsHook;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives a per-player sidebar off a single shared repeating task. Each line
 * is only re-rendered when its resolved text actually changes, to avoid
 * sending redundant scoreboard packets every tick.
 */
public final class ScoreboardManager {

    private static final String[] ENTRY_CODES = buildEntryCodes();

    private final Plugin plugin;
    private final Component title;
    private final List<String> lineTemplates;
    private final long intervalTicks;
    private final Map<UUID, PlayerBoard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, String>> customPlaceholders = new ConcurrentHashMap<>();
    private BukkitTask task;

    public ScoreboardManager(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.title = LegacyComponentSerializer.legacyAmpersand().deserialize(
                config.getString("scoreboard.title", "&b&lHCFCore"));
        this.lineTemplates = config.getStringList("scoreboard.lines");
        this.intervalTicks = Math.max(1, config.getLong("scoreboard.update-interval-ticks", 20));
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        for (UUID uuid : List.copyOf(boards.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
        boards.clear();
    }

    public void setup(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("hcfcore", Criteria.DUMMY, title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<Team> teams = new ArrayList<>();
        List<String> rendered = new ArrayList<>();
        int size = Math.min(lineTemplates.size(), ENTRY_CODES.length);

        for (int i = 0; i < size; i++) {
            String entry = ENTRY_CODES[i];
            Team lineTeam = scoreboard.registerNewTeam("line" + i);
            lineTeam.addEntry(entry);
            teams.add(lineTeam);

            int score = size - i;
            objective.getScore(entry).setScore(score);
            objective.getScore(entry).numberFormat(NumberFormat.blank());
            rendered.add(null);
        }

        player.setScoreboard(scoreboard);
        boards.put(player.getUniqueId(), new PlayerBoard(scoreboard, objective, teams, rendered));
        renderFor(player);
    }

    public void remove(UUID uuid) {
        boards.remove(uuid);
        customPlaceholders.remove(uuid);
    }

    /**
     * Lets other subsystems (e.g. the Repair ability's countdown) feed a
     * value into a scoreboard line without owning a second, conflicting
     * Scoreboard object -- admins opt in by adding the matching token
     * (e.g. "{repair}") to their configured scoreboard.lines.
     */
    public void setPlaceholder(UUID uuid, String key, String value) {
        customPlaceholders.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>()).put(key, value);
    }

    public void clearPlaceholder(UUID uuid, String key) {
        Map<String, String> placeholders = customPlaceholders.get(uuid);
        if (placeholders != null) {
            placeholders.remove(key);
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            renderFor(player);
        }
    }

    private void renderFor(Player player) {
        PlayerBoard board = boards.get(player.getUniqueId());
        if (board == null) {
            return;
        }

        for (int i = 0; i < board.teams.size(); i++) {
            String resolved = resolvePlaceholders(player, lineTemplates.get(i));
            if (resolved.equals(board.lastRendered.get(i))) {
                continue;
            }
            board.lastRendered.set(i, resolved);
            board.teams.get(i).prefix(LegacyComponentSerializer.legacyAmpersand().deserialize(resolved));
        }
    }

    private String resolvePlaceholders(Player player, String template) {
        Map<String, String> custom = customPlaceholders.getOrDefault(player.getUniqueId(), Map.of());
        return template
                .replace("{online}", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("{faction}", FactionsHook.getFactionTag(player))
                .replace("{faction_role}", FactionsHook.getRoleName(player))
                .replace("{repair}", custom.getOrDefault("repair", ""));
    }

    private static String[] buildEntryCodes() {
        String[] hex = "0123456789abcdef".split("");
        String[] codes = new String[hex.length * hex.length];
        int index = 0;
        for (String a : hex) {
            for (String b : hex) {
                codes[index++] = "§" + a + "§" + b;
            }
        }
        return codes;
    }

    private record PlayerBoard(Scoreboard scoreboard, Objective objective, List<Team> teams, List<String> lastRendered) {
    }
}
