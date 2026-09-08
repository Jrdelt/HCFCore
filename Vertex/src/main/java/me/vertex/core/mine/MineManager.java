package me.vertex.core.mine;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Owns {@code mines.yml}: the configured mining regions, the blaze-rod
 * selection that places them, and the worker that regenerates mined blocks.
 *
 * <p>A mine ships fully configured but with no world. An admin selects its
 * two corners in-game and the world and bounds are written into the file
 * here, so coordinates are never typed by hand -- the same flow as
 * {@code /koth create}.
 */
public final class MineManager {

    private final Plugin plugin;
    private final Messages messages;
    private final NamespacedKey wandKey;
    private final File file;

    private final Map<String, MineRegion> regions = new LinkedHashMap<>();
    private final Map<String, MineKothDefinition> kothDefinitions = new LinkedHashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final MineRegenQueue regenQueue = new MineRegenQueue();

    private YamlConfiguration config;
    private volatile boolean enabled;
    private volatile boolean denyBlockPlace;
    private volatile boolean denyNonMineBreak;
    private volatile long maximumSelectionVolume;
    private volatile long workerIntervalTicks;
    private volatile int maxRegenPerPass;
    private volatile long kothTickIntervalTicks;
    private BukkitTask regenTask;
    private volatile HotZoneManager hotZones;

    public MineManager(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.wandKey = new NamespacedKey(plugin, "mine_wand");
        this.file = new File(plugin.getDataFolder(), "mines.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("mines.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        denyBlockPlace = config.getBoolean("protection.deny-block-place", true);
        denyNonMineBreak = config.getBoolean("protection.deny-non-mine-block-break", true);
        maximumSelectionVolume = Math.max(1L, config.getLong("selection.maximum-volume", 60_000_000L));
        workerIntervalTicks = Math.max(1L, config.getLong("regeneration.worker-interval-ticks", 5L));
        maxRegenPerPass = Math.max(1, config.getInt("regeneration.max-per-pass", 100));
        kothTickIntervalTicks = Math.max(1L, config.getLong("koth.tick-interval-ticks", 20L));

        regions.clear();
        kothDefinitions.clear();
        ConfigurationSection mines = config.getConfigurationSection("mines");
        if (mines != null) {
            for (String id : mines.getKeys(false)) {
                ConfigurationSection section = mines.getConfigurationSection(id);
                if (section == null) {
                    continue;
                }
                String mineId = id.toLowerCase(Locale.ROOT);
                MineRegion region = readRegion(mineId, section);
                if (region != null) {
                    regions.put(region.id(), region);
                }
                MineKothDefinition koth = readKoth(mineId, region, section.getConfigurationSection("koth"));
                if (koth != null) {
                    kothDefinitions.put(mineId, koth);
                }
            }
        }
        restartWorker();
    }

    private MineRegion readRegion(String id, ConfigurationSection section) {
        List<MineOreTable.Entry> entries = new ArrayList<>();
        ConfigurationSection ores = section.getConfigurationSection("ores");
        if (ores != null) {
            for (String key : ores.getKeys(false)) {
                Material material = Material.matchMaterial(key.trim().toUpperCase(Locale.ROOT));
                if (material == null || material.isAir()) {
                    plugin.getLogger().warning("mines.yml: " + id + " lists unknown ore '" + key + "', ignoring it.");
                    continue;
                }
                entries.add(new MineOreTable.Entry(material,
                        ores.getDouble(key + ".weight", 0D),
                        Math.max(1, ores.getInt(key + ".drop", 1))));
            }
        }

        Set<Material> baseBlocks = EnumSet.noneOf(Material.class);
        for (String raw : section.getStringList("base-blocks")) {
            Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                plugin.getLogger().warning("mines.yml: " + id + " lists unknown base block '" + raw + "'.");
                continue;
            }
            baseBlocks.add(material);
        }

        Material icon = Material.matchMaterial(
                String.valueOf(section.getString("icon", "STONE")).toUpperCase(Locale.ROOT));
        MineRegion.PvpMode pvp = "enabled".equalsIgnoreCase(section.getString("pvp", "koth-zone-only"))
                ? MineRegion.PvpMode.ENABLED : MineRegion.PvpMode.KOTH_ZONE_ONLY;

        return new MineRegion(id,
                section.getString("display-name", id),
                section.getString("world", ""),
                section.getInt("minimum.x"), section.getInt("minimum.y"), section.getInt("minimum.z"),
                section.getInt("maximum.x"), section.getInt("maximum.y"), section.getInt("maximum.z"),
                pvp,
                icon == null ? Material.STONE : icon,
                Math.max(1, section.getInt("regen-delay-seconds", 30)),
                baseBlocks,
                MineOreTable.of(entries));
    }

