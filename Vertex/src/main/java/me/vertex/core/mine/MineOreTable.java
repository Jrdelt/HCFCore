package me.vertex.core.mine;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A mining world's weighted block table, and the maths for rolling it.
 *
 * <p>Kept free of Bukkit's server state so the distribution is unit-testable,
 * the same way {@code ShopPricing} is. Weights are whatever the config says --
 * percentages, ratios, anything -- and are normalised here, so an admin
 * editing one ore never has to rebalance the rest to keep the table valid.
 */
public final class MineOreTable {

    /**
     * @param material    the block generated
     * @param weight      relative frequency; normalised against the rest of the table
     * @param dropAmount  how much mining it yields before boosters
     * @param dropMaterial the item granted to the miner
     */
    public record Entry(Material material, double weight, int dropAmount, Material dropMaterial) {

        /** Backwards-compatible entry using the normal mining reward for its block. */
        public Entry(Material material, double weight, int dropAmount) {
            this(material, weight, dropAmount, defaultDropMaterial(material));
        }

        /**
         * Uses the item form a mine should reward instead of an ore block.
         * Custom ore materials deliberately fall back to themselves, while
         * the vanilla mine ores always pay their resource, never a silk-touch
         * block.
         */
        public static Material defaultDropMaterial(Material material) {
            if (material == null) {
                return Material.STONE;
            }
            return switch (material) {
                case COAL_ORE, DEEPSLATE_COAL_ORE -> Material.COAL;
                case IRON_ORE, DEEPSLATE_IRON_ORE -> Material.IRON_INGOT;
                case COPPER_ORE, DEEPSLATE_COPPER_ORE -> Material.COPPER_INGOT;
                case GOLD_ORE, DEEPSLATE_GOLD_ORE -> Material.GOLD_INGOT;
                case NETHER_GOLD_ORE -> Material.GOLD_NUGGET;
                case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> Material.REDSTONE;
                case LAPIS_ORE, DEEPSLATE_LAPIS_ORE -> Material.LAPIS_LAZULI;
                case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> Material.DIAMOND;
                case EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> Material.EMERALD;
                case ANCIENT_DEBRIS -> Material.NETHERITE_SCRAP;
                case NETHERITE_BLOCK -> Material.NETHERITE_INGOT;
                default -> material;
            };
        }
    }

    private final List<Entry> entries;
    private final double totalWeight;

    private MineOreTable(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        this.totalWeight = entries.stream().mapToDouble(Entry::weight).sum();
    }

    public static MineOreTable of(List<Entry> entries) {
        List<Entry> usable = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.weight() > 0D) {
                usable.add(entry);
            }
        }
        return new MineOreTable(usable);
    }

    public boolean isEmpty() {
        return entries.isEmpty() || totalWeight <= 0D;
    }

    public List<Entry> entries() {
        return entries;
    }

    /** Every material this table can generate, so callers can whitelist breaking. */
    public List<Material> materials() {
        return entries.stream().map(Entry::material).toList();
    }

    public Entry entry(Material material) {
        for (Entry entry : entries) {
            if (entry.material() == material) {
                return entry;
            }
        }
        return null;
    }

    /** This material's share of the table, as a percentage of all weights. */
    public double chancePercent(Material material) {
        Entry entry = entry(material);
        return entry == null || totalWeight <= 0D ? 0D : entry.weight() / totalWeight * 100D;
    }

    /**
     * @param roll a value in {@code [0, 1)}; taking it as a parameter rather
     *             than drawing it internally is what makes the distribution
     *             testable without relying on chance
     */
    public Material pick(double roll) {
        if (isEmpty()) {
            return null;
        }
        double target = Math.max(0D, Math.min(0.999999D, roll)) * totalWeight;
        double cumulative = 0D;
        for (Entry entry : entries) {
            cumulative += entry.weight();
            if (target < cumulative) {
                return entry.material();
            }
        }
        return entries.getLast().material();
    }

    /**
     * A copy with rarer entries boosted more than common ones, for a Hot Zone.
     *
     * <p>{@code HotWeight = weight * (1 + intensity * (maxWeight/weight)^exponent)},
     * so the scarcer an ore already is, the larger its relative gain. Per-ore
     * overrides win outright when given, for when a table needs hand-balancing
     * rather than a curve.
     */
    public MineOreTable withHotZone(double intensity, double rarityExponent, Map<Material, Double> overrides) {
        if (isEmpty() || intensity <= 0D) {
            return this;
        }
        double maxWeight = entries.stream().mapToDouble(Entry::weight).max().orElse(1D);
        List<Entry> boosted = new ArrayList<>();
        for (Entry entry : entries) {
            Double override = overrides == null ? null : overrides.get(entry.material());
            double weight = override != null
                    ? entry.weight() * override
                    : entry.weight() * (1D + intensity * Math.pow(maxWeight / entry.weight(), rarityExponent));
            boosted.add(new Entry(entry.material(), weight, entry.dropAmount(), entry.dropMaterial()));
        }
        return new MineOreTable(boosted);
    }

    /** The table as percentages, for a GUI that must show real configured values. */
    public Map<Material, Double> asPercentages() {
        Map<Material, Double> percentages = new LinkedHashMap<>();
        for (Entry entry : entries) {
            percentages.put(entry.material(), chancePercent(entry.material()));
        }
        return percentages;
    }
}
