package me.vertex.core.tablist;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.PlaceholderResolver;
import me.vertex.core.luckperms.LuckPermsHook;
import me.vertex.core.staff.StaffManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives everyone's tab list off a single shared repeating task, the same
 * way {@link me.vertex.core.scoreboard.ScoreboardManager} drives the
 * sidebar -- a per-player entry name plus a header and footer, each
 * resent only when its resolved text actually changed.
 *
 * <p>Vanished staff are not given any special handling here on purpose:
 * {@code Player#hidePlayer(Plugin, Player)} (already used by
 * {@link StaffManager} for vanish) removes the target from the viewer's
 * tab list at the packet level as part of hiding them at all -- the same
 * mechanism vanilla vanish plugins rely on. Setting a vanished player's
 * {@code playerListName} still happens every tick like anyone else's; it
 * simply never reaches a viewer who can't already see them, since there's
 * no tab entry on that viewer's client to carry the name in the first
 * place.
 */
public final class TablistManager {

    private final Plugin plugin;
    private final boolean enabled;
    private final long intervalTicks;
    private final String formatTemplate;
    private final String headerTemplate;
    private final String footerTemplate;
    private final DateTimeFormatter dateFormatter;
    private final boolean usesOnlineCount;
    private final boolean usesFactionTop;
    private final Map<UUID, PlayerTabState> lastRendered = new ConcurrentHashMap<>();
    private final Map<UUID, HeaderFooterState> lastHeaderFooters = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastOrders = new ConcurrentHashMap<>();
    private final GroupedTablistRenderer groupedRenderer;
    private volatile StaffManager staffManager;
    private BukkitTask task;

    public TablistManager(Plugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.enabled = config.getBoolean("tablist.enabled", true);
        this.intervalTicks = Math.max(1, config.getLong("tablist.update-interval-ticks", 15));
        this.formatTemplate = config.getString("tablist.format", "{name}");
        this.headerTemplate = String.join("\n", config.getStringList("tablist.header"));
        this.footerTemplate = String.join("\n", config.getStringList("tablist.footer"));
        this.dateFormatter = DateTimeFormatter.ofPattern(config.getString("scoreboard.date-format", "MM/dd"));
        List<String> allTemplates = List.of(formatTemplate, headerTemplate, footerTemplate);
        this.usesOnlineCount = allTemplates.stream().anyMatch(template -> template.contains("{online}"));
        this.usesFactionTop = allTemplates.stream().anyMatch(template -> template.contains("{ftop}"));
        this.groupedRenderer = new GroupedTablistRenderer(config);
    }

    /** Wired in after construction -- StaffManager isn't built yet at the point TablistManager first is. */
    public void setStaffManager(StaffManager staffManager) {
        this.staffManager = staffManager;
    }

    public void start() {
        if (!enabled) {
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
    }

    /** Cancels the update task and restores every online player's tab list to vanilla defaults. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Grouped mode keeps its own render cache, so use the live player
        // list rather than only flat-mode's lastRendered keys.
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playerListName(null);
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
        lastRendered.clear();
        lastHeaderFooters.clear();
        lastOrders.clear();
        groupedRenderer.stop();
    }

    /** Renders immediately for one player (e.g. right on join) instead of waiting for the next tick. */
    public void renderNow(Player player) {
        if (!enabled) {
            return;
        }
        // Grouped mode owns player row names as well as ordering. Rendering
        // the flat form first caused joins, quits, and online-count changes
        // to overwrite its rank rows until another rank change happened.
        if (groupedRenderer.isEnabled()) {
            renderHeaderFooter(player, usesOnlineCount ? onlineCountExcludingVanished() : 0,
                    usesFactionTop ? FactionsHook.getFactionTopRanks() : Map.of());
            groupedRenderer.tick();
            return;
        }
        renderFor(player, usesOnlineCount ? onlineCountExcludingVanished() : 0,
                usesFactionTop ? FactionsHook.getFactionTopRanks() : Map.of());
    }

    /** Rebuilds every row immediately after LuckPerms finishes a join/meta refresh. */
    public void refreshNow() {
        if (enabled) {
            tick();
        }
    }

    /** LuckPerms can complete an online user's inherited-meta load just after join. */
    public void refreshAfterJoin() {
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshNow, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshNow, 20L);
    }

