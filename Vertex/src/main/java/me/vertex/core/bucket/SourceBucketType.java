package me.vertex.core.bucket;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;

/**
 * One configured Source Bucket variant, as read from {@code
 * sourcebuckets.yml}. Unlike {@code ChunkBusterType} (a fixed 4-constant
 * enum), Source Buckets are config-driven -- an admin can define any number
 * of variants per placed block, each with its own flow behavior -- so this is a
 * plain immutable record rather than an enum, the same shape {@code
 * WandTier} uses for the same reason (arbitrary, admin-named tiers loaded
 * from a {@code ConfigurationSection}'s keys).
 *
 * @param id             globally unique, e.g. {@code "water:downward"} --
 *                       {@link PlacedBlock#configKey()} + {@code ":"} + the
 *                       variant's own {@code sourcebuckets.yml} key. This is
 *                       exactly what gets written into the item's PDC tag,
 *                       so renaming a variant's YAML key changes what's on
 *                       disk (same trade-off {@code ChunkBusterType}'s class
 *                       doc calls out for its own {@code configKey}).
 * @param enabled        whether this variant can currently be used/bought.
 *                       A disabled variant still resolves from an
 *                       already-owned item's PDC tag (so {@code
 *                       SourceBucketManager.validate} can report {@code
 *                       TYPE_DISABLED} instead of silently doing nothing) --
 *                       only a variant removed from the config file entirely
 *                       stops resolving at all, the same limitation {@code
 *                       WandManager.tierOf} already has for a removed tier.
 * @param placedBlock    the block material this variant places. Each type
 *                       has a separate top-level configuration tree in
 *                       {@code sourcebuckets.yml}.
 * @param flowPattern    SINGLE_SOURCE, DOWNWARD, or OUTWARD; see {@link FlowPattern}.
 * @param maxDistance    for DOWNWARD, the number of blocks below the
 *                       placement point that the flow may reach (0 =
 *                       placement point only, -1 = the world's minimum Y).
 *                       For OUTWARD, the total number of blocks in the line.
 *                       Ignored for SINGLE_SOURCE.
 * @param baseClaimOnly  when true, usable only inside a Base Claim (never a
 *                       Raid Claim) -- queried via {@code
 *                       BaseClaimManager.isBaseClaim}.
 * @param combatAllowed  when false, blocked while combat-tagged
 *                       ({@code CombatManager.isTagged}); when true, no
 *                       combat check at all. Configured independently per
 *                       variant -- there is deliberately no single global
 *                       combat rule for every Source Bucket.
 * @param shopPrice      one-time {@code /shop} purchase price. Paying this
 *                       once hands over a permanently reusable item; there
 *                       is no subscription or re-buy-prevention bookkeeping
 *                       -- see {@code SourceBucketManager}'s class doc for
 *                       why no new database table exists for this.
 * @param perUseFee      the fixed fee charged on every *successful*
 *                       placement, after the fact -- see {@code
 *                       SourceBucketManager#use} for the exact
 *                       validate-then-place-then-charge ordering.
 * @param material       the item's {@link Material} (typically {@code
 *                       a bucket, but not
 *                       required to be -- configurable exactly like {@code
 *                       ChunkBusterType.material}).
 * @param customModelData optional resource-pack model override, or null.
 * @param name           the item's display name (MiniMessage/legacy input).
 * @param lore           the item's lore lines (MiniMessage/legacy input).
 * @param glow           when true, a fake enchant glow is applied (hidden
 *                       Unbreaking 1 + {@code ItemFlag.HIDE_ENCHANTS}), the
 *                       same idiom {@code SandBotManager.createGiveItem}
 *                       already uses elsewhere in this codebase.
 */
public record SourceBucketType(
        String id,
        boolean enabled,
        PlacedBlock placedBlock,
        FlowPattern flowPattern,
        int maxDistance,
        boolean baseClaimOnly,
        boolean combatAllowed,
        double shopPrice,
        double perUseFee,
        Material material,
        Integer customModelData,
        String name,
        List<String> lore,
        boolean glow) {

    /** The block a configured Source Bucket will place. */
    public enum PlacedBlock {
        WATER("water", Material.WATER, Material.WATER_BUCKET),
        LAVA("lava", Material.LAVA, Material.LAVA_BUCKET),
        OBSIDIAN("obsidian", Material.OBSIDIAN, Material.BUCKET),
        COBBLESTONE("cobblestone", Material.COBBLESTONE, Material.BUCKET);

        private final String configKey;
        private final Material blockMaterial;
        private final Material defaultItemMaterial;

        PlacedBlock(String configKey, Material blockMaterial, Material defaultItemMaterial) {
            this.configKey = configKey;
            this.blockMaterial = blockMaterial;
            this.defaultItemMaterial = defaultItemMaterial;
        }

        public String configKey() {
            return configKey;
        }

        /** The actual block material placed by this type. */
        public Material blockMaterial() {
            return blockMaterial;
        }

        /** The item material used when a variant does not configure one. */
        public Material defaultItemMaterial() {
            return defaultItemMaterial;
        }
    }

    /**
     * The three flow behaviors this feature implements. Deliberately a small,
     * closed set rather than an open-ended strategy interface: the spec
     * asks only for these three, and {@code SourceBucketManager.computeFlowPositions}
     * switches on this enum directly. Adding another pattern later means
     * adding one more enum constant and one more branch there -- no
     * rewrite of the claim-boundary/obstruction-stopping logic itself,
     * which is pattern-agnostic (it just walks whatever candidate
     * positions the pattern proposes).
     */
    public enum FlowPattern {
        SINGLE_SOURCE("single-source"),
        DOWNWARD("downward"),
        OUTWARD("outward");

        private final String configKey;

        FlowPattern(String configKey) {
            this.configKey = configKey;
        }

        public String configKey() {
            return configKey;
        }

        /** @return the matching pattern for a configured key, or null if none match. */
        public static FlowPattern fromConfigKey(String key) {
            if (key == null) {
                return null;
            }
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            for (FlowPattern pattern : values()) {
                if (pattern.configKey.equals(normalized)) {
                    return pattern;
                }
            }
            return null;
        }
    }
}