    private MineKothDefinition readKoth(String mineId, MineRegion region, ConfigurationSection section) {
        if (section == null || region == null) {
            return null;
        }
        List<MineKothBooster.Tier> tiers = new ArrayList<>();
        for (Map<?, ?> raw : section.getMapList("booster-tiers")) {
            Object after = raw.get("after-seconds");
            Object percent = raw.get("percent");
            if (after instanceof Number afterSeconds && percent instanceof Number bonus) {
                tiers.add(new MineKothBooster.Tier(afterSeconds.longValue(), bonus.doubleValue()));
            } else {
                plugin.getLogger().warning("mines.yml: " + mineId + " has a malformed KOTH booster tier, ignoring it.");
            }
        }
        MineKothControl.Settings settings = new MineKothControl.Settings(
                Math.max(1D, section.getDouble("capture-seconds", 180D)),
                section.getBoolean("multiple-members-speed-up", true),
                Math.max(0D, section.getDouble("additional-member-speed", 0.25D)),
                Math.max(1, section.getInt("max-counted-members", 5)),
                Math.max(1D, section.getDouble("max-speed-multiplier", 2.0D)),
                Math.max(0D, Math.min(100D, section.getDouble("booster-reset-threshold", 50D))));

        return new MineKothDefinition(mineId,
                section.getBoolean("enabled", true),
                region.world(),
                section.getInt("minimum.x"), section.getInt("minimum.y"), section.getInt("minimum.z"),
                section.getInt("maximum.x"), section.getInt("maximum.y"), section.getInt("maximum.z"),
                settings,
                MineKothBooster.of(tiers));
    }

    public List<MineKothDefinition> kothDefinitions() {
        return List.copyOf(kothDefinitions.values());
    }

    public MineKothDefinition kothDefinition(String mineId) {
        return mineId == null ? null : kothDefinitions.get(mineId.toLowerCase(Locale.ROOT));
    }

