package me.vertex.core.enchant;

import me.vertex.core.item.ItemKind;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.menu.MenuPlaceholders;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p><b>Duplication</b>: every physical Rune and physical enchant item is
 * tagged via {@link TrackedItemIds} at creation, and every target item is
 * tagged the moment it first receives a successful application -- see
 * {@code me.vertex.core.dupe.DupeManager#shouldTrack}, which now treats any
 * item carrying a real {@link ItemKind} as worth tracking. No second
 * detection system is built here; tagging is the entire integration.
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

    public record ApplyOutcome(ApplyResult result, boolean consumedEnchantItem, boolean consumedGems,
            String enchantId, int level, double chanceUsed) {

        static ApplyOutcome reject(ApplyResult result, String enchantId, int level) {
            return new ApplyOutcome(result, false, false, enchantId, level, 0D);
        }
    }

    /** What a physical (rolled, not-yet-applied) enchant item's PDC says it is. */
    public record EnchantItemInfo(String enchantId, int level, RuneTier originTier) {
    }

    private record RuneCosmetic(Material material, Integer customModelData, String name, List<String> lore,
            boolean glow) {
    }

    private final Plugin plugin;
    private final TrackedItemIds trackedItemIds;

    private final NamespacedKey runeTierKey;
    private final NamespacedKey enchantItemIdKey;
    private final NamespacedKey enchantItemLevelKey;
    private final NamespacedKey enchantItemOriginTierKey;
    private final NamespacedKey luckyGemKey;
    private final NamespacedKey enchantsKey;
    private final NamespacedKey baseLoreKey;

    private volatile Map<String, EnchantDefinition> definitions = Map.of();
    private volatile Map<RuneTier, RuneRollTable> rollTables = Map.of();
    private volatile Map<RuneTier, RuneCosmetic> runeCosmetics = Map.of();
    private volatile Map<RuneTier, Double> runeShopPrices = Map.of();
    private volatile Map<RuneTier, Double> luckyGemEffectiveness = Map.of();
    private volatile RuneCosmetic luckyGemCosmetic = new RuneCosmetic(Material.EMERALD, null, "Lucky Gem", List.of(), true);

    public EnchantManager(Plugin plugin, TrackedItemIds trackedItemIds) {
        this.plugin = plugin;
        this.trackedItemIds = trackedItemIds;
        this.runeTierKey = new NamespacedKey(plugin, "rune_tier");
        this.enchantItemIdKey = new NamespacedKey(plugin, "enchant_item_id");
        this.enchantItemLevelKey = new NamespacedKey(plugin, "enchant_item_level");
        this.enchantItemOriginTierKey = new NamespacedKey(plugin, "enchant_item_origin_tier");
        this.luckyGemKey = new NamespacedKey(plugin, "lucky_gem");
        this.enchantsKey = new NamespacedKey(plugin, "custom_enchants");
        this.baseLoreKey = new NamespacedKey(plugin, "custom_enchants_base_lore");
    }

    // ------------------------------------------------------------------
    // Config loading
    // ------------------------------------------------------------------

    public void load() {
        loadEnchants();
        loadRunes(); // depends on `definitions` above, to validate table references
    }

    private void loadEnchants() {
        File file = new File(plugin.getDataFolder(), "enchants.yml");
        if (!file.exists()) {
            plugin.saveResource("enchants.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        Map<String, EnchantDefinition> loaded = new LinkedHashMap<>();
        ConfigurationSection root = config.getConfigurationSection("enchants");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                EnchantDefinition definition = readEnchant("enchants.yml", id, section);
                if (definition != null) {
                    loaded.put(id, definition);
                }
            }
        }
        definitions = Map.copyOf(loaded);
    }

    private EnchantDefinition readEnchant(String where, String id, ConfigurationSection section) {
        String displayName = section.getString("display-name", id);
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

        List<EnchantDefinition.Level> levels = new ArrayList<>();
        ConfigurationSection levelsSection = section.getConfigurationSection("levels");
        if (levelsSection != null) {
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
                levels.add(readLevel(where, id, levelNumber, levelSection));
            }
        }
        if (levels.isEmpty()) {
            plugin.getLogger().warning(where + ": enchant '" + id + "' has no levels configured, skipping it entirely.");
            return null;
        }
        return new EnchantDefinition(id, displayName, compatible, enabledWorlds, disabledWorlds, levels);
    }

    private EnchantDefinition.Level readLevel(String where, String enchantId, int levelNumber, ConfigurationSection section) {
        Material material = readMaterial(where, "enchant '" + enchantId + "' level " + levelNumber,
                section.getString("material"), Material.STONE);
        Integer customModelData = section.contains("custom-model-data") ? section.getInt("custom-model-data") : null;
        String name = section.getString("name", enchantId + " " + levelNumber);
        List<String> lore = section.getStringList("lore");
        boolean glow = section.getBoolean("glow", false);
        double procChance = clampPercent(section.getDouble("proc-chance", 0D));
        double successRate = clampPercent(section.getDouble("success-rate", 50D));
        double abilityValue = section.getDouble("ability-value", 0D);
        return new EnchantDefinition.Level(levelNumber, material, customModelData, name, lore, glow, procChance,
                successRate, abilityValue);
    }

    private void loadRunes() {
        File file = new File(plugin.getDataFolder(), "runes.yml");
        if (!file.exists()) {
            plugin.saveResource("runes.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        luckyGemCosmetic = readCosmetic("runes.yml", "lucky-gem",
                config.getConfigurationSection("lucky-gem"), Material.EMERALD);

        Map<RuneTier, Double> effectiveness = new EnumMap<>(RuneTier.class);
        ConfigurationSection effSection = config.getConfigurationSection("lucky-gem-effectiveness");
        for (RuneTier tier : RuneTier.values()) {
            double value = effSection == null ? 0D : effSection.getDouble(tier.name(), 0D);
            effectiveness.put(tier, Math.max(0D, value));
        }
        luckyGemEffectiveness = Map.copyOf(effectiveness);

        Map<RuneTier, RuneCosmetic> cosmetics = new EnumMap<>(RuneTier.class);
        Map<RuneTier, Double> prices = new EnumMap<>(RuneTier.class);
        Map<RuneTier, RuneRollTable> tables = new EnumMap<>(RuneTier.class);
        ConfigurationSection runesSection = config.getConfigurationSection("runes");
        for (RuneTier tier : RuneTier.values()) {
            ConfigurationSection tierSection = runesSection == null ? null
                    : runesSection.getConfigurationSection(tier.name());
            cosmetics.put(tier, readCosmetic("runes.yml", "runes." + tier.name(), tierSection, Material.AMETHYST_SHARD));
            prices.put(tier, tierSection == null ? 0D : Math.max(0D, tierSection.getDouble("shop-price", 0D)));
            tables.put(tier, readRollTable("runes.yml", tier, tierSection));
        }
        runeCosmetics = Map.copyOf(cosmetics);
        runeShopPrices = Map.copyOf(prices);
        rollTables = Map.copyOf(tables);
    }

    private RuneRollTable readRollTable(String where, RuneTier tier, ConfigurationSection tierSection) {
        List<RuneRollTable.Entry> entries = new ArrayList<>();
        if (tierSection != null) {
            for (Map<?, ?> raw : tierSection.getMapList("table")) {
                Object enchantObj = raw.get("enchant");
                Object levelObj = raw.get("level");
                Object weightObj = raw.get("weight");
                if (enchantObj == null || levelObj == null || weightObj == null) {
                    plugin.getLogger().warning(where + ": runes." + tier.name()
                            + ".table has an incomplete entry, skipping it.");
                    continue;
                }
                String enchantId = String.valueOf(enchantObj);
                int level;
                double weight;
                try {
                    level = Integer.parseInt(String.valueOf(levelObj));
                    weight = Double.parseDouble(String.valueOf(weightObj));
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning(where + ": runes." + tier.name()
                            + ".table has a non-numeric level/weight, skipping it.");
                    continue;
                }
                EnchantDefinition definition = definitions.get(enchantId);
                if (definition == null || definition.level(level) == null) {
                    plugin.getLogger().warning(where + ": runes." + tier.name() + ".table references unknown enchant/level '"
                            + enchantId + "' level " + level + ", skipping it.");
                    continue;
                }
                entries.add(new RuneRollTable.Entry(enchantId, level, weight));
            }
        }
        return RuneRollTable.of(entries);
    }

    private RuneCosmetic readCosmetic(String where, String path, ConfigurationSection section, Material fallback) {
        if (section == null) {
            plugin.getLogger().warning(where + ": " + path + " is missing, using a fallback icon.");
            return new RuneCosmetic(fallback, null, path, List.of(), false);
        }
        Material material = readMaterial(where, path, section.getString("material"), fallback);
        Integer customModelData = section.contains("custom-model-data") ? section.getInt("custom-model-data") : null;
        String name = section.getString("name", path);
        List<String> lore = section.getStringList("lore");
        boolean glow = section.getBoolean("glow", false);
        return new RuneCosmetic(material, customModelData, name, lore, glow);
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

    public List<String> enchantIds() {
        return List.copyOf(definitions.keySet());
    }

    public RuneRollTable rollTable(RuneTier tier) {
        return tier == null ? RuneRollTable.of(List.of()) : rollTables.getOrDefault(tier, RuneRollTable.of(List.of()));
    }

    public double luckyGemEffectiveness(RuneTier tier) {
        return tier == null ? 0D : luckyGemEffectiveness.getOrDefault(tier, 0D);
    }

    public double runeShopPrice(RuneTier tier) {
        return tier == null ? 0D : runeShopPrices.getOrDefault(tier, 0D);
    }

    private static String displayTier(RuneTier tier) {
        if (tier == null) {
            return "";
        }
        String name = tier.name();
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Rune items
    // ------------------------------------------------------------------

    public ItemStack createRune(RuneTier tier) {
        RuneCosmetic cosmetic = runeCosmetics.getOrDefault(tier, luckyGemCosmetic);
        ItemStack item = new ItemStack(cosmetic.material());
        ItemMeta meta = item.getItemMeta();
        MenuPlaceholders placeholders = MenuPlaceholders.of().put("tier", displayTier(tier));
        meta.displayName(placeholders.render(cosmetic.name()).getFirst());
        List<Component> lore = new ArrayList<>();
        for (String line : cosmetic.lore()) {
            lore.addAll(placeholders.render(line));
        }
        meta.lore(lore);
        if (cosmetic.customModelData() != null) {
            meta.setCustomModelData(cosmetic.customModelData());
        }
        if (cosmetic.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.getPersistentDataContainer().set(runeTierKey, PersistentDataType.STRING, tier.name());
        item.setItemMeta(meta);
        trackedItemIds.ensureInstanceId(item, ItemKind.RUNE);
        return item;
    }

    public boolean isRune(ItemStack item) {
        return tierOf(item) != null;
    }

    public RuneTier tierOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(runeTierKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return RuneTier.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        ItemStack item = new ItemStack(levelConfig.material());
        ItemMeta meta = item.getItemMeta();
        double rollChance = rollTable(originTier).chancePercent(enchantId, level);
        MenuPlaceholders placeholders = levelPlaceholders(definition, levelConfig, rollChance)
                .put("tier", displayTier(originTier));
        meta.displayName(placeholders.render(levelConfig.name()).getFirst());
        List<Component> lore = new ArrayList<>();
        for (String line : levelConfig.lore()) {
            lore.addAll(placeholders.render(line));
        }
        meta.lore(lore);
        if (levelConfig.customModelData() != null) {
            meta.setCustomModelData(levelConfig.customModelData());
        }
        if (levelConfig.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(enchantItemIdKey, PersistentDataType.STRING, enchantId);
        pdc.set(enchantItemLevelKey, PersistentDataType.INTEGER, level);
        pdc.set(enchantItemOriginTierKey, PersistentDataType.STRING, originTier.name());
        item.setItemMeta(meta);
        trackedItemIds.ensureInstanceId(item, ItemKind.ENCHANTMENT_ITEM);
        return item;
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
        if (enchantId == null || level == null || tierRaw == null) {
            return null;
        }
        RuneTier tier;
        try {
            tier = RuneTier.valueOf(tierRaw);
        } catch (IllegalArgumentException e) {
            tier = RuneTier.SIMPLE;
        }
        return new EnchantItemInfo(enchantId, level, tier);
    }

    // ------------------------------------------------------------------
    // Lucky Gems
    // ------------------------------------------------------------------

    public ItemStack createLuckyGem() {
        ItemStack item = new ItemStack(luckyGemCosmetic.material());
        ItemMeta meta = item.getItemMeta();
        MenuPlaceholders placeholders = MenuPlaceholders.of();
        meta.displayName(placeholders.render(luckyGemCosmetic.name()).getFirst());
        List<Component> lore = new ArrayList<>();
        for (String line : luckyGemCosmetic.lore()) {
            lore.addAll(placeholders.render(line));
        }
        meta.lore(lore);
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
        return item.getItemMeta().getPersistentDataContainer().has(luckyGemKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------

    /**
     * The current success chance a confirm would use right now: the
     * enchant item's configured level success rate, plus this many Lucky
     * Gems' per-tier boost (the ORIGIN tier of the enchant item, per
     * section 18 read alongside {@code runes.yml}'s "Lucky Gem
     * effectiveness per tier" -- not the target item, which has no tier of
     * its own), capped at 100.
     */
    public double effectiveChance(ItemStack enchantItem, int gemCount) {
        EnchantItemInfo info = enchantItemInfo(enchantItem);
        if (info == null) {
            return 0D;
        }
        EnchantDefinition definition = definitions.get(info.enchantId());
        EnchantDefinition.Level level = definition == null ? null : definition.level(info.level());
        if (level == null) {
            return 0D;
        }
        double perGem = luckyGemEffectiveness(info.originTier());
        double chance = level.successRate() + perGem * Math.max(0, gemCount);
        return Math.max(0D, Math.min(100D, chance));
    }

    /**
     * Applies one physical enchant item to a target item, per sections
     * 15-18: compatibility, then same-enchant level-replacement rules, then
     * (only for a valid attempt) a success/failure roll boosted by Lucky
     * Gems. A rejection never mutates {@code target} and never consumes
     * anything; a valid attempt (success or failure) always consumes the
     * enchant item and every placed Lucky Gem, win or lose.
     *
     * @param roll externally supplied, in {@code [0, 1)} -- see the class doc
     */
    public ApplyOutcome applyEnchant(ItemStack target, ItemStack enchantItem, int gemCount, double roll) {
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

        double chance = effectiveChance(enchantItem, gemCount);
        boolean success = roll * 100D < chance;
        if (success) {
            applyEnchantAndRerender(target, info.enchantId(), info.level());
            trackedItemIds.ensureInstanceId(target, ItemKind.ENCHANTED_ITEM);
        }
        return new ApplyOutcome(success ? ApplyResult.SUCCESS : ApplyResult.FAILURE, true, gemCount > 0,
                info.enchantId(), info.level(), chance);
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

        ItemMeta toMeta = to.getItemMeta();
        if (toMeta == null) {
            return false;
        }
        toMeta.getPersistentDataContainer().set(enchantsKey, PersistentDataType.STRING, enchants);
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

    private void applyEnchantAndRerender(ItemStack target, String enchantId, int level) {
        ItemMeta meta = target.getItemMeta();
        ensureBaseLoreSnapshot(target, meta);
        Map<String, Integer> enchants = enchantsOf(target);
        enchants.put(enchantId, level);
        writeEnchants(target, enchants);
        rebuildFullLore(target);
    }

    private void ensureBaseLoreSnapshot(ItemStack item, ItemMeta meta) {
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(baseLoreKey, PersistentDataType.STRING)) {
            return;
        }
        List<Component> currentLore = meta.hasLore() && meta.lore() != null ? meta.lore() : List.of();
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

    /**
     * Rebuilds an item's full lore from scratch: its pristine pre-enchant
     * base lore, then one rendered block per active custom enchant, in the
     * order they were first applied -- vanilla enchantments are never part
     * of this list at all (they render above the lore box automatically,
     * which is exactly section 21's required ordering, for free).
     */
    private void rebuildFullLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        List<String> baseLore = readBaseLore(meta);
        Map<String, Integer> enchants = parseEnchants(
                meta.getPersistentDataContainer().get(enchantsKey, PersistentDataType.STRING));

        List<Component> lore = new ArrayList<>();
        for (String line : baseLore) {
            lore.add(MessageFormatter.deserialize(line));
        }
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            EnchantDefinition definition = definitions.get(entry.getKey());
            if (definition == null) {
                continue;
            }
            EnchantDefinition.Level level = definition.level(entry.getValue());
            if (level == null) {
                continue;
            }
            MenuPlaceholders placeholders = levelPlaceholders(definition, level, 0D);
            for (String line : level.lore()) {
                lore.addAll(placeholders.render(line));
            }
        }
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    private MenuPlaceholders levelPlaceholders(EnchantDefinition definition, EnchantDefinition.Level level,
            double rollChancePercent) {
        return MenuPlaceholders.of()
                .put("enchant", definition.displayName())
                .put("level", level.level())
                .put("proc_chance", formatNumber(level.procChance()))
                .put("success_rate", formatNumber(level.successRate()))
                .put("failure_rate", formatNumber(level.failureRate()))
                .put("ability_value", formatNumber(level.abilityValue()))
                .put("roll_chance", formatNumber(rollChancePercent));
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
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

    private void writeEnchants(ItemStack item, Map<String, Integer> enchants) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(enchantsKey, PersistentDataType.STRING, serializeEnchants(enchants));
        item.setItemMeta(meta);
    }
}
