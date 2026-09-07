package me.vertex.core.tablist;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.essentials.EssentialsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.luckperms.LuckPermsHook;
import me.vertex.core.protocollib.ProtocolLibHook;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "separated by rank, highest LuckPerms weight first" tab list mode:
 * every online player gets a real row reading {@code <rank prefix>
 * <display name> [<faction>]}, grouped under a
 * header row per rank showing that rank's online count. The header's visible
 * text is only the rank label -- never a fake profile/player name.
 *
 * <p>Real rows are ordered with {@code Player#setPlayerListOrder} --
 * vanilla since 1.21.2, entirely independent of scoreboard teams, so this
 * shares no state with {@link me.vertex.core.nametag.NametagManager}'s
 * own per-viewer teams. Header rows aren't real players at all; Bukkit has
 * no API for a fake tab entry, so those are injected as raw PLAYER_INFO
 * packets through {@link ProtocolLibHook} and simply don't appear for
 * anyone if ProtocolLib isn't installed -- everything else here (the
 * sorted/grouped real rows) still works without it.
 *
 * <p>Vanished players are rendered exactly like anyone else here, same as
 * {@link TablistManager}'s flat mode: their row is computed and sent like
 * normal, it just never reaches a viewer who can't already see them
 * (vanish hides the underlying tab entry at the packet level). A header's
 * count can therefore include a vanished member that a given viewer can't
 * actually see listed -- a minor, staff-only discrepancy accepted for the
 * same reason TablistManager's own doc comment gives: not worth a second,
 * per-viewer count just to close a gap nobody but staff would notice.
 */
final class GroupedTablistRenderer {

    private static final String FALLBACK_GROUP_ID = "__no_rank__";

    private final boolean enabled;
    private final String playerFormat;
    private final String headerFormat;
    private final String fallbackGroupName;

    private final Map<UUID, Integer> lastOrder = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastPlayerText = new ConcurrentHashMap<>();
    private final Map<String, HeaderState> activeHeaders = new ConcurrentHashMap<>();

    private record HeaderState(UUID fakeId, String fakeUsername, String text, int order) {
    }

    GroupedTablistRenderer(FileConfiguration config) {
        this.enabled = config.getBoolean("tablist.grouped.enabled", false);
        this.playerFormat = config.getString("tablist.grouped.player-format", "{prefix}<white>{name} <gray>{faction}");
        this.headerFormat = config.getString("tablist.grouped.header-format", "{prefix}<bold>{group} <gray>({count})");
        this.fallbackGroupName = config.getString("tablist.grouped.fallback-group-name", "Members");
    }

    boolean isEnabled() {
        return enabled;
    }

    /** Recomputes every row and applies whatever changed -- real order/name, and the shared header set. */
    void tick() {
        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        Map<UUID, Player> playersById = new HashMap<>();
        Map<String, String> representativePrefixByGroup = new HashMap<>();
        List<RankGrouping.Entry> entries = new ArrayList<>(online.size());

        for (Player player : online) {
            playersById.put(player.getUniqueId(), player);
            LuckPermsHook.GroupInfo group = LuckPermsHook.getPrimaryGroupInfo(player);
            String prefix = prefixOrEmpty(player);
            String groupId = group == null ? null : group.id();
            representativePrefixByGroup.putIfAbsent(groupId == null ? FALLBACK_GROUP_ID : groupId, prefix);
            entries.add(new RankGrouping.Entry(player.getUniqueId(), player.getName(), groupId,
                    group == null ? null : group.displayName(), group == null ? 0 : group.weight()));
        }

        List<RankGrouping.Row> rows = RankGrouping.buildRows(entries, FALLBACK_GROUP_ID, fallbackGroupName);
        Map<String, HeaderState> newHeaders = new HashMap<>();

        for (RankGrouping.Row row : rows) {
            if (row instanceof RankGrouping.PlayerRow playerRow) {
                applyPlayerRow(playersById.get(playerRow.playerId()), playerRow, representativePrefixByGroup);
            } else if (row instanceof RankGrouping.HeaderRow headerRow) {
                String prefix = representativePrefixByGroup.getOrDefault(headerRow.groupId(), "");
                String text = headerFormat
                        .replace("{prefix}", prefix)
                        .replace("{group}", headerRow.displayName())
                        .replace("{count}", String.valueOf(headerRow.count()));
                UUID fakeId = fakeHeaderId(headerRow.groupId());
                newHeaders.put(headerRow.groupId(), new HeaderState(fakeId, fakeUsername(headerRow.groupId()), text, headerRow.order()));
            }
        }

        reconcileHeaders(online, newHeaders);
    }

