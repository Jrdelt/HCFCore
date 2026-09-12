package me.vertex.core.enchant;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One custom enchant's whole configuration: what item types it can go on,
 * which worlds it works in, and every one of its independently-configured
 * levels. Immutable once loaded from {@code enchants.yml} -- a reload
 * simply builds and swaps a whole new map of these in {@code
 * EnchantManager}.
 *
 * <p>Compatibility and world restriction live on the enchant as a whole,
 * not per level: a pickaxe enchant's levels are all still pickaxe-only, and
 * an enchant disabled in the Nether is disabled in the Nether at every
 * level. Everything that legitimately varies level to level (icon,
 * proc chance, ability strength, success rate) lives on {@link Level}
 * instead.
 */
public final class EnchantDefinition {

    /**
     * @param level           1-based level number
     * @param material        the physical enchant item's icon
     * @param customModelData optional resource-pack model override
     * @param glow            whether the physical enchant item should glint
     * @param procChance      percent chance (0-100) this level's effect activates when it's live -- exposed
     *                        for a future gameplay-effect listener to consult; this phase stores and renders
     *                        it but does not itself fire any enchant-specific gameplay effect
     * @param successRate     percent chance (0-100) a valid application attempt at this level succeeds;
     *                        failure is simply the inverse, per spec -- never stored separately
     * @param abilityValue    a generic per-level numeric strength/effect value, purely for lore/config use
     */
    public record Level(int level, Material material, Integer customModelData, boolean glow,
            double procChance, double successRate, double abilityValue) {

        public double failureRate() {
            return Math.max(0D, Math.min(100D, 100D - successRate));
        }
    }

    private final String id;
    private final String displayName;
    private final String description;
    private final Set<String> compatibleTypes;
    private final Set<String> enabledWorlds;
    private final Set<String> disabledWorlds;
    private final List<Level> levels;

    public EnchantDefinition(String id, String displayName, String description, Set<String> compatibleTypes,
            Set<String> enabledWorlds, Set<String> disabledWorlds, List<Level> levels) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.compatibleTypes = Set.copyOf(compatibleTypes);
        this.enabledWorlds = Set.copyOf(enabledWorlds);
        this.disabledWorlds = Set.copyOf(disabledWorlds);
        this.levels = List.copyOf(levels);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Canonical identified-Rune ability label, before its level value. */
    public String description() {
        return description;
    }

    public int maxLevel() {
        return levels.size();
    }

    public List<Level> levels() {
        return levels;
    }

    public Set<String> compatibleTypes() {
        return compatibleTypes;
    }

    /** @return the given level's config, or null when it doesn't exist on this enchant. */
    public Level level(int level) {
        return level >= 1 && level <= levels.size() ? levels.get(level - 1) : null;
    }

    /** Whether this enchant (at any level) may be applied to the given target Material. */
    public boolean isCompatible(Material material) {
        return material != null && EnchantTargetGroups.matches(compatibleTypes, material);
    }

    /**
     * Whether this enchant should have any live effect in the given world.
     * Enabled-worlds is a whitelist when non-empty; otherwise disabled-worlds
     * is a blacklist. Neither list ever removes the enchant from the item or
     * its lore -- see {@code EnchantManager#isEffectActive}.
     */
    public boolean isWorldActive(String worldName) {
        if (worldName == null) {
            return true;
        }
        String lower = worldName.toLowerCase(Locale.ROOT);
        if (!enabledWorlds.isEmpty()) {
            return enabledWorlds.contains(lower);
        }
        return !disabledWorlds.contains(lower);
    }
}
