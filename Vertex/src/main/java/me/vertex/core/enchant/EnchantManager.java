package me.vertex.core.enchant;

import me.vertex.core.item.ItemKind;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

    private record RuneCosmetic(Material material, Integer customModelData, boolean glow) {
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

    private volatile Map<String, EnchantDefinition> definitions = Map.of();
    private volatile Map<RuneTier, RuneRollTable> rollTables = Map.of();
    private volatile Map<RuneTier, RuneCosmetic> runeCosmetics = Map.of();
    private volatile Map<RuneTier, Double> runeShopPrices = Map.of();
    private volatile double luckyGemShopPrice;
    private volatile RuneCosmetic luckyGemCosmetic = new RuneCosmetic(Material.EMERALD, null, true);

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
    }

    Plugin plugin() { return plugin; }

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
        return new EnchantDefinition(id, displayName, description, compatible, enabledWorlds, disabledWorlds, levels);
    }

    private EnchantDefinition.Level readLevel(String where, String enchantId, int levelNumber, ConfigurationSection section) {
        Material material = readMaterial(where, "enchant '" + enchantId + "' level " + levelNumber,
                section.getString("material"), Material.STONE);
        Integer customModelData = section.contains("custom-model-data") ? section.getInt("custom-model-data") : null;
        boolean glow = section.getBoolean("glow", false);
        double procChance = clampPercent(section.getDouble("proc-chance", 0D));
        double successRate = clampPercent(section.getDouble("success-rate", 50D));
        double abilityValue = section.getDouble("ability-value", 0D);
        return new EnchantDefinition.Level(levelNumber, material, customModelData, glow, procChance, successRate,
                abilityValue);
    }

    private void loadRunes() {
        File file = new File(plugin.getDataFolder(), "runes.yml");
        if (!file.exists()) {
            plugin.saveResource("runes.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        luckyGemCosmetic = readCosmetic("runes.yml", "lucky-gem",
                config.getConfigurationSection("lucky-gem"), Material.EMERALD);
        luckyGemShopPrice = Math.max(0D, config.getDouble("lucky-gem.shop-price", 0D));

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

    public List<String> enchantIds() {
        return List.copyOf(definitions.keySet());
    }

    public RuneRollTable rollTable(RuneTier tier) {
        return tier == null ? RuneRollTable.of(List.of()) : rollTables.getOrDefault(tier, RuneRollTable.of(List.of()));
    }

    public double runeShopPrice(RuneTier tier) {
        return tier == null ? 0D : runeShopPrices.getOrDefault(tier, 0D);
    }

    public double luckyGemShopPrice() {
        return luckyGemShopPrice;
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
        pdc.set(enchantItemSuccessKey, PersistentDataType.DOUBLE, levelConfig.successRate());
        item.setItemMeta(meta);
        renderEnchantItem(item);
        return item;
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
        lore.add(RuneFormatting.plain("ᴅʀᴀɢ ᴏɴᴛᴏ ᴄᴏᴍᴘᴀᴛɪʙʟᴇ ᴇǫᴜɪᴘᴍᴇɴᴛ ᴛᴏ ᴀᴘᴘʟʏ", NamedTextColor.DARK_GRAY));

        ItemMeta meta = item.getItemMeta();
        meta.displayName(RuneFormatting.title(definition.displayName(), info.level(),
                RuneFormatting.tierColor(info.originTier()), info.level() == definition.maxLevel()));
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
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            EnchantDefinition definition = definitions.get(entry.getKey());
            if (definition == null) {
                continue;
            }
            if (definition.level(entry.getValue()) == null) {
                continue;
            }
            RuneTier tier = enchantTiers.getOrDefault(entry.getKey(), RuneTier.SIMPLE);
            lore.add(RuneFormatting.appliedLine(definition.displayName(), entry.getValue(),
                    RuneFormatting.tierColor(tier), entry.getValue() == definition.maxLevel()));
        }
        lore.addAll(renderArenaRuneLines(meta));
        meta.lore(lore);
        item.setItemMeta(meta);
    }

    /** Keeps the two rune families visually composable when both are on one item. */
    private List<Component> renderArenaRuneLines(ItemMeta meta) {
        List<Component> lore = new ArrayList<>();
        for (Map.Entry<ArenaRuneManager.Effect, Integer> entry : arenaRunesOf(meta).entrySet()) {
            lore.add(RuneFormatting.appliedLine(entry.getKey().displayName(), entry.getValue(), NamedTextColor.BLUE,
                    entry.getValue() == 20));
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
        for (Map.Entry<ArenaRuneManager.Effect, Integer> entry : arenaRunesOf(meta).entrySet()) {
            String expected = entry.getKey().displayName() + " " + roman(entry.getValue());
            String standardized = RuneFormatting.smallCaps(entry.getKey().displayName()) + " "
                    + RuneFormatting.roman(entry.getValue());
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

    private Map<ArenaRuneManager.Effect, Integer> arenaRunesOf(ItemMeta meta) {
        Map<ArenaRuneManager.Effect, Integer> result = new LinkedHashMap<>();
        String raw = meta.getPersistentDataContainer().get(arenaAppliedKey, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String entry : raw.split(",")) {
            String[] split = entry.split(":", 2);
            if (split.length != 2) {
                continue;
            }
            ArenaRuneManager.Effect effect = ArenaRuneManager.Effect.byId(split[0]);
            try {
                int level = Integer.parseInt(split[1]);
                if (effect != null && level >= 1 && level <= 20) {
                    result.put(effect, level);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed legacy PDC rather than losing valid lore.
            }
        }
        return result;
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