    public void remove(UUID uuid) {
        lastRendered.remove(uuid);
        lastHeaderFooters.remove(uuid);
        lastOrders.remove(uuid);
        groupedRenderer.remove(uuid);
    }

    private void tick() {
        int onlineCount = usesOnlineCount ? onlineCountExcludingVanished() : 0;
        Map<Integer, String> factionTopRanks = usesFactionTop ? FactionsHook.getFactionTopRanks() : Map.of();
        if (groupedRenderer.isEnabled()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                renderHeaderFooter(player, onlineCount, factionTopRanks);
            }
            groupedRenderer.tick();
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                renderFor(player, onlineCount, factionTopRanks);
            }
            applyLuckPermsOrdering();
        }
    }

    /** Flat tab lists still sort real players by their highest LuckPerms group weight. */
    private void applyLuckPermsOrdering() {
        List<RankGrouping.Entry> entries = new java.util.ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            LuckPermsHook.GroupInfo group = LuckPermsHook.getPrimaryGroupInfo(player);
            entries.add(new RankGrouping.Entry(player.getUniqueId(), player.getName(),
                    group == null ? null : group.id(), group == null ? null : group.displayName(),
                    group == null ? 0 : group.weight()));
        }
        for (RankGrouping.Row row : RankGrouping.buildRows(entries, "__no_rank__", "Members")) {
            if (!(row instanceof RankGrouping.PlayerRow playerRow)
                    || Integer.valueOf(playerRow.order()).equals(lastOrders.put(playerRow.playerId(), playerRow.order()))) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerRow.playerId());
            if (player != null) {
                player.setPlayerListOrder(playerRow.order());
            }
        }
    }

    private void renderFor(Player player, int onlineCount, Map<Integer, String> factionTopRanks) {
        String factionTop = usesFactionTop
                ? factionTopRanks.getOrDefault(FactionsHook.getFactionId(player), "-")
                : null;

        String name = resolve(player, formatTemplate, onlineCount, factionTop);
        String header = resolve(player, headerTemplate, onlineCount, factionTop);
        String footer = resolve(player, footerTemplate, onlineCount, factionTop);
        PlayerTabState state = new PlayerTabState(name, header, footer);

        if (state.equals(lastRendered.put(player.getUniqueId(), state))) {
            return;
        }
        player.playerListName(MessageFormatter.deserialize(name));
        player.sendPlayerListHeaderAndFooter(MessageFormatter.deserialize(header), MessageFormatter.deserialize(footer));
    }

    /** Grouped mode owns player rows but retains the normal configurable header and footer. */
    private void renderHeaderFooter(Player player, int onlineCount, Map<Integer, String> factionTopRanks) {
        String factionTop = usesFactionTop
                ? factionTopRanks.getOrDefault(FactionsHook.getFactionId(player), "-")
                : null;
        String header = resolve(player, headerTemplate, onlineCount, factionTop);
        String footer = resolve(player, footerTemplate, onlineCount, factionTop);
        HeaderFooterState state = new HeaderFooterState(header, footer);
        if (!state.equals(lastHeaderFooters.put(player.getUniqueId(), state))) {
            player.sendPlayerListHeaderAndFooter(MessageFormatter.deserialize(header), MessageFormatter.deserialize(footer));
        }
    }

    private String resolve(Player player, String template, int onlineCount, String factionTop) {
        return PlaceholderResolver.resolve(player, template, onlineCount, factionTop, dateFormatter, Map.of());
    }

    /** Same reasoning as ScoreboardManager's: vanish is client-visibility-only, so it's not reflected in a raw online-player count without filtering it out here. */
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

    private record PlayerTabState(String name, String header, String footer) {
    }

    private record HeaderFooterState(String header, String footer) {
    }
}
