package me.vertex.core.wand;

import me.vertex.core.lang.MessageFormatter;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads {@code wands.yml} and owns everything about a wand item.
 *
 * <p>Remaining uses live on the item itself rather than in a server-side
 * table, so the count follows the wand through restarts, trades, chests, and
 * enderchests with no bookkeeping that could drift out of sync with it.
 */
public final class WandManager {

    private final Plugin plugin;
    /** Internal tags that do not make an item custom; see hasNoCustomData. */
    private final java.util.Set<NamespacedKey> harmlessMarkers;
    private final NamespacedKey tierKey;
    private final NamespacedKey usesKey;

    private volatile boolean enabled;
    private volatile boolean sellEnabled;
    private volatile boolean tntEnabled;
    private volatile int gunpowderPerTnt;
    private volatile boolean requireSand;
    private volatile int sandPerTnt;
    private volatile Set<Material> neverSell = EnumSet.noneOf(Material.class);
    private volatile Map<String, WandTier> tiers = Map.of();

    public WandManager(Plugin plugin) {
        this.plugin = plugin;
        this.harmlessMarkers = java.util.Set.of(new NamespacedKey(plugin, "mob_drop"));
        this.tierKey = new NamespacedKey(plugin, "wand_tier");
        this.usesKey = new NamespacedKey(plugin, "wand_uses");
    }

    Plugin plugin(){return plugin;}

    public void load() {
        File file = new File(plugin.getDataFolder(), "wands.yml");
        if (!file.exists()) {
            plugin.saveResource("wands.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        sellEnabled = config.getBoolean("sell-wands.enabled", true);
        tntEnabled = config.getBoolean("tnt-wands.enabled", true);
        gunpowderPerTnt = Math.max(1, config.getInt("tnt-wands.gunpowder-per-tnt", 5));
        requireSand = config.getBoolean("tnt-wands.require-sand", false);
        sandPerTnt = Math.max(0, config.getInt("tnt-wands.sand-per-tnt", 4));

        Set<Material> blocked = EnumSet.noneOf(Material.class);
        for (String raw : config.getStringList("sell-wands.never-sell")) {
            Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.getLogger().warning("wands.yml: never-sell lists unknown material '" + raw + "', ignoring it.");
                continue;
            }
            blocked.add(material);
        }
        neverSell = blocked;

        Map<String, WandTier> loaded = new LinkedHashMap<>();
        readTiers(config.getConfigurationSection("sell-wands.tiers"), WandType.SELL, loaded);
        readTiers(config.getConfigurationSection("tnt-wands.tiers"), WandType.TNT, loaded);
        tiers = Map.copyOf(loaded);
    }

    private void readTiers(ConfigurationSection section, WandType type, Map<String, WandTier> into) {
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection tier = section.getConfigurationSection(id);
            if (tier == null) {
                continue;
            }
            Material material = Material.matchMaterial(
                    String.valueOf(tier.getString("material", "STICK")).toUpperCase(Locale.ROOT));
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("wands.yml: wand '" + id + "' has an unknown material, using STICK.");
                material = Material.STICK;
            }
            into.put(id.toLowerCase(Locale.ROOT), new WandTier(
                    id.toLowerCase(Locale.ROOT),
                    type,
                    material,
                    tier.contains("custom-model-data") ? tier.getInt("custom-model-data") : null,
                    tier.getString("name", id),
                    tier.getStringList("lore"),
                    Math.max(1, tier.getInt("uses", 50))));
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEnabled(WandType type) {
        return enabled && (type == WandType.SELL ? sellEnabled : tntEnabled);
    }

    public int gunpowderPerTnt() {
        return gunpowderPerTnt;
    }

    public boolean requireSand() {
        return requireSand;
    }

    public int sandPerTnt() {
        return requireSand ? sandPerTnt : 0;
    }

    public WandTier tier(String id) {
        return id == null ? null : tiers.get(id.toLowerCase(Locale.ROOT));
    }

    public List<String> tierIds() {
        return List.copyOf(tiers.keySet());
    }

    /**
     * Whether a Sell Wand may sell this stack.
     *
     * <p>Anything carrying custom item data is refused outright. A named,
     * enchanted, or model-data item that merely shares a material with a shop
     * entry is not that shop entry -- selling a player's custom gear at the
     * price of a plain one is exactly the accident this prevents. It also
     * means Vertex's own items (wands, backpacks, collectors) can never be
     * swept up by a wand, without needing to list each of them.
     */
    public boolean isSellable(ItemStack item) {
        return isPlainStack(item) && !neverSell.contains(item.getType());
    }

    /**
     * A stack carrying no custom item data at all.
     *
     * <p>A named, enchanted, or model-data item that merely shares a material
     * with a shop entry is not that shop entry -- selling someone's custom
     * gear at the price of a plain one is exactly the accident this prevents.
     * It also means Vertex's own items (wands, Backpacks, Chunk Collectors)
     * can never be swept up by a wand without having to list each of them.
     */
    public boolean isPlainStack(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (!item.hasItemMeta()) {
            return true;
        }
        ItemStack comparable = item.clone();
        ItemMeta meta = comparable.getItemMeta();
        if (!hasNoCustomData(meta)) return false;
        harmlessMarkers.forEach(meta.getPersistentDataContainer()::remove);
        comparable.setItemMeta(meta);
        return me.vertex.core.shop.ShopManager.isPlainStack(comparable);
    }

    /**
     * True when the only attached data is a harmless internal marker.
     *
     * <p>Vertex tags some perfectly ordinary items for its own bookkeeping --
     * a mob drop waiting for a Chunk Collector, for instance. Those are still
     * plain items to a player, and treating any tagged item as custom made
     * wands refuse ordinary loot: a chest of grinder drops sold nothing, and
     * a TNT Wand would not touch creeper gunpowder, which is the main way
     * gunpowder is obtained at all.
     *
     * <p>Deliberately an allowlist of specific markers rather than "anything
     * in Vertex's namespace". Vertex's own items -- wands, Backpacks, Chunk
     * Collectors -- are identified by their own keys, and exempting the whole
     * namespace would make a wand able to sell another wand.
     */
    private boolean hasNoCustomData(ItemMeta meta) {
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            if (!harmlessMarkers.contains(key)) {
                return false;
            }
        }
        return true;
    }

