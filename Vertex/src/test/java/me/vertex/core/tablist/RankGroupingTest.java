package me.vertex.core.tablist;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RankGroupingTest {

    @Test
    void higherWeightGroupIsOrderedFirst() {
        List<RankGrouping.Entry> entries = List.of(
                entry("Sub84", "warrior", "Warrior", 1),
                entry("Volos_", "ascendant", "Ascendant", 10));

        List<RankGrouping.Row> rows = RankGrouping.buildRows(entries, "__fallback__", "Members");

        assertEquals(4, rows.size()); // header + player per group
        assertInstanceOf(RankGrouping.HeaderRow.class, rows.get(0));
        assertEquals("Ascendant", ((RankGrouping.HeaderRow) rows.get(0)).displayName());
        assertInstanceOf(RankGrouping.HeaderRow.class, rows.get(2));
        assertEquals("Warrior", ((RankGrouping.HeaderRow) rows.get(2)).displayName());
    }

    @Test
    void orderStrictlyIncreasesTopToBottomAndStartsPositive() {
        List<RankGrouping.Entry> entries = List.of(
                entry("A", "warrior", "Warrior", 1),
                entry("B", "warrior", "Warrior", 1),
                entry("C", "ascendant", "Ascendant", 10));

        List<RankGrouping.Row> rows = RankGrouping.buildRows(entries, "__fallback__", "Members");

        int previous = 0;
        for (RankGrouping.Row row : rows) {
            assertTrue(row.order() > previous, "order must strictly increase top to bottom");
            assertTrue(row.order() > 0, "Player#setPlayerListOrder requires a positive value");
            previous = row.order();
        }
    }

    @Test
    void headerCountMatchesGroupMembership() {
        List<RankGrouping.Entry> entries = List.of(
                entry("A", "warrior", "Warrior", 1),
                entry("B", "warrior", "Warrior", 1),
                entry("C", "warrior", "Warrior", 1));

        List<RankGrouping.Row> rows = RankGrouping.buildRows(entries, "__fallback__", "Members");

        RankGrouping.HeaderRow header = (RankGrouping.HeaderRow) rows.get(0);
        assertEquals(3, header.count());
    }

    @Test
    void playersWithinAGroupAreSortedAlphabeticallyCaseInsensitive() {
        List<RankGrouping.Entry> entries = List.of(
                entry("charlie", "warrior", "Warrior", 1),
                entry("Alpha", "warrior", "Warrior", 1),
                entry("bravo", "warrior", "Warrior", 1));

        List<RankGrouping.Row> rows = RankGrouping.buildRows(entries, "__fallback__", "Members");

        assertEquals("Alpha", ((RankGrouping.PlayerRow) rows.get(1)).username());
        assertEquals("bravo", ((RankGrouping.PlayerRow) rows.get(2)).username());
        assertEquals("charlie", ((RankGrouping.PlayerRow) rows.get(3)).username());
    }

    @Test
    void playersWithNoGroupFallIntoTheFallbackBucketSortedLast() {
        List<RankGrouping.Entry> entries = List.of(
                new RankGrouping.Entry(UUID.randomUUID(), "Nobody", null, null, 0),
                entry("Volos_", "ascendant", "Ascendant", 10));

        List<RankGrouping.Row> rows = RankGrouping.buildRows(entries, "__fallback__", "Members");

        assertEquals("Ascendant", ((RankGrouping.HeaderRow) rows.get(0)).displayName());
        RankGrouping.HeaderRow fallbackHeader = (RankGrouping.HeaderRow) rows.get(2);
        assertEquals("Members", fallbackHeader.displayName());
        assertEquals("Nobody", ((RankGrouping.PlayerRow) rows.get(3)).username());
    }

    @Test
    void tiedWeightGroupsBreakTiesByDisplayNameForDeterminism() {
        List<RankGrouping.Entry> entries = List.of(
                entry("A", "zzz", "Zulu", 5),
                entry("B", "aaa", "Alpha", 5));

        List<RankGrouping.Row> rows = RankGrouping.buildRows(entries, "__fallback__", "Members");

        assertEquals("Alpha", ((RankGrouping.HeaderRow) rows.get(0)).displayName());
        assertEquals("Zulu", ((RankGrouping.HeaderRow) rows.get(2)).displayName());
    }

    private static RankGrouping.Entry entry(String username, String groupId, String groupDisplayName, int weight) {
        return new RankGrouping.Entry(UUID.randomUUID(), username, groupId, groupDisplayName, weight);
    }
}
