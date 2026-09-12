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
    private volatile int fillBlocksPerTick;
    private volatile boolean kothHologramsEnabled;
    private volatile List<String> kothHologramLines = List.of();
    private final java.util.Deque<FillJob> fillJobs = new java.util.ArrayDeque<>();
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
        fillBlocksPerTick = Math.max(1, config.getInt("regeneration.fill-blocks-per-tick", 4000));

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
                ConfigurationSection kothSection = section.getConfigurationSection("koth");
                MineKothDefinition koth = readKoth(mineId, region, kothSection);
                if (koth != null) {
                    kothDefinitions.put(mineId, koth);
                }
                if (kothSection != null && kothHologramLines.isEmpty()) {
                    kothHologramsEnabled = kothSection.getBoolean("hologram.enabled", true);
                    kothHologramLines = List.copyOf(kothSection.getStringList("hologram.lines"));
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
                Material dropMaterial = MineOreTable.Entry.defaultDropMaterial(material);
                String rawDropMaterial = ores.getString(key + ".drop-material", "");
                if (rawDropMaterial != null && !rawDropMaterial.isBlank()) {
                    Material configuredDrop = Material.matchMaterial(rawDropMaterial.trim().toUpperCase(Locale.ROOT));
                    if (configuredDrop == null || configuredDrop.isAir()) {
                        plugin.getLogger().warning("mines.yml: " + id + " lists invalid drop material '"
                                + rawDropMaterial + "' for " + key + "; using " + dropMaterial + ".");
                    } else {
                        dropMaterial = configuredDrop;
                    }
                }
                entries.add(new MineOreTable.Entry(material,
                        ores.getDouble(key + ".weight", 0D),
                        Math.max(1, ores.getInt(key + ".drop", 1)),
                        dropMaterial));
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

    public boolean kothHologramsEnabled() {
        return kothHologramsEnabled;
    }

    public List<String> kothHologramLines() {
        return kothHologramLines;
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
        meta.displayName(messages.getGui(player, "mines.wand-name"));
        meta.lore(messages.getGuiList(player, "mines.wand-lore"));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, "mine");
        wand.setItemMeta(meta);
        if (!queueOverflow(player, List.of(wand), "mine-selector")) {
            player.sendMessage(messages.get(player, "delivery.storage-unavailable"));
        }
    }

    public boolean isSelectionWand(ItemStack item) {
        return item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.STRING);
    }

    public boolean queueOverflow(Player player, java.util.Collection<ItemStack> items, String source) {
        return me.vertex.core.storage.DeliveryManager.queueOverflow(plugin, player, items, source);
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
        // A newly placed mine is solid base block until something puts ore in
        // it -- regeneration only ever touches blocks a player already mined.
        if (!selection.kothZone) {
            MineRegion placed = region(selection.mineId);
            if (placed != null && beginFill(placed, player.getUniqueId())) {
                player.sendMessage(messages.get(player, "mines.fill-started", "mine", placed.displayName()));
            }
        }
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

    /**
     * Seeds every base block in the region from the ore table.
     *
     * <p>Regeneration alone only ever touches blocks a player has already
     * mined, so a freshly built mine stays solid stone until someone digs it
     * out one block at a time. This is what actually puts ore in the ground
     * when a mine is first placed.
     *
     * <p>Spread over ticks in bounded batches: a selected region can be
     * hundreds of blocks on a side, and setting that many blocks in one tick
     * would stall the server outright.
     *
     * @return false when a fill is already running for this mine
     */
    public boolean beginFill(MineRegion region, UUID initiator) {
        if (!region.isDefined() || region.ores().isEmpty()) {
            return false;
        }
        synchronized (fillJobs) {
            for (FillJob queued : fillJobs) {
                if (queued.mineId.equals(region.id())) {
                    return false;
                }
            }
            FillJob job = new FillJob(region.id(), initiator);
            job.total = (long) (region.maxX() - region.minX() + 1)
                    * (region.maxY() - region.minY() + 1)
                    * (region.maxZ() - region.minZ() + 1);
            fillJobs.add(job);
            showBar(job, initiator);
        }
        return true;
    }

    /**
     * Adds a viewer to a fill already in progress.
     *
     * <p>Re-running the command during a fill shows the bar rather than
     * refusing outright: on a large region a fill runs for tens of minutes,
     * and "already being seeded" with no way to see how far along it is
     * gives an admin no reason to believe it is still working.
     *
     * @return false when that mine is not currently being seeded
     */
    public boolean watchFill(String mineId, UUID viewer) {
        synchronized (fillJobs) {
            for (FillJob job : fillJobs) {
                if (job.mineId.equalsIgnoreCase(mineId)) {
                    job.watchers.add(viewer);
                    showBar(job, viewer);
                    return true;
                }
            }
        }
        return false;
    }

    /** Percent complete for a running fill, or -1 when that mine is not being seeded. */
    public double fillProgressPercent(String mineId) {
        synchronized (fillJobs) {
            for (FillJob job : fillJobs) {
                if (job.mineId.equalsIgnoreCase(mineId)) {
                    return job.fraction() * 100D;
                }
            }
        }
        return -1D;
    }

    private void showBar(FillJob job, UUID viewer) {
        Player player = viewer == null ? null : Bukkit.getPlayer(viewer);
        if (player != null) {
            player.showBossBar(job.bar);
        }
    }

    private void updateBar(FillJob job, MineRegion region) {
        job.bar.progress((float) job.fraction());
        long eta = job.etaSeconds();
        job.bar.name(MessageFormatter.deserialize(messages.getRaw(null, "mines.fill-bar",
                "mine", region == null ? job.mineId : region.displayName(),
                "percent", String.format("%.1f", job.fraction() * 100D),
                "eta", eta < 0 ? "?" : formatEta(eta),
                "placed", String.format("%,d", job.placed))));
    }

    private void hideBar(FillJob job) {
        for (UUID watcher : job.watchers) {
            Player player = Bukkit.getPlayer(watcher);
            if (player != null) {
                player.hideBossBar(job.bar);
            }
        }
    }

    private static String formatEta(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes > 0 ? minutes + "m " + (seconds % 60) + "s" : seconds + "s";
    }

    public boolean isFilling(String mineId) {
        synchronized (fillJobs) {
            for (FillJob job : fillJobs) {
                if (job.mineId.equals(mineId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Advances the current fill by one bounded batch. */
    private void fillTick() {
        FillJob job;
        synchronized (fillJobs) {
            job = fillJobs.peek();
        }
        if (job == null) {
            return;
        }
        MineRegion region = region(job.mineId);
        World world = region == null ? null : Bukkit.getWorld(region.world());
        if (region == null || world == null || !region.isDefined()) {
            finishFill(job, region, false);
            return;
        }

        if (!job.started) {
            job.start(region);
        }
        int budget = fillBlocksPerTick;
        while (budget-- > 0) {
            if (job.y > region.maxY()) {
                finishFill(job, region, true);
                return;
            }
            Block block = world.getBlockAt(job.x, job.y, job.z);
            // Only ever replaces the configured base blocks, so structure,
            // walls, and decoration inside the selection are left alone.
            if (region.baseBlocks().contains(block.getType())) {
                Material rolled = rollBlock(region);
                if (rolled != null && rolled != block.getType()) {
                    block.setType(rolled, false);
                    job.placed++;
                }
            }
            job.scanned++;
            job.advance(region);
        }
        updateBar(job, region);
    }

    private void finishFill(FillJob job, MineRegion region, boolean completed) {
        synchronized (fillJobs) {
            fillJobs.remove(job);
        }
        hideBar(job);
        for (UUID watcher : job.watchers) {
            Player player = Bukkit.getPlayer(watcher);
            if (player == null) {
                continue;
            }
            player.sendMessage(messages.get(player, completed ? "mines.fill-complete" : "mines.fill-aborted",
                    "mine", region == null ? job.mineId : region.displayName(),
                    "placed", String.format("%,d", job.placed)));
        }
    }

    /** A fill walking the region one bounded batch at a time. */
    private static final class FillJob {
        private final String mineId;
        private final UUID initiator;
        /** Everyone watching the progress bar, not just whoever started it. */
        private final java.util.Set<UUID> watchers = new java.util.LinkedHashSet<>();
        private final long startedAtMillis = System.currentTimeMillis();
        private final net.kyori.adventure.bossbar.BossBar bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                net.kyori.adventure.text.Component.empty(), 0f,
                net.kyori.adventure.bossbar.BossBar.Color.BLUE,
                net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
        private int x;
        private int y;
        private int z;
        private long placed;
        private long scanned;
        private long total;
        private boolean started;

        private FillJob(String mineId, UUID initiator) {
            this.mineId = mineId;
            this.initiator = initiator;
            if (initiator != null) {
                watchers.add(initiator);
            }
        }

        private double fraction() {
            return total <= 0 ? 0D : Math.min(1D, scanned / (double) total);
        }

        /** Estimated seconds left, from the rate actually achieved so far. */
        private long etaSeconds() {
            long elapsed = Math.max(1L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
            if (scanned <= 0) {
                return -1L;
            }
            double perSecond = scanned / (double) elapsed;
            return perSecond <= 0D ? -1L : (long) ((total - scanned) / perSecond);
        }

        private void start(MineRegion region) {
            started = true;
            x = region.minX();
            y = region.minY();
            z = region.minZ();
        }

        private void advance(MineRegion region) {
            if (++z > region.maxZ()) {
                z = region.minZ();
                if (++x > region.maxX()) {
                    x = region.minX();
                    y++;
                }
            }
        }
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
        fillTick();
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