    public ItemStack createWand(WandTier tier) {
        return createWand(tier, tier.uses());
    }

    public ItemStack createWand(WandTier tier, int uses) {
        ItemStack item = new ItemStack(tier.material());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(tierKey, PersistentDataType.STRING, tier.id());
        meta.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, Math.max(0, uses));
        applyDisplay(meta, tier, Math.max(0, uses));
        item.setItemMeta(meta);
        return item;
    }

    private void applyDisplay(ItemMeta meta, WandTier tier, int uses) {
        meta.displayName(MessageFormatter.deserialize(me.vertex.core.lang.SmallCaps.template(tier.name())));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : tier.lore()) {
            lore.add(MessageFormatter.deserialize(me.vertex.core.lang.SmallCaps.template(line).replace("{uses}", String.valueOf(uses))));
        }
        meta.lore(lore);
        if (tier.customModelData() != null) {
            meta.setCustomModelData(tier.customModelData());
        }
    }

    /** @return the wand this item is, or null when it is not one. */
    public WandTier tierOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return tier(item.getItemMeta().getPersistentDataContainer().get(tierKey, PersistentDataType.STRING));
    }

    public int usesLeft(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Integer uses = item.getItemMeta().getPersistentDataContainer().get(usesKey, PersistentDataType.INTEGER);
        return uses == null ? 0 : uses;
    }

    /**
     * Spends one use, rewriting the lore so the count a player reads is
     * always the count the server holds.
     *
     * @return true when the wand is spent and should be removed
     */
    public boolean consumeUse(ItemStack item, WandTier tier) {
        int remaining = Math.max(0, usesLeft(item) - 1);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, remaining);
        applyDisplay(meta, tier, remaining);
        item.setItemMeta(meta);
        return remaining <= 0;
    }
}
