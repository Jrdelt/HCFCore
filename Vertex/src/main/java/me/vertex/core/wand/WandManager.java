package me.vertex.core.wand;

import me.vertex.core.collector.ChunkCollectorManager;
import me.vertex.core.lang.MessageFormatter;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

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

    private final WandDebitWal debitWal;
    /**
     * Locations with a journaled TNT Wand source debit not yet confirmed
     * removed from their container, keyed by location with the version that
     * entry was written at. Cleared -- along with the journal entry -- only
     * once {@link #reconcileChunk} confirms the container either had the
     * owed materials removed just now, or no longer has them (meaning the
     * removal already happened before the crash). See {@link #journalDebit}.
     */
    private final Map<String, PendingWandDebit> pendingDebits = new ConcurrentHashMap<>();
    private final AtomicLong nextDebitVersion = new AtomicLong();
    private record PendingWandDebit(long version, int gunpowder, int sand) {
    }

    public WandManager(Plugin plugin) {
        this.plugin = plugin;
        this.harmlessMarkers = java.util.Set.of(new NamespacedKey(plugin, "mob_drop"));
        this.tierKey = new NamespacedKey(plugin, "wand_tier");
        this.usesKey = new NamespacedKey(plugin, "wand_uses");
        this.debitWal = new WandDebitWal(plugin.getDataFolder());
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

    /**
     * Durably records that {@code location} still owes {@code gunpowder}/
     * {@code sand} to a TNT conversion whose bank credit was just committed
     * to SQL -- and is therefore irrevocable -- before either is actually
     * removed from the container there.
     *
     * @return the version this entry was journaled at; pass it to
     *         {@link #clearDebitIfCurrent} once the removal is confirmed, so
     *         a later withdrawal at the same location can never have its
     *         still-pending entry wrongly cleared by this one.
     */
    long journalDebit(Location location, int gunpowder, int sand) {
        String locationKey = key(location);
        long version = nextDebitVersion.incrementAndGet();
        try {
            debitWal.put(new WandDebitWal.Entry(locationKey, version, gunpowder, sand));
            pendingDebits.put(locationKey, new PendingWandDebit(version, gunpowder, sand));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not journal the TNT Wand source debit at " + locationKey
                    + " before applying it. Its bank credit cannot be undone at this point, so a crash before "
                    + "the source materials are actually removed could create TNT for free.", e);
        }
        return version;
    }

    /** Called once a journaled debit at {@code version} is confirmed reconciled; a no-op if superseded. */
    void clearDebitIfCurrent(Location location, long version) {
        String locationKey = key(location);
        boolean[] current = {false};
        pendingDebits.computeIfPresent(locationKey, (key, pending) -> {
            if (pending.version() != version) {
                return pending;
            }
            current[0] = true;
            return null;
        });
        if (!current[0]) {
            return;
        }
        try {
            debitWal.removeIfVersion(locationKey, version);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not clear the reconciled TNT Wand debit journal entry at "
                    + locationKey + "; it will be harmlessly replayed again on the next restart.", e);
        }
    }

    /**
     * Replays journaled TNT Wand debits from before the last shutdown, and
     * immediately reconciles any location whose chunk is already loaded.
     */
    public void loadDebitJournal(ChunkCollectorManager collectors) {
        List<WandDebitWal.Entry> entries;
        try {
            entries = debitWal.load();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read the TNT Wand debit journal; a source debit "
                    + "from before the last shutdown may not be reconciled.", e);
            return;
        }
        Set<Chunk> loadedChunks = new LinkedHashSet<>();
        for (WandDebitWal.Entry entry : entries) {
            nextDebitVersion.updateAndGet(current -> Math.max(current, entry.version()));
            pendingDebits.put(entry.locationKey(), new PendingWandDebit(entry.version(), entry.gunpowder(), entry.sand()));
            Location location = locationFromKey(entry.locationKey());
            if (location != null && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                loadedChunks.add(location.getWorld().getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4));
            }
        }
        for (Chunk chunk : loadedChunks) {
            reconcileChunk(chunk, collectors);
        }
    }

    /**
     * Forces any journaled TNT Wand debit covering a location in this chunk
     * back onto its live container: removes the owed amount if the
     * container still has it (the crash happened before removal), or leaves
     * it alone if it does not (removal already happened). Either way the
     * journal entry is then cleared -- there is nothing further it can force.
     */
    public void reconcileChunk(Chunk chunk, ChunkCollectorManager collectors) {
        if (pendingDebits.isEmpty()) {
            return;
        }
        for (Map.Entry<String, PendingWandDebit> entry : List.copyOf(pendingDebits.entrySet())) {
            Location location = locationFromKey(entry.getKey());
            if (location == null || !location.getWorld().equals(chunk.getWorld())
                    || (location.getBlockX() >> 4) != chunk.getX() || (location.getBlockZ() >> 4) != chunk.getZ()) {
                continue;
            }
            reconcileLocation(location, entry.getValue(), collectors);
        }
    }

    private void reconcileLocation(Location location, PendingWandDebit pending, ChunkCollectorManager collectors) {
        WandContainer container = WandContainer.of(location.getBlock(), collectors);
        if (container != null) {
            Map<Material, Integer> contents = container.contents(this::isPlainStack);
            int gunpowder = Math.min(pending.gunpowder(), contents.getOrDefault(Material.GUNPOWDER, 0));
            int sand = Math.min(pending.sand(), contents.getOrDefault(Material.SAND, 0));
            if (gunpowder > 0) {
                container.remove(Material.GUNPOWDER, gunpowder, this::isPlainStack);
            }
            if (sand > 0) {
                container.remove(Material.SAND, sand, this::isPlainStack);
            }
            if (gunpowder > 0 || sand > 0) {
                container.commit();
            }
        }
        clearDebitIfCurrent(location, pending.version());
    }

    private static String key(Location location) {
        return location.getWorld().getName() + ":" + location.getBlockX() + ":" + location.getBlockY()
                + ":" + location.getBlockZ();
    }

    private Location locationFromKey(String locationKey) {
        String[] parts = locationKey.split(":", 4);
        World world = plugin.getServer().getWorld(parts[0]);
        if (world == null) {
            return null;
        }
        return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }
}