    public long kothTickIntervalTicks() {
        return kothTickIntervalTicks;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean denyBlockPlace() {
        return denyBlockPlace;
    }

    public boolean denyNonMineBreak() {
        return denyNonMineBreak;
    }

    public List<MineRegion> regions() {
        return List.copyOf(regions.values());
    }

    public MineRegion region(String id) {
        return id == null ? null : regions.get(id.toLowerCase(Locale.ROOT));
    }

    /** The mine containing this location, or null when it is outside every one. */
    public MineRegion regionAt(Location location) {
        if (!enabled || location == null) {
            return null;
        }
        for (MineRegion region : regions.values()) {
            if (region.contains(location)) {
                return region;
            }
        }
        return null;
    }

    // ---- Selection ----

    public boolean beginSelection(Player player, String mineId) {
        return beginSelection(player, mineId, false);
    }

    public boolean beginSelection(Player player, String mineId, boolean kothZone) {
        MineRegion region = region(mineId);
        if (region == null) {
            player.sendMessage(messages.get(player, "mines.unknown",
                    "mines", String.join(", ", regions.keySet())));
            return false;
        }
        Selection selection = selections.computeIfAbsent(player.getUniqueId(), ignored -> new Selection());
        selection.mineId = region.id();
        selection.kothZone = kothZone;
        selection.first = null;
        selection.second = null;
        giveWand(player);
        player.sendMessage(messages.get(player,
                kothZone ? "mines.koth-selection-started" : "mines.selection-started", "mine", region.id()));
        return true;
    }

    public boolean cancelSelection(Player player) {
        return selections.remove(player.getUniqueId()) != null;
    }

    public void giveWand(Player player) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(MessageFormatter.deserialize(messages.getRaw(player, "mines.wand-name")));
        meta.lore(messages.getList(player, "mines.wand-lore"));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, "mine");
        wand.setItemMeta(meta);
        player.getInventory().addItem(wand).values()
                .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    public boolean isSelectionWand(ItemStack item) {
        return item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.STRING);
    }

    public void setCorner(Player player, Location location, boolean first) {
        Selection selection = selections.computeIfAbsent(player.getUniqueId(), ignored -> new Selection());
        if (first) {
            selection.first = location;
        } else {
            selection.second = location;
        }
        player.sendMessage(messages.get(player, first ? "mines.selection-first" : "mines.selection-second",
                "x", String.valueOf(location.getBlockX()),
                "y", String.valueOf(location.getBlockY()),
                "z", String.valueOf(location.getBlockZ())));
    }

    /**
     * Writes the selected world and corners into {@code mines.yml}.
     *
     * <p>The world is taken from where the admin was standing rather than
     * being declared up front, so a mine is placed entirely in-game and the
     * config can never name a world the selection does not match.
     */
    public void completeSelection(Player player) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.mineId == null || selection.first == null || selection.second == null) {
            player.sendMessage(messages.get(player, "mines.selection-incomplete"));
            return;
        }
        if (!selection.first.getWorld().equals(selection.second.getWorld())) {
            player.sendMessage(messages.get(player, "mines.selection-world-mismatch"));
            return;
        }
        int minX = Math.min(selection.first.getBlockX(), selection.second.getBlockX());
        int minY = Math.min(selection.first.getBlockY(), selection.second.getBlockY());
        int minZ = Math.min(selection.first.getBlockZ(), selection.second.getBlockZ());
        int maxX = Math.max(selection.first.getBlockX(), selection.second.getBlockX());
        int maxY = Math.max(selection.first.getBlockY(), selection.second.getBlockY());
        int maxZ = Math.max(selection.first.getBlockZ(), selection.second.getBlockZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > maximumSelectionVolume) {
            player.sendMessage(messages.get(player, "mines.selection-too-large",
                    "maximum", String.format("%,d", maximumSelectionVolume)));
            return;
        }

        String path = "mines." + selection.mineId + (selection.kothZone ? ".koth" : "");
        if (!selection.kothZone) {
            config.set(path + ".world", selection.first.getWorld().getName());
        }
        config.set(path + ".minimum.x", minX);
        config.set(path + ".minimum.y", minY);
        config.set(path + ".minimum.z", minZ);
        config.set(path + ".maximum.x", maxX);
        config.set(path + ".maximum.y", maxY);
        config.set(path + ".maximum.z", maxZ);
        try {
            config.save(file);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save mines.yml", e);
            player.sendMessage(messages.get(player, "mines.save-failed"));
            return;
        }

        selections.remove(player.getUniqueId());
        load();
        player.sendMessage(messages.get(player, selection.kothZone ? "mines.koth-created" : "mines.created",
                "mine", selection.mineId,
                "world", selection.first.getWorld().getName(),
                "volume", String.format("%,d", volume)));
    }

    // ---- Generation and regeneration ----

    /**
     * Rolls this mine's table for whatever should appear at a regenerated
     * spot, using the Hot Zone's reweighted table while one is running.
     */
    public Material rollBlock(MineRegion region) {
        MineOreTable table = hotZones == null ? region.ores() : hotZones.tableFor(region);
        return table.pick(ThreadLocalRandom.current().nextDouble());
    }

    /** Wired after construction because the Hot Zone manager reads mines back. */
    public void setHotZones(HotZoneManager hotZones) {
        this.hotZones = hotZones;
    }

    /** Queues a mined location, and clears it to a base block in the meantime. */
    public void scheduleRegen(MineRegion region, Block block) {
        Material base = region.baseBlocks().stream().findFirst().orElse(Material.STONE);
        block.setType(base, false);
        regenQueue.schedule(
                new MineRegenQueue.Key(region.world(), block.getX(), block.getY(), block.getZ()),
                System.currentTimeMillis() + region.regenDelaySeconds() * 1000L);
    }

    public int queuedRegenerations() {
        return regenQueue.size();
    }

    private void restartWorker() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
        if (!enabled) {
            return;
        }
        regenTask = Bukkit.getScheduler().runTaskTimer(plugin, this::regenTick,
                workerIntervalTicks, workerIntervalTicks);
    }

    private void regenTick() {
        for (MineRegenQueue.Key key : regenQueue.drainDue(System.currentTimeMillis(), maxRegenPerPass)) {
            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                continue;
            }
            Location location = new Location(world, key.x(), key.y(), key.z());
            MineRegion region = regionAt(location);
            // Re-validated on the way out: the region may have been moved or
            // the area rebuilt while this sat in the queue, and regenerating
            // ore into someone's structure is worse than skipping it.
            if (region == null || !region.baseBlocks().contains(location.getBlock().getType())) {
                continue;
            }
            Material rolled = rollBlock(region);
            if (rolled != null) {
                location.getBlock().setType(rolled, false);
            }
        }
    }

    public void shutdown() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
        regenQueue.clear();
    }

    private static final class Selection {
        private String mineId;
        private boolean kothZone;
        private Location first;
        private Location second;
    }
}