    /** Sends every currently active header to one viewer, e.g. right on join -- headers only otherwise refresh on the shared tick. */
    void renderNow(Player viewer) {
        if (!enabled) {
            return;
        }
        for (HeaderState state : activeHeaders.values()) {
            ProtocolLibHook.upsertFakeEntry(viewer, state.fakeId(), state.fakeUsername(),
                    MessageFormatter.deserialize(state.text()), state.order());
        }
    }

    void remove(UUID uuid) {
        lastOrder.remove(uuid);
        lastPlayerText.remove(uuid);
    }

    /** Removes every fake header from every online viewer and forgets all cached state. */
    void stop() {
        if (!activeHeaders.isEmpty()) {
            List<UUID> fakeIds = activeHeaders.values().stream().map(HeaderState::fakeId).toList();
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                ProtocolLibHook.removeFakeEntries(viewer, fakeIds);
            }
        }
        activeHeaders.clear();
        lastOrder.clear();
        lastPlayerText.clear();
    }

    private void applyPlayerRow(Player player, RankGrouping.PlayerRow row, Map<String, String> representativePrefixByGroup) {
        if (player == null) {
            return;
        }
        String prefix = prefixOrEmpty(player);
        String faction = FactionsHook.getFactionTag(player);
        String factionText = "None".equals(faction) ? "" : "[" + faction + "]";
        String displayName = EssentialsHook.resolveName(player);
        String text = playerFormat
                .replace("{prefix}", prefix)
                .replace("{name}", displayName)
                .replace("{username}", player.getName())
                .replace("{faction}", factionText);

        UUID uuid = player.getUniqueId();
        if (!Integer.valueOf(row.order()).equals(lastOrder.put(uuid, row.order()))) {
            player.setPlayerListOrder(row.order());
        }
        if (!text.equals(lastPlayerText.put(uuid, text))) {
            player.playerListName(MessageFormatter.deserialize(text));
        }
    }

    private void reconcileHeaders(List<Player> viewers, Map<String, HeaderState> newHeaders) {
        List<UUID> toRemove = new ArrayList<>();
        for (Map.Entry<String, HeaderState> existing : activeHeaders.entrySet()) {
            if (!newHeaders.containsKey(existing.getKey())) {
                toRemove.add(existing.getValue().fakeId());
            }
        }
        if (!toRemove.isEmpty()) {
            for (Player viewer : viewers) {
                ProtocolLibHook.removeFakeEntries(viewer, toRemove);
            }
        }

        for (Map.Entry<String, HeaderState> entry : newHeaders.entrySet()) {
            HeaderState state = entry.getValue();
            if (state.equals(activeHeaders.get(entry.getKey()))) {
                continue;
            }
            Component text = MessageFormatter.deserialize(state.text());
            for (Player viewer : viewers) {
                ProtocolLibHook.upsertFakeEntry(viewer, state.fakeId(), state.fakeUsername(), text, state.order());
            }
        }

        activeHeaders.clear();
        activeHeaders.putAll(newHeaders);
    }

    private static String prefixOrEmpty(Player player) {
        String prefix = LuckPermsHook.getPrefix(player);
        return prefix == null ? "" : prefix;
    }

    private static UUID fakeHeaderId(String groupId) {
        return UUID.nameUUIDFromBytes(("vertex-tablist-header:" + groupId).getBytes(StandardCharsets.UTF_8));
    }

    /** Must satisfy the vanilla username charset -- this fake profile's name is never actually shown, the entry's display name is. */
    private static String fakeUsername(String groupId) {
        String hash = Integer.toHexString(groupId.hashCode());
        String name = "vxh" + hash;
        return name.length() > 16 ? name.substring(0, 16) : name;
    }
}
