package me.vertex.core.enchant;

import me.vertex.core.zone.ZoneType;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One custom enchant's whole configuration: what item types it can go on,
 * which worlds it works in, and every one of its independently-configured
 * levels. Immutable once loaded from {@code customEnchants/runes.yml} -- a reload
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
     * @param material        the physical enchant item's icon (ignored for a seasonal level that
     *                        configures {@code catalogItem})
     * @param customModelData optional resource-pack model override (same caveat as {@code material})
     * @param glow            whether the physical enchant item should glint
     * @param procChance      percent chance (0-100) this level's configured live effect activates
     * @param successRate     percent chance (0-100) a valid application attempt at this level succeeds;
     *                        failure is simply the inverse, per spec -- never stored separately
     * @param abilityValue    the primary player-facing strength value for this level
     * @param effectSettings  effect-specific numeric settings, configured per level
     * @param catalogItem     seasonal only: an id in {@code seasonal-items.yml} ({@link
     *                        me.vertex.core.enchant.SeasonalItemCatalog}) whose exact saved
     *                        ItemStack -- real material, custom model data, lore, vanilla
     *                        enchants, any other plugin's PDC -- this level's ability is baked
     *                        directly onto, instead of a generic {@code material}/{@code
     *                        customModelData} placeholder icon. Null falls back to that
     *                        placeholder. Configured once in {@code runes.yml}; no per-give
     *                        wiring needed afterward.
     */
    public record Level(int level, Material material, Integer customModelData, boolean glow,
            double procChance, double successRate, double abilityValue, Map<String, Double> effectSettings,
            String catalogItem) {

        public Level {
            effectSettings = effectSettings == null ? Map.of() : Map.copyOf(effectSettings);
        }

        public Level(int level, Material material, Integer customModelData, boolean glow,
                double procChance, double successRate, double abilityValue, Map<String, Double> effectSettings) {
            this(level, material, customModelData, glow, procChance, successRate, abilityValue, effectSettings, null);
        }

        /** Returns one optional numeric setting without tying the config to Java fields. */
        public double setting(String key, double fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            return effectSettings.getOrDefault(key.toLowerCase(Locale.ROOT), fallback);
        }
    }

    /** Whether an incoming-damage or outgoing-damage effect is scoped to mobs, players, or either. */
    public enum EffectScope {
        ANY, MOB, PLAYER
    }

    private final String id;
    private final String displayName;
    private final String description;
    private final Set<String> compatibleTypes;
    private final Set<String> enabledWorlds;
    private final Set<String> disabledWorlds;
    private final Set<ZoneType> enabledZones;
    private final Set<ZoneType> disabledZones;
    private final String effect;
    private final EffectScope damageSource;
    private final EffectScope targetFilter;
    private final int priority;
    private final boolean blockedInCombat;
    private final boolean seasonal;
    private final boolean bindable;
    private final Set<RuneTag> tags;
    private final List<Level> levels;
    private final String seasonalSet;
    private final boolean hidden;

    public EnchantDefinition(String id, String displayName, String description, Set<String> compatibleTypes,
            Set<String> enabledWorlds, Set<String> disabledWorlds, String effect, int priority,
            boolean blockedInCombat, List<Level> levels) {
        this(id, displayName, description, compatibleTypes, enabledWorlds, disabledWorlds, effect, priority,
                blockedInCombat, false, levels);
    }

    public EnchantDefinition(String id, String displayName, String description, Set<String> compatibleTypes,
            Set<String> enabledWorlds, Set<String> disabledWorlds, String effect, int priority,
            boolean blockedInCombat, boolean seasonal, List<Level> levels) {
        this(id, displayName, description, compatibleTypes, enabledWorlds, disabledWorlds, Set.of(), Set.of(),
                effect, EffectScope.ANY, EffectScope.ANY, priority, blockedInCombat, seasonal, false, Set.of(), levels);
    }

    public EnchantDefinition(String id, String displayName, String description, Set<String> compatibleTypes,
            Set<String> enabledWorlds, Set<String> disabledWorlds, Set<ZoneType> enabledZones,
            Set<ZoneType> disabledZones, String effect, EffectScope damageSource, EffectScope targetFilter,
            int priority, boolean blockedInCombat, boolean seasonal, boolean bindable, Set<RuneTag> tags,
            List<Level> levels) {
        this(id, displayName, description, compatibleTypes, enabledWorlds, disabledWorlds, enabledZones,
                disabledZones, effect, damageSource, targetFilter, priority, blockedInCombat, seasonal, bindable,
                tags, levels, null, false);
    }

    /**
     * @param seasonalSet purely organizational: which {@code runes.yml}
     *                    {@code seasonal.sets.<name>} group this enchant was
     *                    loaded from (e.g. {@code "fall"}), or null when it's
     *                    configured flat under {@code seasonal.enchants}
     *                    directly or isn't seasonal at all. Never rendered
     *                    anywhere -- the enchant's own {@code id} and {@code
     *                    display-name} are unaffected by which group it's
     *                    nested under, so grouping enchants into a set in
     *                    config never leaks into a rune's in-game name.
     * @param hidden      seasonal only: whether this piece is staged but not
     *                    yet released. A hidden seasonal Rune stays fully
     *                    usable through every admin command (give/roll/
     *                    catalog) -- an operator can build out a whole
     *                    season's worth of gear and abilities ahead of time
     *                    -- it's only excluded from the player-facing
     *                    Seasonal Set preview menu until unhidden.
     */
    public EnchantDefinition(String id, String displayName, String description, Set<String> compatibleTypes,
            Set<String> enabledWorlds, Set<String> disabledWorlds, Set<ZoneType> enabledZones,
            Set<ZoneType> disabledZones, String effect, EffectScope damageSource, EffectScope targetFilter,
            int priority, boolean blockedInCombat, boolean seasonal, boolean bindable, Set<RuneTag> tags,
            List<Level> levels, String seasonalSet, boolean hidden) {
        this.seasonalSet = seasonalSet;
        this.hidden = hidden;
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.compatibleTypes = Set.copyOf(compatibleTypes);
        this.enabledWorlds = Set.copyOf(enabledWorlds);
        this.disabledWorlds = Set.copyOf(disabledWorlds);
        this.enabledZones = enabledZones == null ? Set.of() : Set.copyOf(enabledZones);
        this.disabledZones = disabledZones == null ? Set.of() : Set.copyOf(disabledZones);
        this.effect = effect == null || effect.isBlank() ? "NONE" : effect.trim().toUpperCase(Locale.ROOT);
        this.damageSource = damageSource == null ? EffectScope.ANY : damageSource;
        this.targetFilter = targetFilter == null ? EffectScope.ANY : targetFilter;
        this.priority = priority;
        this.blockedInCombat = blockedInCombat;
        this.seasonal = seasonal;
        this.bindable = bindable;
        this.tags = tags == null ? Set.of() : Set.copyOf(tags);
        this.levels = List.copyOf(levels);
    }

    public boolean isSeasonal() {
        return seasonal;
    }

    /** @return the {@code seasonal.sets.<name>} group this was loaded from, or null. Organizational only -- see the constructor doc. */
    public String seasonalSet() {
        return seasonalSet;
    }

    /** Whether this seasonal Rune is staged but not yet released -- see the constructor doc. Always false for a non-seasonal Rune. */
    public boolean isHidden() {
        return hidden;
    }

    /** Whether this rune may be assigned to a {@code /binds} slot -- explicit config only, never inferred. */
    public boolean isBindable() {
        return bindable;
    }

    /** Every explicit category this rune was configured with -- never inferred. */
    public Set<RuneTag> tags() {
        return tags;
    }

    /** For an incoming-damage effect (e.g. Damage Reduction): which damage sources it applies to. */
    public EffectScope damageSource() {
        return damageSource;
    }

    /** For an outgoing-damage effect (e.g. Melee Damage): which targets it applies to. */
    public EffectScope targetFilter() {
        return targetFilter;
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

    /** Configurable gameplay effect type, consumed by {@link me.vertex.core.enchant.listener.RuneEffectListener}. */
    public String effect() {
        return effect;
    }

    /** Higher priority effects run first when they share the same trigger. */
    public int priority() {
        return priority;
    }

    /** Whether this active effect may not fire while the wearer is combat-tagged. */
    public boolean blockedInCombat() {
        return blockedInCombat;
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

    /**
     * Same whitelist-else-blacklist rule as {@link #isWorldActive}, but for
     * zones: {@code enabledZones} is a whitelist when non-empty (e.g. an
     * Arena-only rune restricted to Haven/Riftlands), otherwise {@code
     * disabledZones} acts as a general-purpose blacklist (e.g. a rune that
     * works everywhere except Haven/Riftlands). A player not currently in
     * any zone always passes.
     */
    public boolean isZoneActive(ZoneType zone) {
        if (zone == null) {
            return enabledZones.isEmpty();
        }
        if (!enabledZones.isEmpty()) {
            return enabledZones.contains(zone);
        }
        return !disabledZones.contains(zone);
    }
}
