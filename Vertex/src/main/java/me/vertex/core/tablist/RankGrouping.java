package me.vertex.core.tablist;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a flat list of online players (each tagged with their LuckPerms
 * rank) into an ordered sequence of rows: a header row per rank -- highest
 * {@link Entry#weight()} first -- followed by that rank's players
 * alphabetically by username, with a strictly increasing {@code order}
 * assigned per row so it can be handed straight to
 * {@code Player#setPlayerListOrder} (real rows) or a fake header packet's
 * {@code listOrder} field (header rows). Paper/client ordering is ascending:
 * the smallest positive value is at the top of the tab list.
 *
 * <p>Kept free of Bukkit/ProtocolLib so the grouping and ordering math is
 * unit-testable without a server.
 */
final class RankGrouping {

    private RankGrouping() {
    }

    /** One online player and the rank group they render under. */
    record Entry(UUID playerId, String username, String groupId, String groupDisplayName, int weight) {
    }

    sealed interface Row permits HeaderRow, PlayerRow {
        int order();
    }

    record HeaderRow(String groupId, String displayName, int count, int order) implements Row {
    }

    record PlayerRow(UUID playerId, String username, int order) implements Row {
    }

    /**
     * @param fallbackGroupId          bucket id used for entries with no
     *                                 resolvable rank (LuckPerms unavailable,
     *                                 no loaded user, or the unconfigured
     *                                 base group)
     * @param fallbackGroupDisplayName that bucket's header text
     */
    static List<Row> buildRows(List<Entry> entries, String fallbackGroupId, String fallbackGroupDisplayName) {
        Map<String, List<Entry>> byGroup = new LinkedHashMap<>();
        Map<String, Integer> weightByGroup = new LinkedHashMap<>();
        Map<String, String> displayNameByGroup = new LinkedHashMap<>();

        for (Entry entry : entries) {
            String groupId = entry.groupId() == null ? fallbackGroupId : entry.groupId();
            String displayName = entry.groupId() == null ? fallbackGroupDisplayName : entry.groupDisplayName();
            int weight = entry.groupId() == null ? Integer.MIN_VALUE : entry.weight();

            byGroup.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(entry);
            weightByGroup.put(groupId, weight);
            displayNameByGroup.put(groupId, displayName);
        }

        List<String> orderedGroups = new ArrayList<>(byGroup.keySet());
        orderedGroups.sort(Comparator
                .comparingInt((String groupId) -> weightByGroup.get(groupId)).reversed()
                .thenComparing(displayNameByGroup::get));

        int totalRows = entries.size() + orderedGroups.size();
        // Paper requires a positive list-order value, and its client sorts
        // those values ascending. Starting at one makes the first (highest
        // weight) header appear immediately above the first ranked player.
        int nextOrder = 1;

        List<Row> rows = new ArrayList<>(totalRows);
        for (String groupId : orderedGroups) {
            List<Entry> members = byGroup.get(groupId);
            members.sort(Comparator.comparing(Entry::username, String.CASE_INSENSITIVE_ORDER));

            rows.add(new HeaderRow(groupId, displayNameByGroup.get(groupId), members.size(), nextOrder++));
            for (Entry member : members) {
                rows.add(new PlayerRow(member.playerId(), member.username(), nextOrder++));
            }
        }
        return rows;
    }
}
