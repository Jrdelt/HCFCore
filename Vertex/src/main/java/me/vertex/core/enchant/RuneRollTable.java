package me.vertex.core.enchant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One Rune tier's weighted roll table, and the maths for rolling it.
 *
 * <p>Mirrors {@code me.vertex.core.mine.MineOreTable}'s exact shape on
 * purpose: an immutable weighted-{@link Entry} list, {@link #pick(double)}
 * taking externally-injected randomness (so the distribution is
 * unit-testable without relying on chance), and weights normalised
 * internally so an admin editing one entry's weight in {@code runes.yml}
 * never has to rebalance the rest of that tier's table to keep it valid.
 *
 * <p>Deliberately a single flat weighted list of (enchant, level) pairs --
 * not a two-stage "pick the enchant, then pick the level" roll -- both
 * because that is what mirrors {@code MineOreTable} literally, and because
 * it is what {@code runes.yml} is specified to hold ("which enchant+level
 * combos are on each tier's table and their weights"). A higher level
 * intentionally rolling less often than a lower one of the same enchant is
 * simply that combo's entry having a smaller configured weight within the
 * same tier's table -- no separate "level probability" stage is needed.
 */
public final class RuneRollTable {

    /**
     * @param enchantId the {@code EnchantDefinition} id this combo rolls
     * @param level     the level within that enchant this combo rolls
     * @param weight    relative frequency; normalised against the rest of the table
     */
    public record Entry(String enchantId, int level, double weight) {
    }

    /** One resolved roll outcome: which enchant, at which level. */
    public record Selection(String enchantId, int level) {
    }

    private final List<Entry> entries;
    private final double totalWeight;

    private RuneRollTable(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        this.totalWeight = entries.stream().mapToDouble(Entry::weight).sum();
    }

    public static RuneRollTable of(List<Entry> entries) {
        List<Entry> usable = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.weight() > 0D) {
                usable.add(entry);
            }
        }
        return new RuneRollTable(usable);
    }

    public boolean isEmpty() {
        return entries.isEmpty() || totalWeight <= 0D;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry entry(String enchantId, int level) {
        for (Entry entry : entries) {
            if (entry.enchantId().equals(enchantId) && entry.level() == level) {
                return entry;
            }
        }
        return null;
    }

    /** This combo's share of the table, as a percentage of all weights. */
    public double chancePercent(String enchantId, int level) {
        Entry entry = entry(enchantId, level);
        return entry == null || totalWeight <= 0D ? 0D : entry.weight() / totalWeight * 100D;
    }

    /**
     * @param roll a value in {@code [0, 1)}; taking it as a parameter rather
     *             than drawing it internally is what makes the distribution
     *             testable without relying on chance
     */
    public Selection pick(double roll) {
        if (isEmpty()) {
            return null;
        }
        double target = Math.max(0D, Math.min(0.999999D, roll)) * totalWeight;
        double cumulative = 0D;
        for (Entry entry : entries) {
            cumulative += entry.weight();
            if (target < cumulative) {
                return new Selection(entry.enchantId(), entry.level());
            }
        }
        Entry last = entries.getLast();
        return new Selection(last.enchantId(), last.level());
    }

    /** The table as percentages, for lore placeholders that must show real configured values. */
    public Map<Entry, Double> asPercentages() {
        Map<Entry, Double> percentages = new LinkedHashMap<>();
        for (Entry entry : entries) {
            percentages.put(entry, chancePercent(entry.enchantId(), entry.level()));
        }
        return percentages;
    }
}
