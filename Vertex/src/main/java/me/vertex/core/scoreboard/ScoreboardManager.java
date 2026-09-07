package me.vertex.core.scoreboard;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.ability.AbilityManager;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.PlaceholderResolver;
import me.vertex.core.staff.StaffManager;
import me.vertex.core.user.UserManager;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    private final String titleTemplate;
    private final DateTimeFormatter dateFormatter;
    private final List<String> lineTemplates;
    private final long intervalTicks;
    private final boolean usesOnlineCount;
    private final boolean usesFactionTop;
    private final Map<UUID, PlayerBoard> boards = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, String>> customPlaceholders = new ConcurrentHashMap<>();
    private volatile String lastRenderedTitle;
    private volatile StaffManager staffManager;
    private BukkitTask task;

    public ScoreboardManager(Plugin plugin, FileConfiguration config, UserManager userManager,
                             AbilityManager abilityManager) {
        this.plugin = plugin;
        this.titleTemplate = config.getString("scoreboard.title", "&b&lVertex");
        this.dateFormatter = DateTimeFormatter.ofPattern(config.getString("scoreboard.date-format", "MM/dd"));
        List<String> configuredLines = new ArrayList<>(config.getStringList("scoreboard.lines"));
        this.lineTemplates = List.copyOf(configuredLines);
        this.intervalTicks = Math.max(1, config.getLong("scoreboard.update-interval-ticks", 20));
        this.usesOnlineCount = lineTemplates.stream().anyMatch(line -> line.contains("{online}"));
        this.usesFactionTop = lineTemplates.stream().anyMatch(line -> line.contains("{ftop}"));
    }

    /**
     * Wired in after construction (StaffManager isn't constructed yet at
     * the point ScoreboardManager first is, in onEnable) -- same "set it
     * once it exists" pattern PlayerConnectionListener.setScoreboardManager
     * already uses for the reverse ordering problem.
     */
    public void setStaffManager(StaffManager staffManager) {
        this.staffManager = staffManager;
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
        customPlaceholders.clear();
    }

    public void setup(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("vertex", Criteria.DUMMY, currentTitleComponent());
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
        renderFor(player, usesOnlineCount ? onlineCountExcludingVanished() : 0,
                usesFactionTop ? FactionsHook.getFactionTopRanks() : Map.of());
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
        String resolvedTitle = resolveTitle();
        if (!resolvedTitle.equals(lastRenderedTitle)) {
            lastRenderedTitle = resolvedTitle;
            Component titleComponent = MessageFormatter.deserialize(resolvedTitle);
            for (PlayerBoard board : boards.values()) {
                board.objective().displayName(titleComponent);
            }
        }
        int onlineCount = usesOnlineCount ? onlineCountExcludingVanished() : 0;
        Map<Integer, String> factionTopRanks = usesFactionTop ? FactionsHook.getFactionTopRanks() : Map.of();
        for (Player player : Bukkit.getOnlinePlayers()) {
            renderFor(player, onlineCount, factionTopRanks);
        }
    }

    private String resolveTitle() {
        return titleTemplate.replace("{date}", LocalDate.now().format(dateFormatter));
    }

    private Component currentTitleComponent() {
        String resolved = resolveTitle();
        lastRenderedTitle = resolved;
        return MessageFormatter.deserialize(resolved);
    }

    private void renderFor(Player player, int onlineCount, Map<Integer, String> factionTopRanks) {
        PlayerBoard board = boards.get(player.getUniqueId());
        if (board == null) {
            return;
        }
        String factionTop = usesFactionTop
                ? factionTopRanks.getOrDefault(FactionsHook.getFactionId(player), "-")
                : null;

        for (int i = 0; i < board.teams.size(); i++) {
            String resolved = resolvePlaceholders(player, lineTemplates.get(i), onlineCount, factionTop);
            if (resolved.equals(board.lastRendered.get(i))) {
                continue;
            }
            board.lastRendered.set(i, resolved);
            board.teams.get(i).prefix(MessageFormatter.deserialize(resolved));
        }
    }

    /**
     * Bukkit.getOnlinePlayers() includes vanished players (vanish is
     * client-visibility-only) -- without filtering these out, a regular
     * player's sidebar shows an online count one higher than what tab/world
     * shows them, a passive but real "someone's watching" leak.
     */
    private int onlineCountExcludingVanished() {
        StaffManager currentStaffManager = staffManager;
        if (currentStaffManager == null) {
            return Bukkit.getOnlinePlayers().size();
        }
        int count = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!currentStaffManager.isVanished(online.getUniqueId())) {
                count++;
            }
        }
        return count;
    }

    private String resolvePlaceholders(Player player, String template, int onlineCount, String factionTop) {
        Map<String, String> custom = customPlaceholders.getOrDefault(player.getUniqueId(), Map.of());
        return PlaceholderResolver.resolve(player, template, onlineCount, factionTop, dateFormatter, custom);
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
