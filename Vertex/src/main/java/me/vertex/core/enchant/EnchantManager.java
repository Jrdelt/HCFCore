package me.vertex.core.enchant;

import me.vertex.core.item.ItemKind;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.zone.ZoneType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * The Custom Enchantment framework's core orchestrator: Rune rolling,
 * physical-enchant-item creation, application (compatibility, level
 * replacement, success/failure, Lucky Gems), world-restriction effect
 * suppression, lore rendering, and PDC persistence -- everything sections
 * 10-27 of the spec describe, gathered in one place the way {@code
 * WandManager} owns everything about a wand rather than spreading it
 * across several classes.
 *
 * <p><b>Randomness</b>: every method that rolls something ({@link
 * #rollRune}, {@link #applyEnchant}) takes the random draw as an explicit
 * {@code roll} parameter -- the same externally-injected-randomness
 * convention {@code MineOreTable#pick(double)} uses -- so the actual
 * probability logic is unit-testable without depending on chance. Callers
 * (the listeners) pass {@code Math.random()}.
 *
 * <p><b>Persistence model</b>: nothing here needs a SQL table. A Rune's
 * tier, a physical enchant item's identity, and a target item's active
 * custom enchants + pristine pre-enchant lore all live entirely in that
 * item's own PDC -- the item itself is the record, so it survives
 * restarts, trades, and container storage for free, and requires no
 * `StorageMigrator` changes (mirroring Source Buckets' "the item is the
 * record" reasoning).
 *
 * <p><b>Stacking and duplication</b>: base Runes, Lucky Gems, and rolled
 * enchant items intentionally do not receive per-instance IDs. That keeps
 * stackable commodities stackable and lets rolled items stack exactly when
 * their stored enchant data is identical. Gear is non-stackable, so it is
 * still given a tracked ID after a successful application.
 */
public final class EnchantManager {

    private static final String ENTRY_SEPARATOR = ";";
    private static final String FIELD_SEPARATOR = ":";
    // LORE_LINE_SEPARATOR below is U+0001, a control character no legitimate
    // lore line can contain -- used to join a snapshot of several lore lines
    // into one PDC string and split it back apart unambiguously.
    private static final String LORE_LINE_SEPARATOR = "";
    private static final Pattern LORE_LINE_SPLIT = Pattern.compile(Pattern.quote(LORE_LINE_SEPARATOR));

    public enum RollResult {
        OK, EMPTY_TABLE
    }

    public record RollOutcome(RollResult result, ItemStack createdItem, String enchantId, int level) {
        public boolean ok() {
            return result == RollResult.OK;
        }

        static RollOutcome empty() {
            return new RollOutcome(RollResult.EMPTY_TABLE, null, null, 0);
        }
    }

    public enum ApplyResult {
        SUCCESS, FAILURE, REJECT_INVALID, REJECT_INCOMPATIBLE, REJECT_EQUAL_LEVEL, REJECT_HIGHER_EXISTS
    }

    public record ApplyOutcome(ApplyResult result, boolean consumedEnchantItem,
            String enchantId, int level, double chanceUsed) {

        static ApplyOutcome reject(ApplyResult result, String enchantId, int level) {
            return new ApplyOutcome(result, false, enchantId, level, 0D);
        }
    }

    /** What a physical (rolled, not-yet-applied) enchant item's PDC says it is. */
    public record EnchantItemInfo(String enchantId, int level, RuneTier originTier) {
    }

    /** A tier's rune-shop currency -- MONEY charges through Vault, XP_LEVELS removes whole XP levels. */
    public enum Currency {
        MONEY, XP_LEVELS
    }

    private record RuneCosmetic(Material material, Integer customModelData, boolean glow) {
    }

    /** One weighted band of levels (inclusive) used to synthesize a tier's roll table without hand-listing every level. */
    private record LevelBucket(int from, int to, double weight) {
    }

    /** When present on a tier, every roll on that tier's table re-randomizes success in this range instead of using the level's configured rate. */
    /** When a tier has one configured, every roll re-randomizes success within it instead of using a fixed per-level rate. */
    public record SuccessRange(double min, double max) {
    }

    private final Plugin plugin;
    private final TrackedItemIds trackedItemIds;

    private final NamespacedKey runeTierKey;
    private final NamespacedKey enchantItemIdKey;
    private final NamespacedKey enchantItemLevelKey;
    private final NamespacedKey enchantItemOriginTierKey;
    private final NamespacedKey enchantItemSuccessKey;
    private final NamespacedKey luckyGemKey;
    private final NamespacedKey legacyArenaLuckyGemKey;
    private final NamespacedKey legacyDraggedGemCountKey;
    private final NamespacedKey enchantsKey;
    private final NamespacedKey enchantTiersKey;
    private final NamespacedKey baseLoreKey;
    private final NamespacedKey arenaAppliedKey;

    // Legacy Arena Rune PDC keys, kept only so items already in the wild from
    // before Arena Runes were unified into this registry keep working -- see
    // the compatibility reads on tierOf/enchantItemInfo below. Never written
    // by new code; every new item is written in the unified format.
    private final NamespacedKey legacyArenaRuneKey;
    private final NamespacedKey legacyArenaEnchantKey;
    private final NamespacedKey legacyArenaEnchantSuccessKey;

    // Set once, after plugin startup wires both objects together -- see
    // setSeasonalCatalog. Null-safe everywhere it's read: a server that
    // hasn't wired one yet (or a level with no catalog-item configured)
    // just gets the old generic placeholder icon.
    private volatile SeasonalItemCatalog seasonalCatalog;

    private volatile Map<String, EnchantDefinition> definitions = Map.of();
    /** Reverse lookup for {@link #tierOf(String)} -- absent (not null) for a {@code legacy:} id, which belongs to no current tier. */
    private volatile Map<String, RuneTier> definitionTiers = Map.of();
    private volatile Map<RuneTier, RuneRollTable> rollTables = Map.of();
    private volatile Map<RuneTier, RuneCosmetic> runeCosmetics = Map.of();
    private volatile Map<RuneTier, Double> runeShopPrices = Map.of();
    private volatile Map<RuneTier, Currency> runeShopCurrencies = Map.of();
    private volatile Map<RuneTier, SuccessRange> randomSuccessRanges = Map.of();
    private volatile java.util.function.Consumer<String> onNoLongerBindable;
    private volatile double luckyGemShopPrice;
    private volatile RuneCosmetic luckyGemCosmetic = new RuneCosmetic(Material.EMERALD, null, true);
    private volatile double successRateJitterPercent = 10D;

    /** One fixed, shared bonus for every current and legacy Lucky Gem. */
    public static final double LUCKY_GEM_BONUS_PERCENT = 3.5D;

    public EnchantManager(Plugin plugin, TrackedItemIds trackedItemIds) {
        this.plugin = plugin;
        this.trackedItemIds = trackedItemIds;
        this.runeTierKey = new NamespacedKey(plugin, "rune_tier");
        this.enchantItemIdKey = new NamespacedKey(plugin, "enchant_item_id");
        this.enchantItemLevelKey = new NamespacedKey(plugin, "enchant_item_level");
        this.enchantItemOriginTierKey = new NamespacedKey(plugin, "enchant_item_origin_tier");
        this.enchantItemSuccessKey = new NamespacedKey(plugin, "rune_success_chance");
        this.luckyGemKey = new NamespacedKey(plugin, "lucky_gem");
        this.legacyArenaLuckyGemKey = new NamespacedKey(plugin, "arena_lucky_gem");
        this.legacyDraggedGemCountKey = new NamespacedKey(plugin, "drag_lucky_gem_count");
        this.enchantsKey = new NamespacedKey(plugin, "custom_enchants");
        this.enchantTiersKey = new NamespacedKey(plugin, "custom_enchant_tiers");
        this.baseLoreKey = new NamespacedKey(plugin, "custom_enchants_base_lore");
        this.arenaAppliedKey = new NamespacedKey(plugin, "arena_enchants");
        this.legacyArenaRuneKey = new NamespacedKey(plugin, "arena_rune");
        this.legacyArenaEnchantKey = new NamespacedKey(plugin, "arena_enchant");
        this.legacyArenaEnchantSuccessKey = new NamespacedKey(plugin, "arena_enchant_success");
    }

    public Plugin plugin() { return plugin; }

    /** Wires the catalog a seasonal level's {@code catalog-item} config refers to. See {@link #seasonalCatalog}. */
    public void setSeasonalCatalog(SeasonalItemCatalog catalog) {
        this.seasonalCatalog = catalog;
    }

    // ------------------------------------------------------------------
    // Config loading
    // ------------------------------------------------------------------

    private static final String RUNES_CONFIG_PATH = "customEnchants/runes.yml";

    public void load() {
        loadRunes();
    }

    /** One definition plus the roll-table rows it contributes to its tier -- kept together since both come from the same config block. */
    private record LoadedEnchant(EnchantDefinition definition, List<RuneRollTable.Entry> rollEntries) {
    }

    private void loadRunes() {
        File file = new File(plugin.getDataFolder(), RUNES_CONFIG_PATH);
        if (!file.exists()) {
            plugin.saveResource(RUNES_CONFIG_PATH, false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        luckyGemCosmetic = readCosmetic(RUNES_CONFIG_PATH, "lucky-gem",
                config.getConfigurationSection("lucky-gem"), Material.EMERALD);
        luckyGemShopPrice = Math.max(0D, config.getDouble("lucky-gem.shop-price", 0D));
        successRateJitterPercent = Math.max(0D, config.getDouble("success-rate-jitter-percent", 10D));

        Map<String, EnchantDefinition> loadedDefinitions = new LinkedHashMap<>();
        Map<String, RuneTier> loadedDefinitionTiers = new LinkedHashMap<>();
        Map<RuneTier, RuneCosmetic> cosmetics = new EnumMap<>(RuneTier.class);
        Map<RuneTier, Double> prices = new EnumMap<>(RuneTier.class);
        Map<RuneTier, Currency> currencies = new EnumMap<>(RuneTier.class);
        Map<RuneTier, SuccessRange> successRanges = new EnumMap<>(RuneTier.class);
        Map<RuneTier, RuneRollTable> tables = new EnumMap<>(RuneTier.class);

        ConfigurationSection runesSection = config.getConfigurationSection("runes");
        for (RuneTier tier : RuneTier.values()) {
            String tierKey = tier.name().toLowerCase(Locale.ROOT);
            ConfigurationSection tierSection = runesSection == null ? null : runesSection.getConfigurationSection(tierKey);
            cosmetics.put(tier, readCosmetic(RUNES_CONFIG_PATH, "runes." + tierKey, tierSection, Material.AMETHYST_SHARD));
            prices.put(tier, tierSection == null ? 0D : Math.max(0D, tierSection.getDouble("shop-price", 0D)));
            currencies.put(tier, readCurrency(tierSection));
            SuccessRange successRange = readSuccessRange(tierSection);
            if (successRange != null) {
                successRanges.put(tier, successRange);
            }

            List<RuneRollTable.Entry> entries = new ArrayList<>();
            if (tierSection != null) {
                List<LevelBucket> buckets = readLevelBuckets(tierSection.getMapList("level-buckets"));
                int levelCount = tierSection.getInt("level-count", 0);
                ConfigurationSection enchantsSection = tierSection.getConfigurationSection("enchants");
                if (enchantsSection != null) {
                    for (String id : enchantsSection.getKeys(false)) {
                        ConfigurationSection enchantSection = enchantsSection.getConfigurationSection(id);
                        if (enchantSection == null) {
                            continue;
                        }
                        LoadedEnchant loaded = readEnchant(RUNES_CONFIG_PATH + " (" + tierKey + ")", id, enchantSection,
                                buckets, levelCount, tier);
                        if (loaded == null) {
                            continue;
                        }
                        loadedDefinitions.put(id, loaded.definition());
                        loadedDefinitionTiers.put(id, tier);
                        entries.addAll(loaded.rollEntries());
                    }
                }
                // Purely organizational grouping (e.g. every Fall-set piece
                // nested under `sets: fall:` instead of loose in the flat
                // `enchants:` list above) -- each enchant still needs its
                // own top-level id and never inherits the set name into it,
                // so grouping never changes a rune's id or in-game name.
                // See EnchantDefinition#seasonalSet.
                ConfigurationSection setsSection = tierSection.getConfigurationSection("sets");
                if (setsSection != null) {
                    for (String setName : setsSection.getKeys(false)) {
                        ConfigurationSection setSection = setsSection.getConfigurationSection(setName);
                        ConfigurationSection setEnchants = setSection == null ? null
                                : setSection.getConfigurationSection("enchants");
                        if (setEnchants == null) {
                            continue;
                        }
                        // The set's own material/custom-model-data/catalog-item
                        // (if any) become every nested level's default icon,
                        // overridable per level same as before.
                        Material setMaterial = setSection.contains("material")
                                ? readMaterial(RUNES_CONFIG_PATH, "seasonal set '" + setName + "'",
                                        setSection.getString("material"), null)
                                : null;
                        Integer setCustomModelData = setSection.contains("custom-model-data")
                                ? setSection.getInt("custom-model-data") : null;
                        String setCatalogItem = setSection.getString("catalog-item");
                        boolean setHidden = setSection.getBoolean("hidden", false);
                        LevelIconDefaults setIconDefaults = new LevelIconDefaults(setMaterial, setCustomModelData,
                                setCatalogItem != null && setCatalogItem.isBlank() ? null : setCatalogItem, setHidden);
                        for (String id : setEnchants.getKeys(false)) {
                            ConfigurationSection enchantSection = setEnchants.getConfigurationSection(id);
                            if (enchantSection == null) {
                                continue;
                            }
                            LoadedEnchant loaded = readEnchant(
                                    RUNES_CONFIG_PATH + " (" + tierKey + ".sets." + setName + ")", id, enchantSection,
                                    buckets, levelCount, setName, setIconDefaults, tier);
                            if (loaded == null) {
                                continue;
                            }
                            loadedDefinitions.put(id, loaded.definition());
                            loadedDefinitionTiers.put(id, tier);
                            entries.addAll(loaded.rollEntries());
                        }
                    }
                }
            }
            tables.put(tier, RuneRollTable.of(entries));
        }

        // Definitions retained only so items already rolled/applied from a
        // now-removed pool keep rendering correctly -- deliberately outside
        // every tier's `enchants:` block and every roll table.
        ConfigurationSection legacySection = config.getConfigurationSection("legacy");
        if (legacySection != null) {
            for (String id : legacySection.getKeys(false)) {
                ConfigurationSection section = legacySection.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                LoadedEnchant loaded = readEnchant(RUNES_CONFIG_PATH + " (legacy)", id, section, List.of(), 0, null);
                if (loaded != null) {
                    loadedDefinitions.put(id, loaded.definition());
                }
            }
        }

        Map<String, EnchantDefinition> previousDefinitions = definitions;
        // Map.copyOf's iteration order is deliberately unspecified -- unlike
        // Collections.unmodifiableMap, it does NOT preserve the source map's
        // order, so it silently scrambled the runes.yml file order every
        // load. seasonalIds() (and anything else iterating definitions in
        // display order, like the Seasonal Set preview menu) needs that
        // order to actually be admin-controllable by editing the file.
        definitions = Collections.unmodifiableMap(loadedDefinitions);
        definitionTiers = Map.copyOf(loadedDefinitionTiers);
        runeCosmetics = Map.copyOf(cosmetics);
        runeShopPrices = Map.copyOf(prices);
        runeShopCurrencies = Map.copyOf(currencies);
        randomSuccessRanges = Map.copyOf(successRanges);
        rollTables = Map.copyOf(tables);
        loadWarChest(config);
        notifyNoLongerBindable(previousDefinitions);
        warnIfSeasonalPieceMappingIsAmbiguous();
    }

    /**
     * {@code /seasonal catalog create} identifies which seasonal Rune to
     * bake onto a held item purely from that item's material, which only
     * works while every seasonal Rune targets exactly one piece type and no
     * two target the same one. Config is free to violate that (a seasonal
     * Rune could legitimately list more than one compatible type, or two
     * could overlap), so this only warns -- it never rejects a load -- but
     * makes a silent, confusing "wrong Rune got baked on" failure mode
     * loud and immediate instead.
     */
    private void warnIfSeasonalPieceMappingIsAmbiguous() {
        Map<String, List<String>> claimants = new LinkedHashMap<>();
        for (EnchantDefinition definition : definitions.values()) {
            if (!definition.isSeasonal()) {
                continue;
            }
            Set<String> types = definition.compatibleTypes();
            if (types.size() != 1) {
                plugin.getLogger().warning(RUNES_CONFIG_PATH + ": seasonal Rune '" + definition.id()
                        + "' has compatible-types " + types + " (" + types.size() + " entries) -- "
                        + "/seasonal catalog create needs exactly one to know which item this belongs on.");
            }
            for (String type : types) {
                claimants.computeIfAbsent(type, key -> new ArrayList<>()).add(definition.id());
            }
        }
        for (Map.Entry<String, List<String>> entry : claimants.entrySet()) {
            if (entry.getValue().size() > 1) {
                plugin.getLogger().warning(RUNES_CONFIG_PATH + ": seasonal Runes " + entry.getValue()
                        + " all claim compatible-types '" + entry.getKey() + "' -- "
                        + "/seasonal catalog create can't tell them apart for that item type.");
            }
        }
    }

    /**
     * War Chest (seasonal) isn't a gear-applied enchant -- it's a backpack
     * tier (see {@code me.vertex.core.backpack}, id {@code war_chest}) --
     * so its config lives in this same file's sibling {@code war-chest:}
     * block instead of under any tier's {@code enchants:} section, and is
     * parsed here directly rather than through {@link #readEnchant}.
     */
    public record WarChestLevel(double procChancePercent, double cooldownSeconds) {
    }

    public record WarChestBooster(String id, boolean enabled, double weight, int potionLevel, double durationSeconds,
            double oreDropBonusPercent, double mobDropBonusPercent) {
    }

    private volatile Map<Integer, WarChestLevel> warChestLevels = Map.of();
    private volatile List<WarChestBooster> warChestBoosters = List.of();

    private void loadWarChest(YamlConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("war-chest");
        Map<Integer, WarChestLevel> levels = new java.util.LinkedHashMap<>();
        List<WarChestBooster> boosters = new ArrayList<>();
        if (section != null) {
            ConfigurationSection levelsSection = section.getConfigurationSection("levels");
            if (levelsSection != null) {
                for (String key : levelsSection.getKeys(false)) {
                    try {
                        int level = Integer.parseInt(key);
                        ConfigurationSection levelSection = levelsSection.getConfigurationSection(key);
                        if (levelSection == null) {
                            continue;
                        }
                        levels.put(level, new WarChestLevel(
                                clampPercent(levelSection.getDouble("proc-chance-percent", 0D)),
                                Math.max(0D, levelSection.getDouble("cooldown-seconds", 10D))));
                    } catch (NumberFormatException ignored) {
                        plugin.getLogger().warning(RUNES_CONFIG_PATH + " (war-chest): non-numeric level key '" + key + "', ignoring it.");
                    }
                }
            }
            ConfigurationSection boostersSection = section.getConfigurationSection("boosters");
            if (boostersSection != null) {
                for (String id : boostersSection.getKeys(false)) {
                    ConfigurationSection boosterSection = boostersSection.getConfigurationSection(id);
                    if (boosterSection == null) {
                        continue;
                    }
                    boosters.add(new WarChestBooster(id, boosterSection.getBoolean("enabled", true),
                            Math.max(0D, boosterSection.getDouble("weight", 1D)),
                            boosterSection.getInt("potion-level", 1),
                            Math.max(0D, boosterSection.getDouble("duration-seconds", 5D)),
                            Math.max(0D, boosterSection.getDouble("ore-drop-bonus-percent", 0D)),
                            Math.max(0D, boosterSection.getDouble("mob-drop-bonus-percent", 0D))));
                }
            }
        }
        warChestLevels = Map.copyOf(levels);
        warChestBoosters = List.copyOf(boosters);
    }

    public WarChestLevel warChestLevel(int level) {
        WarChestLevel exact = warChestLevels.get(level);
        if (exact != null) {
            return exact;
        }
        // No per-tier level cap exists in the shared backpack-upgrade system
        // (see backpacks.yml's own comment on this) -- a War Chest upgraded
        // past its configured range simply keeps its highest configured
        // level's potency rather than silently granting nothing.
        return warChestLevels.entrySet().stream()
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(new WarChestLevel(0D, 10D));
    }

    public List<WarChestBooster> warChestBoosters() {
        return warChestBoosters;
    }

    /**
     * Fired after every reload (including startup) with every enchant id
     * that either no longer exists, or flipped {@code bindable: true} to
     * {@code false}, in this reload. {@code /binds} is the only current
     * subscriber -- see {@code BindManager#pruneRune} -- but this stays
     * generic (not a hard dependency on the binds package) since
     * `EnchantManager` must not know about `/binds` at all.
     */
    public void setOnNoLongerBindable(java.util.function.Consumer<String> listener) {
        this.onNoLongerBindable = listener;
    }

    private void notifyNoLongerBindable(Map<String, EnchantDefinition> previousDefinitions) {
        if (onNoLongerBindable == null || previousDefinitions.isEmpty()) {
            return;
        }
        for (EnchantDefinition previous : previousDefinitions.values()) {
            if (!previous.isBindable()) {
                continue;
            }
            EnchantDefinition current = definitions.get(previous.id());
            if (current == null || !current.isBindable()) {
                onNoLongerBindable.accept(previous.id());
            }
        }
    }

    private Currency readCurrency(ConfigurationSection tierSection) {
        if (tierSection == null) {
            return Currency.MONEY;
        }
        try {
            return Currency.valueOf(tierSection.getString("currency", "MONEY").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Currency.MONEY;
        }
    }

    private SuccessRange readSuccessRange(ConfigurationSection tierSection) {
        ConfigurationSection section = tierSection == null ? null : tierSection.getConfigurationSection("random-success-range");
        if (section == null) {
            return null;
        }
        double min = clampPercent(section.getDouble("min", 0D));
        double max = clampPercent(section.getDouble("max", 100D));
        return new SuccessRange(Math.min(min, max), Math.max(min, max));
    }

    private List<LevelBucket> readLevelBuckets(List<Map<?, ?>> raw) {
        List<LevelBucket> buckets = new ArrayList<>();
        for (Map<?, ?> entry : raw) {
            Object fromObj = entry.get("from");
            Object toObj = entry.get("to");
            Object weightObj = entry.get("weight");
            if (fromObj == null || toObj == null || weightObj == null) {
                continue;
            }
            try {
                int from = Integer.parseInt(String.valueOf(fromObj));
                int to = Integer.parseInt(String.valueOf(toObj));
                double weight = Double.parseDouble(String.valueOf(weightObj));
                if (from >= 1 && to >= from && weight > 0D) {
                    buckets.add(new LevelBucket(from, to, weight));
                }
            } catch (NumberFormatException ignored) {
                // Skip a malformed bucket rather than failing the whole tier.
            }
        }
        return buckets;
    }

    /** Spreads a bucket's total weight evenly across every level it covers, so the sum stays proportional to the original odds. */
    private static double weightForLevel(List<LevelBucket> buckets, int level) {
        for (LevelBucket bucket : buckets) {
            if (level >= bucket.from() && level <= bucket.to()) {
                return bucket.weight() / (bucket.to() - bucket.from() + 1);
            }
        }
        return 0D;
    }

    /**
     * A {@code sets: <name>:} subsection's own {@code material}/{@code
     * custom-model-data}/{@code catalog-item} act as that set's shared
     * default icon -- every level of every enchant nested under it inherits
     * these unless the level configures its own, so a set whose every piece
     * happens to share one icon (or one catalog item, in the rare case a
     * whole set is a single physical item) doesn't need it repeated on
     * every single level. Flat (non-set) enchants get {@link #NONE}, which
     * preserves the exact old hardcoded {@code Material.STONE}/null/null
     * fallback.
     */
    private record LevelIconDefaults(Material material, Integer customModelData, String catalogItem, boolean hidden) {
        static final LevelIconDefaults NONE = new LevelIconDefaults(null, null, null, false);
    }

    private LoadedEnchant readEnchant(String where, String id, ConfigurationSection section,
            List<LevelBucket> tierBuckets, int tierLevelCount, RuneTier tier) {
        return readEnchant(where, id, section, tierBuckets, tierLevelCount, null, LevelIconDefaults.NONE, tier);
    }

    private LoadedEnchant readEnchant(String where, String id, ConfigurationSection section,
            List<LevelBucket> tierBuckets, int tierLevelCount, String seasonalSet, LevelIconDefaults iconDefaults,
            RuneTier tier) {
        String displayName = section.getString("display-name", id);
        String description = section.getString("description", displayName);
        Set<String> compatible = new LinkedHashSet<>();
        for (String raw : section.getStringList("compatible-types")) {
            if (raw != null && !raw.isBlank()) {
                compatible.add(raw.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (compatible.isEmpty()) {
            plugin.getLogger().warning(where + ": enchant '" + id + "' has no compatible-types, defaulting to ALL.");
            compatible.add("ALL");
        }
        Set<String> enabledWorlds = lowercasedSet(section.getStringList("enabled-worlds"));
        Set<String> disabledWorlds = lowercasedSet(section.getStringList("disabled-worlds"));
        Set<ZoneType> enabledZones = readZones(where, id, section.getStringList("enabled-zones"));
        Set<ZoneType> disabledZones = readZones(where, id, section.getStringList("disabled-zones"));
        String effect = section.getString("effect", "NONE");
        EnchantDefinition.EffectScope damageSource = readEffectScope(section.getString("damage-source"));
        EnchantDefinition.EffectScope targetFilter = readEffectScope(section.getString("target-filter"));
        int priority = section.getInt("priority", 0);
        boolean blockedInCombat = section.getBoolean("blocked-in-combat", false);
        boolean seasonal = section.getBoolean("seasonal", false);
        boolean bindable = section.getBoolean("bindable", false);
        boolean hidden = section.getBoolean("hidden", iconDefaults.hidden());
        Set<RuneTag> tags = readTags(where, id, section.getStringList("tags"));

        List<EnchantDefinition.Level> levels;
        List<RuneRollTable.Entry> rollEntries = new ArrayList<>();
        if (section.contains("per-level-value") && tierLevelCount > 0) {
            levels = generateLevels(section, id, tierLevelCount, tierBuckets, rollEntries, tier);
        } else {
            levels = readExplicitLevels(where, id, section, rollEntries, iconDefaults, tier);
        }
        if (levels.isEmpty()) {
            plugin.getLogger().warning(where + ": enchant '" + id + "' has no levels configured, skipping it entirely.");
            return null;
        }
        EnchantDefinition definition = new EnchantDefinition(id, displayName, description, compatible,
                enabledWorlds, disabledWorlds, enabledZones, disabledZones, effect, damageSource, targetFilter,
                priority, blockedInCombat, seasonal, bindable, tags, levels, seasonalSet, hidden);
        return new LoadedEnchant(definition, rollEntries);
    }

    /** Legacy, hand-authored `levels: { 1: {...}, 2: {...} }` shape -- each level may carry its own roll `weight`. */
    private List<EnchantDefinition.Level> readExplicitLevels(String where, String id, ConfigurationSection section,
            List<RuneRollTable.Entry> rollEntries, LevelIconDefaults iconDefaults, RuneTier tier) {
        List<EnchantDefinition.Level> levels = new ArrayList<>();
        ConfigurationSection levelsSection = section.getConfigurationSection("levels");
        if (levelsSection == null) {
            return levels;
        }
        List<Integer> levelNumbers = new ArrayList<>();
        for (String key : levelsSection.getKeys(false)) {
            try {
                levelNumbers.add(Integer.parseInt(key));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning(where + ": enchant '" + id + "' has a non-numeric level key '"
                        + key + "', ignoring it.");
            }
        }
        Collections.sort(levelNumbers);
        for (int levelNumber : levelNumbers) {
            ConfigurationSection levelSection = levelsSection.getConfigurationSection(String.valueOf(levelNumber));
            if (levelSection == null) {
                continue;
            }
            levels.add(readLevel(where, id, levelNumber, levelSection, iconDefaults, tier));
            double weight = levelSection.getDouble("weight", 0D);
            if (weight > 0D) {
                rollEntries.add(new RuneRollTable.Entry(id, levelNumber, weight));
            }
        }
        return levels;
    }

    /**
     * Generated levels for a `per-level-value` enchant (today, only the
     * Arena tier uses this): {@code tierLevelCount} levels are synthesized
     * with a linearly-scaling ability value and a shared icon/proc-chance,
     * instead of hand-authoring dozens of near-identical level blocks. Roll
     * weight per level comes from the tier's {@code level-buckets}.
     */
    private List<EnchantDefinition.Level> generateLevels(ConfigurationSection section, String id, int tierLevelCount,
            List<LevelBucket> tierBuckets, List<RuneRollTable.Entry> rollEntries, RuneTier tier) {
        double perLevelValue = section.getDouble("per-level-value", 0D);
        double procChance = clampPercent(section.getDouble("proc-chance", 100D));
        Map<String, Double> effectSettings = readEffectSettings(RUNES_CONFIG_PATH, id, 0, section.getConfigurationSection("effect-settings"));
        Material levelMaterial = tier != null && tier != RuneTier.SEASONAL
                ? RuneFormatting.tierCandle(tier) : Material.STONE;
        List<EnchantDefinition.Level> levels = new ArrayList<>(tierLevelCount);
        for (int level = 1; level <= tierLevelCount; level++) {
            levels.add(new EnchantDefinition.Level(level, levelMaterial, null, level == tierLevelCount,
                    procChance, 50D, perLevelValue * level, effectSettings));
            double weight = weightForLevel(tierBuckets, level);
            if (weight > 0D) {
                rollEntries.add(new RuneRollTable.Entry(id, level, weight));
            }
        }
        return levels;
    }

    private Set<ZoneType> readZones(String where, String id, List<String> raw) {
        Set<ZoneType> zones = EnumSet.noneOf(ZoneType.class);
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                zones.add(ZoneType.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(where + ": enchant '" + id + "' has an unknown zone '" + value + "', ignoring it.");
            }
        }
        return zones;
    }

    private EnchantDefinition.EffectScope readEffectScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return EnchantDefinition.EffectScope.ANY;
        }
        try {
            return EnchantDefinition.EffectScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EnchantDefinition.EffectScope.ANY;
        }
    }

    private Set<RuneTag> readTags(String where, String id, List<String> raw) {
        Set<RuneTag> tags = EnumSet.noneOf(RuneTag.class);
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                tags.add(RuneTag.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(where + ": enchant '" + id + "' has an unknown tag '" + value + "', ignoring it.");
            }
        }
        return tags;
    }

    private EnchantDefinition.Level readLevel(String where, String enchantId, int levelNumber,
            ConfigurationSection section, LevelIconDefaults iconDefaults, RuneTier tier) {
        Material tierDefault = tier != null && tier != RuneTier.SEASONAL
                ? RuneFormatting.tierCandle(tier) : Material.STONE;
        Material materialFallback = iconDefaults.material() != null ? iconDefaults.material() : tierDefault;
        Material material = readMaterial(where, "enchant '" + enchantId + "' level " + levelNumber,
                section.getString("material"), materialFallback);
        Integer customModelData = section.contains("custom-model-data")
                ? Integer.valueOf(section.getInt("custom-model-data")) : iconDefaults.customModelData();
        boolean glow = section.getBoolean("glow", false);
        double procChance = clampPercent(section.getDouble("proc-chance", 0D));
        double successRate = clampPercent(section.getDouble("success-rate", 50D));
        double abilityValue = section.getDouble("ability-value", 0D);
        Map<String, Double> effectSettings = readEffectSettings(where, enchantId, levelNumber,
                section.getConfigurationSection("effect-settings"));
        String catalogItem = section.getString("catalog-item");
        if (catalogItem == null || catalogItem.isBlank()) {
            catalogItem = iconDefaults.catalogItem();
        }
        return new EnchantDefinition.Level(levelNumber, material, customModelData, glow, procChance, successRate,
                abilityValue, effectSettings, catalogItem);
    }

    private Map<String, Double> readEffectSettings(String where, String enchantId, int levelNumber, ConfigurationSection settings) {
        Map<String, Double> effectSettings = new LinkedHashMap<>();
        if (settings == null) {
            return effectSettings;
        }
        for (String key : settings.getKeys(false)) {
            Object raw = settings.get(key);
            if (!(raw instanceof Number) && !(raw instanceof String)) {
                plugin.getLogger().warning(where + ": enchant '" + enchantId + "' level " + levelNumber
                        + " has a non-numeric effect setting '" + key + "', ignoring it.");
                continue;
            }
            try {
                effectSettings.put(key.toLowerCase(Locale.ROOT), Double.parseDouble(String.valueOf(raw)));
            } catch (NumberFormatException error) {
                plugin.getLogger().warning(where + ": enchant '" + enchantId + "' level " + levelNumber
                        + " has a non-numeric effect setting '" + key + "', ignoring it.");
            }
        }
        return effectSettings;
    }

    private RuneCosmetic readCosmetic(String where, String path, ConfigurationSection section, Material fallback) {
        if (section == null) {
            plugin.getLogger().warning(where + ": " + path + " is missing, using a fallback icon.");
            return new RuneCosmetic(fallback, null, false);
        }
        Material material = readMaterial(where, path, section.getString("material"), fallback);
        Integer customModelData = section.contains("custom-model-data") ? section.getInt("custom-model-data") : null;
        boolean glow = section.getBoolean("glow", false);
        return new RuneCosmetic(material, customModelData, glow);
    }

    private Material readMaterial(String where, String context, String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
        if (material == null || material.isAir()) {
            plugin.getLogger().warning(where + ": " + context + " has unknown material '" + raw
                    + "', using " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private Set<String> lowercasedSet(List<String> raw) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                result.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static double clampPercent(double value) {
        return Math.max(0D, Math.min(100D, value));
    }

    // ------------------------------------------------------------------
    // Definitions / accessors
    // ------------------------------------------------------------------

    public EnchantDefinition definition(String id) {
        return id == null ? null : definitions.get(id);
    }

    /**
     * Which {@link RuneTier} rolls {@code id}, for coloring a chat message's
     * rune name to match (see {@link me.vertex.core.enchant.RuneFormatting#coloredNameRaw}).
     * {@code null} for a {@code legacy:} id (retired -- belongs to no
     * current tier) or an unknown id.
     */
    public RuneTier tierOf(String id) {
        return id == null ? null : definitionTiers.get(id);
    }

    public boolean isSeasonal(String id) {
        if (id == null) {
            return false;
        }
        EnchantDefinition def = definitions.get(id);
        return def != null && def.isSeasonal();
    }

    /** Every currently registered {@code seasonal: true} enchant id -- e.g. for admin give-command tab completion. */
    public Set<String> seasonalIds() {
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (EnchantDefinition definition : definitions.values()) {
            if (definition.isSeasonal()) {
                ids.add(definition.id());
            }
        }
        return ids;
    }

    /**
     * Authoritatively filters custom enchants on an item according to a predicate.
     * Enchants matching shouldRemove are stripped, while un-matched ones are preserved.
     * Rebuilds PDC tags and lore.
     */
    public boolean filterCustomEnchants(ItemStack item, java.util.function.Predicate<String> shouldRemove) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        Map<String, Integer> current = enchantsOf(item);
        if (current.isEmpty()) {
            return false;
        }
        Map<String, Integer> next = new LinkedHashMap<>();
        Map<String, RuneTier> nextTiers = new LinkedHashMap<>();
        Map<String, RuneTier> currentTiers = enchantTiersOf(item);
        boolean changed = false;
        for (Map.Entry<String, Integer> entry : current.entrySet()) {
            String enchantId = entry.getKey();
            if (shouldRemove.test(enchantId)) {
                changed = true;
            } else {
                next.put(enchantId, entry.getValue());
                RuneTier tier = currentTiers.get(enchantId);
                if (tier != null) {
                    nextTiers.put(enchantId, tier);
                }
            }
        }
        if (!changed) {
            return false;
        }
        writeEnchants(item, next);
        writeEnchantTiers(item, nextTiers);
        rebuildFullLore(item);
        return true;
    }

    public RuneRollTable rollTable(RuneTier tier) {
        return tier == null ? RuneRollTable.of(List.of()) : rollTables.getOrDefault(tier, RuneRollTable.of(List.of()));
    }

    public double runeShopPrice(RuneTier tier) {
        return tier == null ? 0D : runeShopPrices.getOrDefault(tier, 0D);
    }

    /** MONEY (Vault) for every legacy tier by default; a tier may opt into XP_LEVELS via config (Arena does). */
    public Currency runeShopCurrency(RuneTier tier) {
        return tier == null ? Currency.MONEY : runeShopCurrencies.getOrDefault(tier, Currency.MONEY);
    }

    public double luckyGemShopPrice() {
        return luckyGemShopPrice;
    }

    /** Non-null only for a tier configured with `random-success-range` (Arena today), used by the catalog to show a range instead of a fixed rate. */
    public SuccessRange randomSuccessRange(RuneTier tier) {
        return tier == null ? null : randomSuccessRanges.get(tier);
    }

    /**
     * Sums the ability value of every currently-active copy of {@code
     * effect} across the player's equipped items -- e.g. what backs a
     * mob-drop-boost {@link me.vertex.core.booster.BoosterSource}. Mirrors
     * {@link #enchantsOf}/{@link #isEffectActive} rather than a separate
     * per-effect bookkeeping map.
     */
    public double equippedAbilityValue(Player player, String effect, ZoneType currentZone) {
        if (player == null || effect == null) {
            return 0D;
        }
        String normalized = effect.trim().toUpperCase(Locale.ROOT);
        double total = 0D;
        for (ItemStack item : equippedItems(player)) {
            for (Map.Entry<String, Integer> entry : enchantsOf(item).entrySet()) {
                EnchantDefinition definition = definitions.get(entry.getKey());
                if (definition == null || !normalized.equals(definition.effect())
                        || !isEffectActive(item, entry.getKey(), player.getWorld().getName())
                        || !definition.isZoneActive(currentZone)) {
                    continue;
                }
                EnchantDefinition.Level level = definition.level(entry.getValue());
                if (level != null) {
                    total += level.abilityValue();
                }
            }
        }
        return total;
    }

    private static List<ItemStack> equippedItems(Player player) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !item.getType().isAir()) {
                items.add(item);
            }
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        if (main != null && !main.getType().isAir()) {
            items.add(main);
        }
        if (off != null && !off.getType().isAir()) {
            items.add(off);
        }
        return items;
    }

    private static String displayTier(RuneTier tier) {
        if (tier == null) {
            return "";
        }
        return switch (tier) {
            case SIMPLE -> "ꜱɪᴍᴘʟᴇ";
            case ELITE -> "ᴇʟɪᴛᴇ";
            case RARE -> "ʀᴀʀᴇ";
            case LEGENDARY -> "ʟᴇɢᴇɴᴅᴀʀʏ";
            case ARENA -> "ᴀʀᴇɴᴀ";
            case SEASONAL -> "ꜱᴇᴀꜱᴏɴᴀʟ";
        };
    }

    // ------------------------------------------------------------------
    // Rune items
    // ------------------------------------------------------------------

    public ItemStack createRune(RuneTier tier) {
        RuneCosmetic cosmetic = runeCosmetics.getOrDefault(tier, luckyGemCosmetic);
        ItemStack item = new ItemStack(cosmetic.material());
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.FireworkEffectMeta fireworkMeta) {
            fireworkMeta.setEffect(org.bukkit.FireworkEffect.builder()
                    .withColor(org.bukkit.Color.BLACK, org.bukkit.Color.WHITE)
                    .build());
        }
        meta.displayName(RuneFormatting.plain(displayTier(tier) + " ʀᴜɴᴇ", RuneFormatting.tierColor(tier)));
        meta.lore(List.of(
                RuneFormatting.plain("ᴄᴜꜱᴛᴏᴍ ᴇɴᴄʜᴀɴᴛᴍᴇɴᴛ", NamedTextColor.DARK_GRAY),
                RuneFormatting.plain("ʀɪɢʜᴛ-ᴄʟɪᴄᴋ ᴛᴏ ɪᴅᴇɴᴛɪꜰʏ ᴀ ʀᴀɴᴅᴏᴍ ʀᴜɴᴇ", NamedTextColor.GRAY),
                RuneFormatting.plain("ꜱᴛᴀᴄᴋᴀʙʟᴇ ᴜᴘ ᴛᴏ 64", NamedTextColor.DARK_GRAY)));
        if (cosmetic.customModelData() != null) {
            meta.setCustomModelData(cosmetic.customModelData());
        }
        if (cosmetic.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(runeTierKey, PersistentDataType.STRING, tier.name());
        item.setItemMeta(meta);
        return item;
    }

    public boolean isRune(ItemStack item) {
        return tierOf(item) != null;
    }

    public RuneTier tierOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String raw = pdc.get(runeTierKey, PersistentDataType.STRING);
        if (raw != null) {
            try {
                return RuneTier.valueOf(raw);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        // Pre-unification base Arena Rune items only ever carried this byte marker.
        return pdc.has(legacyArenaRuneKey, PersistentDataType.BYTE) ? RuneTier.ARENA : null;
    }

    /**
     * Rolls one Rune, per section 12: identify its tier, use that tier's
     * table, and produce a physical enchant item. Completely independent of
     * that item's later application success -- see {@link #applyEnchant}.
     *
     * @param roll externally supplied, in {@code [0, 1)} -- see the class doc
     */
    public RollOutcome rollRune(RuneTier tier, double roll) {
        if (tier == null) {
            return RollOutcome.empty();
        }
        RuneRollTable table = rollTable(tier);
        RuneRollTable.Selection selection = table.pick(roll);
        if (selection == null) {
            return RollOutcome.empty();
        }
        ItemStack created = createEnchantItem(selection.enchantId(), selection.level(), tier);
        if (created == null) {
            return RollOutcome.empty();
        }
        SuccessRange range = randomSuccessRanges.get(tier);
        double randomized;
        if (range != null) {
            randomized = range.min() + ThreadLocalRandom.current().nextDouble() * (range.max() - range.min());
        } else {
            // Jitter each individual roll around its level's configured base
            // rate instead of every same-level item sharing one fixed value.
            // Besides giving each rolled item its own identity, this keeps
            // otherwise-identical same-level items from silently merging
            // into one ambiguous inventory stack (their PersistentDataContainer
            // now almost always differs), without touching the deliberate
            // per-level/per-tier success curve the jitter is centered on.
            EnchantDefinition.Level levelConfig = definitions.get(selection.enchantId()).level(selection.level());
            double jitter = (ThreadLocalRandom.current().nextDouble() * 2D - 1D) * successRateJitterPercent;
            randomized = levelConfig.successRate() + jitter;
        }
        ItemMeta meta = created.getItemMeta();
        meta.getPersistentDataContainer().set(enchantItemSuccessKey, PersistentDataType.DOUBLE, clampPercent(randomized));
        created.setItemMeta(meta);
        renderEnchantItem(created);
        return new RollOutcome(RollResult.OK, created, selection.enchantId(), selection.level());
    }

    // ------------------------------------------------------------------
    // Physical enchant items
    // ------------------------------------------------------------------

    public ItemStack createEnchantItem(String enchantId, int level, RuneTier originTier) {
        EnchantDefinition definition = definitions.get(enchantId);
        EnchantDefinition.Level levelConfig = definition == null ? null : definition.level(level);
        if (definition == null || levelConfig == null) {
            return null;
        }
        if (originTier == RuneTier.SEASONAL) {
            ItemStack baked = levelConfig.catalogItem() != null
                    ? createCatalogedSeasonalItem(enchantId, level, levelConfig.catalogItem())
                    : findCatalogedSeasonalItemByEnchant(enchantId, level);
            if (baked != null) {
                return baked;
            }
            // Neither an explicit catalog-item link nor a discoverable
            // catalog entry carrying this ability exists yet -- fall
            // through to the generic placeholder rather than handing out
            // nothing.
        }
        Material material = originTier == RuneTier.SEASONAL
                ? levelConfig.material() : RuneFormatting.tierCandle(originTier);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (originTier == RuneTier.SEASONAL && levelConfig.customModelData() != null) {
            meta.setCustomModelData(levelConfig.customModelData());
        }
        if (levelConfig.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(enchantItemIdKey, PersistentDataType.STRING, enchantId);
        pdc.set(enchantItemLevelKey, PersistentDataType.INTEGER, level);
        pdc.set(enchantItemOriginTierKey, PersistentDataType.STRING, originTier.name());
        pdc.set(enchantItemSuccessKey, PersistentDataType.DOUBLE, levelConfig.successRate());
        item.setItemMeta(meta);
        renderEnchantItem(item);
        return item;
    }

    /**
     * The config-driven "tie an ability directly to a real item" path: a
     * clone of the catalog's saved ItemStack (real material/custom model
     * data/lore/vanilla enchants/any other plugin's PDC, untouched) with
     * this ability baked onto it via {@link #bakeEnchantOnto} -- the exact
     * item worn, already active, no separate rune-application step. Null
     * when either no catalog is wired yet or {@code catalogItemId} isn't
     * (yet) saved in it, so the caller can fall back to the generic icon.
     */
    private ItemStack createCatalogedSeasonalItem(String enchantId, int level, String catalogItemId) {
        SeasonalItemCatalog catalog = seasonalCatalog;
        if (catalog == null) {
            return null;
        }
        ItemStack base = catalog.get(catalogItemId);
        if (base == null) {
            plugin.getLogger().warning(RUNES_CONFIG_PATH + ": seasonal enchant '" + enchantId + "' level " + level
                    + " references catalog-item '" + catalogItemId + "', which hasn't been saved yet "
                    + "(/seasonal catalog save " + catalogItemId + "); using the placeholder icon instead.");
            return null;
        }
        return bakeEnchantOnto(base, enchantId, level, RuneTier.SEASONAL);
    }

    /**
     * The zero-config "tie an ability directly to a real item" path:
     * {@code /seasonal catalog save} already bakes an ability onto the item
     * it saves (see {@link me.vertex.core.enchant.SeasonalCommand}), so the
     * saved item itself already records which ability it belongs to --
     * nothing needs wiring back into {@code runes.yml} by hand. This scans
     * the catalog for the (expected to be unique) entry already carrying
     * {@code enchantId} and re-bakes it at the requested {@code level}. An
     * explicit {@code catalog-item} config link, checked by the caller
     * first, always wins when both exist. Null if no catalog entry carries
     * this ability yet.
     */
    private ItemStack findCatalogedSeasonalItemByEnchant(String enchantId, int level) {
        SeasonalItemCatalog catalog = seasonalCatalog;
        if (catalog == null) {
            return null;
        }
        for (String catalogId : catalog.ids()) {
            ItemStack candidate = catalog.get(catalogId);
            if (candidate != null && enchantsOf(candidate).containsKey(enchantId)) {
                return bakeEnchantOnto(candidate, enchantId, level, RuneTier.SEASONAL);
            }
        }
        return null;
    }

    /** Rebuilds the complete, standardized presentation from durable Rune data. */
    private void renderEnchantItem(ItemStack item) {
        EnchantItemInfo info = enchantItemInfo(item);
        if (info == null || !item.hasItemMeta()) {
            return;
        }
        EnchantDefinition definition = definitions.get(info.enchantId());
        EnchantDefinition.Level level = definition == null ? null : definition.level(info.level());
        if (definition == null || level == null) {
            return;
        }
        double success = successChance(item);
        List<Component> lore = new ArrayList<>();
        lore.add(RuneFormatting.plain("ᴄᴜꜱᴛᴏᴍ ᴇɴᴄʜᴀɴᴛᴍᴇɴᴛ", NamedTextColor.DARK_GRAY));
        lore.add(RuneFormatting.plain(RuneFormatting.smallCaps(definition.description()) + ": +"
                + RuneFormatting.percent(level.abilityValue()), NamedTextColor.GRAY));
        if (level.procChance() > 0D) {
            lore.add(RuneFormatting.plain("ᴘʀᴏᴄ ᴄʜᴀɴᴄᴇ: " + RuneFormatting.percent(level.procChance()) + "%",
                    NamedTextColor.GRAY));
        }
        lore.add(RuneFormatting.plain("ᴀᴘᴘʟɪᴄᴀᴛɪᴏɴ ꜱᴜᴄᴄᴇꜱꜱ: ", NamedTextColor.GRAY)
                .append(RuneFormatting.plain(RuneFormatting.percent(success) + "%", NamedTextColor.GREEN))
                .append(RuneFormatting.plain(" / ꜰᴀɪʟ: ", NamedTextColor.GRAY))
                .append(RuneFormatting.plain(RuneFormatting.percent(100D - success) + "%", NamedTextColor.RED)));
        lore.add(RuneFormatting.plain("ᴀᴘᴘʟɪᴇꜱ ᴛᴏ: ", NamedTextColor.GRAY)
                .append(RuneFormatting.plain(RuneFormatting.smallCaps(String.join(", ", definition.compatibleTypes())),
                        NamedTextColor.GREEN)));
        if (definition.isSeasonal()) {
            // Every piece of the current seasonal (Fallen) set only ever
            // comes from that season's crate, never a shop/roll -- this
            // badge line makes that obvious on the item itself, not just in
            // menus/marketing copy.
            lore.add(RuneFormatting.plain("🔱 Fallen Crate Exclusive 🔱", NamedTextColor.GOLD));
        }

        ItemMeta meta = item.getItemMeta();
        meta.displayName(RuneFormatting.titleFor(info.originTier(), definition.displayName(), info.level(),
                info.level() == definition.maxLevel()));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    public boolean isEnchantItem(ItemStack item) {
        return enchantItemInfo(item) != null;
    }

    public EnchantItemInfo enchantItemInfo(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String enchantId = pdc.get(enchantItemIdKey, PersistentDataType.STRING);
        Integer level = pdc.get(enchantItemLevelKey, PersistentDataType.INTEGER);
        String tierRaw = pdc.get(enchantItemOriginTierKey, PersistentDataType.STRING);
        if (enchantId != null && level != null && tierRaw != null) {
            RuneTier tier;
            try {
                tier = RuneTier.valueOf(tierRaw);
            } catch (IllegalArgumentException e) {
                tier = RuneTier.SIMPLE;
            }
            return new EnchantItemInfo(enchantId, level, tier);
        }
        // Pre-unification Arena Runes stored "effect-id:level" (kebab-case,
        // e.g. "void-shield:12") under a different key entirely -- items
        // already rolled from before Arena joined this registry keep reading
        // correctly here instead of needing a forced migration pass.
        String legacyRaw = pdc.get(legacyArenaEnchantKey, PersistentDataType.STRING);
        if (legacyRaw == null) {
            return null;
        }
        String[] split = legacyRaw.split(":", 2);
        if (split.length != 2) {
            return null;
        }
        try {
            return new EnchantItemInfo(split[0].replace('-', '_'), Integer.parseInt(split[1]), RuneTier.ARENA);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Lucky Gems
    // ------------------------------------------------------------------

    public ItemStack createLuckyGem() {
        ItemStack item = new ItemStack(luckyGemCosmetic.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(RuneFormatting.plain("ʟᴜᴄᴋʏ ɢᴇᴍ", NamedTextColor.GREEN));
        meta.lore(List.of(
                RuneFormatting.plain("ᴄᴜꜱᴛᴏᴍ ᴇɴᴄʜᴀɴᴛᴍᴇɴᴛ", NamedTextColor.DARK_GRAY),
                RuneFormatting.plain("ɪɴᴄʀᴇᴀꜱᴇꜱ ꜱᴜᴄᴄᴇꜱꜱ: +3.50%", NamedTextColor.GREEN),
                RuneFormatting.plain("ᴅʀᴀɢ ᴏɴᴛᴏ ᴀ ʀᴜɴᴇ ᴛᴏ ᴀᴘᴘʟʏ", NamedTextColor.DARK_GRAY)));
        if (luckyGemCosmetic.customModelData() != null) {
            meta.setCustomModelData(luckyGemCosmetic.customModelData());
        }
        if (luckyGemCosmetic.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(luckyGemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Deliberately not tagged with {@link TrackedItemIds} -- see {@link
     * ItemKind}'s class doc for why a stackable, fully-consumed commodity
     * item is out of scope for per-instance dupe tracking.
     */
    public boolean isLuckyGem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(luckyGemKey, PersistentDataType.BYTE)
                || pdc.has(legacyArenaLuckyGemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------

    /**
     * The chance travels with the identified Rune. Older runes that used a
     * stored gem count are converted logically at the new universal rate.
     */
    public double successChance(ItemStack enchantItem) {
        EnchantItemInfo info = enchantItemInfo(enchantItem);
        if (info == null) {
            return 0D;
        }
        EnchantDefinition definition = definitions.get(info.enchantId());
        EnchantDefinition.Level level = definition == null ? null : definition.level(info.level());
        if (level == null) {
            return 0D;
        }
        PersistentDataContainer pdc = enchantItem.getItemMeta().getPersistentDataContainer();
        Double stored = pdc.get(enchantItemSuccessKey, PersistentDataType.DOUBLE);
        if (stored != null) {
            return clampPercent(stored);
        }
        Double legacyArenaStored = pdc.get(legacyArenaEnchantSuccessKey, PersistentDataType.DOUBLE);
        if (legacyArenaStored != null) {
            return clampPercent(legacyArenaStored);
        }
        Integer historicalGemCount = pdc.get(legacyDraggedGemCountKey, PersistentDataType.INTEGER);
        double chance = level.successRate() + Math.max(0, historicalGemCount == null ? 0 : historicalGemCount)
                * LUCKY_GEM_BONUS_PERCENT;
        return Math.max(0D, Math.min(100D, chance));
    }

    /** Returns an independently stack-safe, re-rendered upgraded Rune, or null at 100%. */
    public ItemStack addLuckyGem(ItemStack enchantItem) {
        if (!isEnchantItem(enchantItem)) {
            return null;
        }
        double current = successChance(enchantItem);
        if (current >= 100D) {
            return null;
        }
        ItemStack upgraded = enchantItem.clone();
        upgraded.setAmount(1);
        ItemMeta meta = upgraded.getItemMeta();
        meta.getPersistentDataContainer().set(enchantItemSuccessKey, PersistentDataType.DOUBLE,
                clampPercent(current + LUCKY_GEM_BONUS_PERCENT));
        meta.getPersistentDataContainer().remove(legacyDraggedGemCountKey);
        upgraded.setItemMeta(meta);
        renderEnchantItem(upgraded);
        return upgraded;
    }

    /**
     * Applies one physical enchant item to a target item, per sections
     * 15-18: compatibility, then same-enchant level-replacement rules, then
     * (only for a valid attempt) a success/failure roll using the chance
     * already stored on the Rune. A rejection never mutates {@code target}
     * and never consumes anything; a valid attempt (success or failure)
     * consumes only the Rune.
     *
     * @param roll externally supplied, in {@code [0, 1)} -- see the class doc
     */
    public ApplyOutcome applyEnchant(ItemStack target, ItemStack enchantItem, int ignoredLegacyGemCount, double roll) {
        if (target == null || target.getType().isAir() || !target.hasItemMeta()) {
            return ApplyOutcome.reject(ApplyResult.REJECT_INVALID, null, 0);
        }
        EnchantItemInfo info = enchantItemInfo(enchantItem);
        if (info == null) {
            return ApplyOutcome.reject(ApplyResult.REJECT_INVALID, null, 0);
        }
        EnchantDefinition definition = definitions.get(info.enchantId());
        EnchantDefinition.Level levelConfig = definition == null ? null : definition.level(info.level());
        if (definition == null || levelConfig == null) {
            return ApplyOutcome.reject(ApplyResult.REJECT_INVALID, info.enchantId(), info.level());
        }
        if (!definition.isCompatible(target.getType())) {
            return ApplyOutcome.reject(ApplyResult.REJECT_INCOMPATIBLE, info.enchantId(), info.level());
        }

        int existing = levelOf(target, info.enchantId());
        if (existing == info.level()) {
            return ApplyOutcome.reject(ApplyResult.REJECT_EQUAL_LEVEL, info.enchantId(), info.level());
        }
        if (existing > info.level()) {
            return ApplyOutcome.reject(ApplyResult.REJECT_HIGHER_EXISTS, info.enchantId(), info.level());
        }

        double chance = successChance(enchantItem);
        boolean success = roll * 100D < chance;
        if (success) {
            applyEnchantAndRerender(target, info.enchantId(), info.level(), info.originTier());
            trackedItemIds.ensureInstanceId(target, ItemKind.ENCHANTED_ITEM);
        }
        return new ApplyOutcome(success ? ApplyResult.SUCCESS : ApplyResult.FAILURE, true,
                info.enchantId(), info.level(), chance);
    }

    /**
     * Bakes {@code enchantId} at {@code level} directly onto {@code item}
     * (mutated in place and returned) -- for admin-distributed seasonal
     * gear that <em>is</em> the target item (a real, cosmetic piece with
     * its own material/custom model data/lore/vanilla enchants), not a
     * standalone Rune meant to be applied onto arbitrary compatible gear
     * later. Skips every runtime {@link #applyEnchant} gate (compatible
     * type, existing level, roll chance) since there's no player-facing
     * apply action here -- the item is simply created already carrying the
     * ability, active the moment it's worn.
     */
    public ItemStack bakeEnchantOnto(ItemStack item, String enchantId, int level, RuneTier originTier) {
        if (item == null || item.getType().isAir() || !definitions.containsKey(enchantId)) {
            return item;
        }
        applyEnchantAndRerender(item, enchantId, level, originTier);
        return item;
    }

    // ------------------------------------------------------------------
    // Reading applied state
    // ------------------------------------------------------------------

    public int levelOf(ItemStack item, String enchantId) {
        return enchantsOf(item).getOrDefault(enchantId, 0);
    }

    public Map<String, Integer> enchantsOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return new LinkedHashMap<>();
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(enchantsKey, PersistentDataType.STRING);
        return parseEnchants(raw);
    }

    /**
     * Whether this enchant should currently have any live effect on this
     * item -- false whenever the enchant simply isn't present, AND false
     * when the enchant's own world-restriction config disables it in
     * {@code worldName}, even though the enchant remains fully visible on
     * the item and fully intact in its data either way (section 24). There
     * is no automatic SafeZone/WarZone/claim override here -- only the
     * enchant's own configured restriction applies.
     */
    public boolean isEffectActive(ItemStack item, String enchantId, String worldName) {
        if (levelOf(item, enchantId) <= 0) {
            return false;
        }
        EnchantDefinition definition = definitions.get(enchantId);
        return definition != null && definition.isWorldActive(worldName);
    }

    // ------------------------------------------------------------------
    // Persistence across legitimate vanilla transformations
    // ------------------------------------------------------------------

    /**
     * Carries this item's Vertex identity (see {@link TrackedItemIds#copy})
     * and any active custom enchant data + pristine base lore from {@code
     * from} onto {@code to} -- for an anvil rename/repair or a smithing-
     * table upgrade, where the platform computes a distinct result
     * ItemStack that this plugin cannot assume already inherited that PDC.
     * A no-op (returns false) when {@code from} carries nothing of ours to
     * begin with, so calling this on every ordinary anvil/smithing use
     * (most of which involve no Vertex item at all) costs nothing.
     *
     * @return true when something was actually copied onto {@code to}
     */
    public boolean preserveAcrossTransform(ItemStack from, ItemStack to) {
        if (from == null || to == null || from.getType().isAir() || to.getType().isAir() || !from.hasItemMeta()) {
            return false;
        }
        boolean identityCopied = trackedItemIds.copy(from, to);
        boolean enchantsCopied = copyEnchantData(from, to);
        return identityCopied || enchantsCopied;
    }

    private boolean copyEnchantData(ItemStack from, ItemStack to) {
        ItemMeta fromMeta = from.getItemMeta();
        String enchants = fromMeta.getPersistentDataContainer().get(enchantsKey, PersistentDataType.STRING);
        if (enchants == null || enchants.isBlank()) {
            return false;
        }
        String baseLore = fromMeta.getPersistentDataContainer().get(baseLoreKey, PersistentDataType.STRING);
        String enchantTiers = fromMeta.getPersistentDataContainer().get(enchantTiersKey, PersistentDataType.STRING);

        ItemMeta toMeta = to.getItemMeta();
        if (toMeta == null) {
            return false;
        }
        toMeta.getPersistentDataContainer().set(enchantsKey, PersistentDataType.STRING, enchants);
        if (enchantTiers != null) {
            toMeta.getPersistentDataContainer().set(enchantTiersKey, PersistentDataType.STRING, enchantTiers);
        }
        if (baseLore != null) {
            toMeta.getPersistentDataContainer().set(baseLoreKey, PersistentDataType.STRING, baseLore);
        }
        to.setItemMeta(toMeta);
        rebuildFullLore(to);
        return true;
    }

    // ------------------------------------------------------------------
    // Lore rendering
    // ------------------------------------------------------------------

    private void applyEnchantAndRerender(ItemStack target, String enchantId, int level, RuneTier originTier) {
        ItemMeta meta = target.getItemMeta();
        ensureBaseLoreSnapshot(target, meta);
        Map<String, Integer> enchants = enchantsOf(target);
        enchants.put(enchantId, level);
        writeEnchants(target, enchants);
        Map<String, RuneTier> enchantTiers = enchantTiersOf(target);
        enchantTiers.put(enchantId, originTier);
        writeEnchantTiers(target, enchantTiers);
        rebuildFullLore(target);
    }

    private void ensureBaseLoreSnapshot(ItemStack item, ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(baseLoreKey, PersistentDataType.STRING)) {
            return;
        }
        List<Component> currentLore = stripArenaRuneLore(meta,
                meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        StringBuilder serialized = new StringBuilder();
        for (Component line : currentLore) {
            if (!serialized.isEmpty()) {
                serialized.append(LORE_LINE_SEPARATOR);
            }
            serialized.append(MessageFormatter.serialize(line));
        }
        pdc.set(baseLoreKey, PersistentDataType.STRING, serialized.toString());
        item.setItemMeta(meta);
    }

    private List<String> readBaseLore(ItemMeta meta) {
        String raw = meta.getPersistentDataContainer().get(baseLoreKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return List.of(LORE_LINE_SPLIT.split(raw, -1));
    }

    /** Rebuilds base lore plus one standardized line for each applied custom enchant. */
    private void rebuildFullLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> baseLore = readBaseLore(meta);
        Map<String, Integer> enchants = parseEnchants(
                meta.getPersistentDataContainer().get(enchantsKey, PersistentDataType.STRING));
        Map<String, RuneTier> enchantTiers = enchantTiersOf(item);
        List<Component> lore = new ArrayList<>();
        for (String line : baseLore) {
            lore.add(MessageFormatter.deserialize(line));
        }
        // Seasonal always renders last, regardless of application order --
        // it's a distinct, later-added visual category (gold gradient, see
        // RuneFormatting#seasonalTitle) meant to stand apart from the tier
        // enchants above it, not interleaved with them.
        List<Component> normalLines = new ArrayList<>();
        List<Component> seasonalLines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            EnchantDefinition definition = definitions.get(entry.getKey());
            if (definition == null) {
                continue;
            }
            if (definition.level(entry.getValue()) == null) {
                continue;
            }
            RuneTier tier = enchantTiers.getOrDefault(entry.getKey(), RuneTier.SIMPLE);
            Component line = RuneFormatting.titleFor(tier, definition.displayName(), entry.getValue(),
                    entry.getValue() == definition.maxLevel());
            (tier == RuneTier.SEASONAL ? seasonalLines : normalLines).add(line);
        }
        lore.addAll(normalLines);
        lore.addAll(renderArenaRuneLines(meta));
        lore.addAll(seasonalLines);
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /**
     * Renders lore for the pre-unification "arena_enchants" applied-effects
     * PDC format -- items enchanted through the old {@code ArenaRuneManager}
     * before Arena Runes joined this registry. New applications always go
     * through {@link #applyEnchantAndRerender} instead, so this is a
     * read-only compatibility path, not a second application mechanism.
     */
    private List<Component> renderArenaRuneLines(ItemMeta meta) {
        List<Component> lore = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : legacyArenaRunesOf(meta).entrySet()) {
            lore.add(RuneFormatting.appliedLine(legacyArenaDisplayName(entry.getKey()), entry.getValue(),
                    NamedTextColor.BLUE, entry.getValue() == 20));
        }
        return lore;
    }

    private List<Component> stripArenaRuneLore(ItemMeta meta, List<Component> source) {
        List<Component> lore = new ArrayList<>(source);
        int header = -1;
        for (int index = 0; index < lore.size(); index++) {
            if ("Arena Runes".equals(PlainTextComponentSerializer.plainText().serialize(lore.get(index)))) {
                header = index;
            }
        }
        if (header >= 0) {
            int start = header;
            if (start > 0 && PlainTextComponentSerializer.plainText().serialize(lore.get(start - 1)).isEmpty()) {
                start--;
            }
            lore = new ArrayList<>(lore.subList(0, start));
        }
        for (Map.Entry<String, Integer> entry : legacyArenaRunesOf(meta).entrySet()) {
            String displayName = legacyArenaDisplayName(entry.getKey());
            String expected = displayName + " " + roman(entry.getValue());
            String standardized = RuneFormatting.smallCaps(displayName) + " " + RuneFormatting.roman(entry.getValue());
            for (int index = lore.size() - 1; index >= 0; index--) {
                String plain = PlainTextComponentSerializer.plainText().serialize(lore.get(index));
                if (expected.equals(plain) || standardized.equals(plain)) {
                    lore.remove(index);
                    break;
                }
            }
        }
        return lore;
    }

    /** @return legacy kebab-case effect id (e.g. {@code "void-shield"}) to applied level. */
    private Map<String, Integer> legacyArenaRunesOf(ItemMeta meta) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String raw = meta.getPersistentDataContainer().get(arenaAppliedKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String entry : raw.split(",")) {
            String[] split = entry.split(":", 2);
            if (split.length != 2) {
                continue;
            }
            try {
                int level = Integer.parseInt(split[1]);
                if (level >= 1 && level <= 20) {
                    result.put(split[0], level);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed legacy PDC rather than losing valid lore.
            }
        }
        return result;
    }

    private String legacyArenaDisplayName(String legacyEffectId) {
        EnchantDefinition definition = definitions.get(legacyEffectId.replace('-', '_'));
        return definition != null ? definition.displayName() : legacyEffectId;
    }

    private static String roman(int value) {
        if (value < 1 || value > 3999) {
            return String.valueOf(value);
        }
        int[] amounts = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < amounts.length; index++) {
            while (value >= amounts[index]) {
                result.append(numerals[index]);
                value -= amounts[index];
            }
        }
        return result.toString();
    }

    // ------------------------------------------------------------------
    // PDC (de)serialization for the active-enchants map
    // ------------------------------------------------------------------

    private Map<String, Integer> parseEnchants(String raw) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String part : raw.split(ENTRY_SEPARATOR)) {
            String[] fields = part.split(FIELD_SEPARATOR, 2);
            if (fields.length != 2) {
                continue;
            }
            try {
                map.put(fields[0], Integer.parseInt(fields[1]));
            } catch (NumberFormatException ignored) {
                // A corrupt/foreign entry -- skip it rather than fail the whole item.
            }
        }
        return map;
    }

    private String serializeEnchants(Map<String, Integer> map) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(ENTRY_SEPARATOR);
            }
            builder.append(entry.getKey()).append(FIELD_SEPARATOR).append(entry.getValue());
        }
        return builder.toString();
    }

    private Map<String, RuneTier> enchantTiersOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return new LinkedHashMap<>();
        }
        return parseEnchantTiers(item.getItemMeta().getPersistentDataContainer().get(enchantTiersKey, PersistentDataType.STRING));
    }

    private Map<String, RuneTier> parseEnchantTiers(String raw) {
        Map<String, RuneTier> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String part : raw.split(ENTRY_SEPARATOR)) {
            String[] fields = part.split(FIELD_SEPARATOR, 2);
            if (fields.length != 2) {
                continue;
            }
            try {
                map.put(fields[0], RuneTier.valueOf(fields[1]));
            } catch (IllegalArgumentException ignored) {
                // A corrupt or legacy tier entry falls back to the simple gray style.
            }
        }
        return map;
    }

    private String serializeEnchantTiers(Map<String, RuneTier> map) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, RuneTier> entry : map.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append(ENTRY_SEPARATOR);
            }
            builder.append(entry.getKey()).append(FIELD_SEPARATOR).append(entry.getValue().name());
        }
        return builder.toString();
    }

    private void writeEnchants(ItemStack item, Map<String, Integer> enchants) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(enchantsKey, PersistentDataType.STRING, serializeEnchants(enchants));
        item.setItemMeta(meta);
    }

    private void writeEnchantTiers(ItemStack item, Map<String, RuneTier> enchantTiers) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(enchantTiersKey, PersistentDataType.STRING, serializeEnchantTiers(enchantTiers));
        item.setItemMeta(meta);
    }

}
