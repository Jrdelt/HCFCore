package me.vertex.core.backpack;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loads {@code backpacks.yml}, creates/reads/writes Backpack items, and
 * owns the level/size/cost math ({@link BackpackProgression}). A Backpack
 * carries its own state entirely in its item's PersistentDataContainer --
 * tier, level, and its stored contents (serialized via
 * {@link ItemStack#serializeItemsAsBytes}, one entry per distinct
 * material with no cap on a single entry's amount) -- since, unlike a
 * Chunk Collector, there's no fixed world location to index it by.
 */
public final class BackpackManager {

    private final Plugin plugin;
    private final Messages messages;
    private final File file;

    private final NamespacedKey markerKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey levelKey;
    /** Removes the retired Backpack XP tag when an existing item is next saved. */
    private final NamespacedKey legacyXpKey;
    /** Removes the legacy expiry PDC tag the next time an old item is saved. */
    private final NamespacedKey legacyExpiryKey;
    private final NamespacedKey contentsKey;
    /** A per-item identity prevents two otherwise-identical empty bags from stacking. */
    private final NamespacedKey instanceKey;

    private volatile boolean enabled;
    private volatile long baseItemCapacity;
    private volatile long itemCapacityPerLevel;
    private volatile boolean autoStoreMobDrops;
    private final Set<Material> autoStoreMiningMaterials = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile double upgradeCostBase;
    private volatile int upgradeCostEasyThroughLevel;
    private volatile double upgradeCostEasyMultiplier;
    private volatile int upgradeCostRampThroughLevel;
    private volatile double upgradeCostMaxMultiplier;
    private final Map<String, BackpackTier> tiers = new LinkedHashMap<>();

    public BackpackManager(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.file = new File(plugin.getDataFolder(), "backpacks.yml");
        this.markerKey = new NamespacedKey(plugin, "backpack");
        this.tierKey = new NamespacedKey(plugin, "backpack_tier");
        this.levelKey = new NamespacedKey(plugin, "backpack_level");
        this.legacyXpKey = new NamespacedKey(plugin, "backpack_xp");
        this.legacyExpiryKey = new NamespacedKey(plugin, "backpack_expires_at");
        this.contentsKey = new NamespacedKey(plugin, "backpack_contents");
        this.instanceKey = new NamespacedKey(plugin, "backpack_instance");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("backpacks.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        baseItemCapacity = Math.max(1L, config.getLong("base-item-capacity", 1250L));
        itemCapacityPerLevel = Math.max(0L, config.getLong("item-capacity-per-level", 0L));
        autoStoreMobDrops = config.getBoolean("auto-store.mob-drops", true);
        autoStoreMiningMaterials.clear();
        for (String name : config.getStringList("auto-store.mining-materials")) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isBlock()) {
                plugin.getLogger().warning("Ignoring invalid Backpack mining material: " + name);
                continue;
            }
            autoStoreMiningMaterials.add(material);
        }
        // The old single growth curve became unaffordable after only a few
        // levels. The nested values deliberately have their own defaults,
        // rather than reading the retired upgrade-cost-base/growth keys, so
        // existing Backpack configs receive the fair 1-50/50+ curve as soon
        // as the updated JAR is installed.
        upgradeCostBase = Math.max(0, config.getDouble("upgrade-cost.base", 500.0));
        upgradeCostEasyThroughLevel = Math.max(1, config.getInt("upgrade-cost.easy-through-level", 50));
        upgradeCostEasyMultiplier = Math.max(1.0, config.getDouble("upgrade-cost.easy-multiplier", 1.10));
        upgradeCostRampThroughLevel = Math.max(upgradeCostEasyThroughLevel,
                config.getInt("upgrade-cost.ramp-through-level", 150));
        upgradeCostMaxMultiplier = Math.max(1.0, config.getDouble("upgrade-cost.max-multiplier", 2.0));

        tiers.clear();
        ConfigurationSection tiersSection = config.getConfigurationSection("tiers");
        if (tiersSection != null) {
            for (String id : tiersSection.getKeys(false)) {
                ConfigurationSection tierSection = tiersSection.getConfigurationSection(id);
                if (tierSection == null) {
                    continue;
                }
                String configuredType = tierSection.getString("item-type", "LEATHER");
                Material itemType = Material.matchMaterial(configuredType == null ? "" : configuredType);
                if (itemType == null || itemType.isAir()) {
                    plugin.getLogger().warning("Ignoring Backpack tier '" + id
                            + "': item-type must be a valid non-air Bukkit material.");
                    continue;
                }
                tiers.put(id.toLowerCase(Locale.ROOT), new BackpackTier(
                        id.toLowerCase(Locale.ROOT),
                        tierSection.getString("display-name", id),
                        itemType,
                        Math.max(0, tierSection.getInt("custom-model-data", 0)),
                        tierSection.getDouble("drop-bonus-base-percent", 0.0),
                        tierSection.getDouble("drop-bonus-per-level-percent", 0.0)));
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    Plugin plugin() {
        return plugin;
    }

    public BackpackTier getTier(String id) {
        return id == null ? null : tiers.get(id.toLowerCase(Locale.ROOT));
    }

    public Set<String> tierIds() {
        return Set.copyOf(tiers.keySet());
    }

    /** Maximum individual items (not occupied slots) that this level may store. */
    public long itemCapacityForLevel(int level) {
        long extraLevels = Math.max(0L, (long) level - 1L);
        if (itemCapacityPerLevel == 0L || extraLevels == 0L) {
            return baseItemCapacity;
        }
        if (extraLevels > (Long.MAX_VALUE - baseItemCapacity) / itemCapacityPerLevel) {
            return Long.MAX_VALUE;
        }
        return baseItemCapacity + extraLevels * itemCapacityPerLevel;
    }

    public boolean autoStoresMining(Material material) {
        return autoStoreMiningMaterials.contains(material);
    }

    public boolean autoStoresMobDrops() {
        return autoStoreMobDrops;
    }

    /** Counts stack amounts: a 64-stack contributes sixty-four stored items. */
    public static long storedItemCount(ItemStack[] contents) {
        long total = 0L;
        if (contents == null) {
            return 0L;
        }
        for (ItemStack item : contents) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if (Long.MAX_VALUE - total < item.getAmount()) {
                return Long.MAX_VALUE;
            }
            total += item.getAmount();
        }
        return total;
    }

    /** No tier-configured level cap: every normal positive level has a next upgrade. */
    public double upgradeCost(BackpackTier tier, int level) {
        return level == Integer.MAX_VALUE ? -1 : BackpackProgression.upgradeCost(level, upgradeCostBase,
                upgradeCostEasyThroughLevel, upgradeCostEasyMultiplier, upgradeCostRampThroughLevel, upgradeCostMaxMultiplier);
    }

    public double dropBonusPercent(BackpackTier tier, int level) {
        return BackpackProgression.dropBonusPercent(tier.dropBonusBasePercent(), tier.dropBonusPerLevelPercent(), level);
    }

    /**
     * The player-visible Backpack name, without any formatting codes. It is
     * deliberately plain because it is substituted into a configurable
     * MiniMessage template alongside other run-time values.
     */
    public String displayName(BackpackTier tier) {
        if (tier == null) {
            return "";
        }
        String name = MessageFormatter.plain(tier.displayName());
        return name.isBlank() ? tierLabel(tier) : name;
    }

    /** A readable form of a tier id such as {@code t2_reinforced -> T2 Reinforced}. */
    public String tierLabel(BackpackTier tier) {
        if (tier == null || tier.id().isBlank()) {
            return "";
        }
        StringBuilder label = new StringBuilder();
        for (String part : tier.id().split("[_-]+")) {
            if (part.isBlank()) {
                continue;
            }
            if (!label.isEmpty()) {
                label.append(' ');
            }
            label.append(part.substring(0, 1).toUpperCase(Locale.ROOT))
                    .append(part.substring(1));
        }
        return label.toString();
    }

    /** A valid, single Backpack equipped in the player's offhand, if any. */
    public EquippedBackpack equippedBackpack(org.bukkit.entity.Player player) {
        if (!enabled) {
            return null;
        }
        ItemStack item = player.getInventory().getItemInOffHand();
        if (!isSingleBackpack(item)) {
            return null;
        }
        BackpackData data = readData(item);
        BackpackTier tier = data == null ? null : getTier(data.tierId());
        return tier == null ? null : new EquippedBackpack(item, tier, data);
    }

    /**
     * Convenience trio for callers outside this package (the PlaceholderAPI
     * expansion) that only need primitives/strings back -- {@link
     * BackpackTier} and {@link BackpackData} are deliberately package-private,
     * so an {@link EquippedBackpack} can only be inspected from in here.
     */
    public String equippedTierDisplayName(org.bukkit.entity.Player player) {
        EquippedBackpack equipped = equippedBackpack(player);
        return equipped == null ? null : displayName(equipped.tier());
    }

    public long equippedStoredCount(org.bukkit.entity.Player player) {
        EquippedBackpack equipped = equippedBackpack(player);
        return equipped == null ? -1 : storedItemCount(equipped.data().contents());
    }

    public long equippedCapacity(org.bukkit.entity.Player player) {
        EquippedBackpack equipped = equippedBackpack(player);
        return equipped == null ? -1 : itemCapacityForLevel(equipped.data().level());
    }

    /**
     * Applies the Backpack's configured drop bonus, stores as much as fits,
     * and returns every item that could not be stored. The caller is
     * responsible for dropping the returned items naturally.
     */
    public List<ItemStack> storeAutoCollected(EquippedBackpack equipped, List<ItemStack> drops) {
        if (equipped == null || drops == null || drops.isEmpty()) {
            return drops == null ? List.of() : List.copyOf(drops);
        }
        BackpackData original = equipped.data();
        // One entry per distinct material -- not a fixed-size slot array,
        // so there is no artificial ceiling below the advertised
        // itemCapacityForLevel() the way a Bukkit-inventory-shaped array
        // (capped at 64 per slot, and at however many slots existed) used
        // to impose. See BackpackData's contents() doc for why.
        List<ItemStack> contents = new java.util.ArrayList<>(java.util.Arrays.asList(original.contents()));
        long remainingCapacity = Math.max(0L, itemCapacityForLevel(original.level()) - storedItemCount(original.contents()));
        long stored = 0L;
        List<ItemStack> leftovers = new java.util.ArrayList<>();

        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) {
                continue;
            }
            long boostedAmount = applyBonus(drop.getAmount(), equipped.tier(), original.level());
            long toStore = Math.min(boostedAmount, remainingCapacity);
            if (toStore > 0) {
                mergeInto(contents, drop, toStore);
                stored += toStore;
                remainingCapacity -= toStore;
            }
            long overflow = boostedAmount - toStore;
            if (overflow > 0) {
                leftovers.addAll(splitIntoRealStacks(drop, overflow));
            }
        }

        if (stored > 0L) {
            // writeData also rebuilds the item lore from the just-updated
            // contents count. The caller replaces the offhand stack and
            // refreshes the inventory so clients see that new count now.
            writeData(equipped.item(), original.withContents(contents.toArray(new ItemStack[0])));
        }
        return leftovers;
    }

    /** The base amount plus its randomized drop-bonus fraction (fractional chance of one extra), as a single combined total. */
    private long applyBonus(int baseAmount, BackpackTier tier, int level) {
        double bonus = Math.max(0D, dropBonusPercent(tier, level));
        long extra = (long) Math.floor(baseAmount * bonus / 100D);
        double fractional = baseAmount * bonus / 100D - extra;
        if (ThreadLocalRandom.current().nextDouble() < fractional) {
            extra++;
        }
        return (long) baseAmount + extra;
    }

    /** Adds {@code amount} to an existing matching entry, or appends a new one -- entries are not capped at vanilla max stack size. */
    private static void mergeInto(List<ItemStack> contents, ItemStack source, long amount) {
        for (ItemStack existing : contents) {
            if (existing != null && !existing.isEmpty() && existing.isSimilar(source)) {
                existing.setAmount((int) Math.min(Integer.MAX_VALUE, existing.getAmount() + amount));
                return;
            }
        }
        ItemStack fresh = source.clone();
        fresh.setAmount((int) Math.min(Integer.MAX_VALUE, amount));
        contents.add(fresh);
    }

    /** Splits a possibly-huge logical amount into real, vanilla-max-stack-sized ItemStacks for handing to a real inventory or the ground. */
    static List<ItemStack> splitIntoRealStacks(ItemStack template, long amount) {
        List<ItemStack> result = new java.util.ArrayList<>();
        int maxStack = Math.max(1, template.getMaxStackSize());
        while (amount > 0) {
            int chunk = (int) Math.min(maxStack, amount);
            ItemStack copy = template.clone();
            copy.setAmount(chunk);
            result.add(copy);
            amount -= chunk;
        }
        return result;
    }

    public boolean isBackpack(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    /** A Backpack must be a single item: each bag represents one inventory. */
    public boolean isSingleBackpack(ItemStack item) {
        return isBackpack(item) && item.getAmount() == 1;
    }

    /**
     * Adds a unique identity to a valid, single legacy Backpack the first
     * time it is opened. New Backpacks receive one in {@link #writeData}.
     */
    public void ensureInstanceId(ItemStack item) {
        if (!isSingleBackpack(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        if (!pdc.has(instanceKey, PersistentDataType.STRING)) {
            pdc.set(instanceKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            item.setItemMeta(meta);
        }
    }

    /** True when both items are the same persisted Backpack instance. */
    public boolean isSameInstance(ItemStack first, ItemStack second) {
        if (!isSingleBackpack(first) || !isSingleBackpack(second)) {
            return false;
        }
        String firstId = first.getItemMeta().getPersistentDataContainer().get(instanceKey, PersistentDataType.STRING);
        String secondId = second.getItemMeta().getPersistentDataContainer().get(instanceKey, PersistentDataType.STRING);
        return firstId != null && firstId.equals(secondId);
    }

    /** Null if {@code item} isn't a Backpack, or its tier no longer exists in config. */
    public BackpackData readData(ItemStack item) {
        if (!isBackpack(item)) {
            return null;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String tierId = pdc.get(tierKey, PersistentDataType.STRING);
        if (tierId == null || getTier(tierId) == null) {
            return null;
        }
        int level = BackpackProgression.clampLevel(pdc.getOrDefault(levelKey, PersistentDataType.INTEGER, 1));
        byte[] contentsBytes = pdc.get(contentsKey, PersistentDataType.BYTE_ARRAY);
        ItemStack[] contents;
        if (contentsBytes == null) {
            contents = new ItemStack[0];
        } else {
            try {
                contents = compact(ItemStack.deserializeItemsFromBytes(contentsBytes));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to read Backpack contents, treating it as empty.", e);
                contents = new ItemStack[0];
            }
        }
        return new BackpackData(tierId, level, contents);
    }

    /** Drops null/empty entries -- one entry per distinct material, no fixed slot count to normalize against. */
    private static ItemStack[] compact(ItemStack[] source) {
        List<ItemStack> kept = new java.util.ArrayList<>(source.length);
        for (ItemStack item : source) {
            if (item != null && !item.isEmpty()) {
                kept.add(item);
            }
        }
        return kept.toArray(new ItemStack[0]);
    }

    /** Persists {@code data} onto {@code item} and refreshes its name/lore to match. */
    public void writeData(ItemStack item, BackpackData data) {
        BackpackTier tier = getTier(data.tierId());
        if (tier == null) {
            return;
        }
        data = sanitizeData(data, tier);
        // Keep an older Backpack (including a pre-migration ItemsAdder
        // item) aligned with the tier's configured vanilla material.
        item.setType(tier.itemType());
        ItemMeta meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        pdc.set(tierKey, PersistentDataType.STRING, data.tierId());
        pdc.set(levelKey, PersistentDataType.INTEGER, data.level());
        pdc.remove(legacyXpKey);
        pdc.remove(legacyExpiryKey);
        pdc.set(contentsKey, PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(data.contents()));
        if (!pdc.has(instanceKey, PersistentDataType.STRING)) {
            pdc.set(instanceKey, PersistentDataType.STRING, UUID.randomUUID().toString());
        }
        if (tier.customModelData() > 0) {
            meta.setCustomModelData(tier.customModelData());
        } else {
            meta.setCustomModelData(null);
        }

        meta.displayName(MessageFormatter.deserialize(tier.displayName()));
        meta.lore(buildLore(tier, data));
        item.setItemMeta(meta);
    }

    /**
     * Clamps the level and drops null/empty content entries -- but
     * never trims stored items down to {@link #itemCapacityForLevel}, even
     * if a config edit lowered it below what a Backpack already legitimately
     * holds. Capacity is only ever enforced going forward, at
     * {@link #storeAutoCollected}'s insertion point: a Backpack sitting
     * over its new cap simply stops accepting more until emptied or
     * upgraded, rather than this plugin silently deleting a player's
     * already-earned items.
     */
    private BackpackData sanitizeData(BackpackData data, BackpackTier tier) {
        int level = BackpackProgression.clampLevel(data.level());
        ItemStack[] contents = compact(data.contents() == null ? new ItemStack[0] : data.contents());
        return new BackpackData(tier.id(), level, contents);
    }

    /** A fresh, empty, level-1 Backpack of {@code tier}. See {@link #createBackpackItem(BackpackTier, int)}. */
    public ItemStack createBackpackItem(BackpackTier tier) {
        return createBackpackItem(tier, 1);
    }

    /**
     * A fresh, empty Backpack of {@code tier} starting at any positive
     * {@code level} (clamped only to 1). The item material and optional
     * custom model data are configured directly in {@code backpacks.yml};
     * no external custom-item plugin is required.
     */
    public ItemStack createBackpackItem(BackpackTier tier, int level) {
        ItemStack item = new ItemStack(tier.itemType());
        int clampedLevel = BackpackProgression.clampLevel(level);
        BackpackData data = new BackpackData(tier.id(), clampedLevel, new ItemStack[0]);
        writeData(item, data);
        return item;
    }

    private List<Component> buildLore(BackpackTier tier, BackpackData data) {
        double bonus = dropBonusPercent(tier, data.level());
        long stored = storedItemCount(data.contents());

        return messages.getList(Bukkit.getConsoleSender(), "backpack.lore",
                "level", String.valueOf(data.level()),
                "bonus", String.format("%.2f", bonus),
                "stored", String.format("%,d", stored),
                "capacity", String.format("%,d", itemCapacityForLevel(data.level())));
    }

    public record EquippedBackpack(ItemStack item, BackpackTier tier, BackpackData data) {
    }

}
