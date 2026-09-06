package me.vertex.core.blueprint;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.NBTInputStream;
import com.sk89q.jnbt.NBTOutputStream;
import com.sk89q.jnbt.NamedTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import me.vertex.core.factions.FactionsHook;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Owns blueprint config (templates, timing), the active-build registry
 * (rebuilt from {@link BlueprintStorage} on startup), per-player cooldowns,
 * and everything that talks to FAWE's clipboard API directly -- the rest
 * of the package only ever sees a flattened {@link ActiveBuild.PendingBlock}
 * list, never WorldEdit types, keeping that dependency contained to this
 * one class.
 */
public final class BlueprintManager {

    private final Plugin plugin;
    private final BlueprintStorage storage;
    private final File file;
    private final Map<String, BlueprintTemplate> templates = new ConcurrentHashMap<>();
    /**
     * Parsed schematics are immutable, so retain their bounds and flattened blocks
     * across batches.
     */
    private final Map<String, CachedTemplate> parsedTemplates = new ConcurrentHashMap<>();
    private final Map<Integer, ActiveBuild> activeBuilds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /**
     * Avoid a console flood when an old schematic has thousands of one retired
     * block type.
     */
    private final Set<String> warnedLegacyBlockIds = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private final Map<Integer, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile String schematicsFolder;
    private volatile int cooldownSeconds;
    private volatile int buildTimeSeconds;
    private volatile int batchIntervalTicks;
    private volatile int maxBlocksPerTick;
    private volatile int claimRecheckIntervalTicks;
    private volatile long maxSchematicBytes;
    private volatile int maxSchematicBlocks;
    private volatile int maxSchematicDimension;

    public BlueprintManager(Plugin plugin, BlueprintStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
        this.file = new File(plugin.getDataFolder(), "blueprints.yml");
    }

    public void load() {
        if (!file.exists()) {
            plugin.saveResource("blueprints.yml", false);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        enabled = config.getBoolean("enabled", true);
        schematicsFolder = config.getString("schematics-folder", "schematics");
        File schematicDirectory = new File(plugin.getDataFolder(), schematicsFolder);
        if (!schematicDirectory.exists() && !schematicDirectory.mkdirs()) {
            plugin.getLogger().warning("Could not create Blueprint schematics folder: " + schematicDirectory);
        } else if (schematicDirectory.exists() && !schematicDirectory.isDirectory()) {
            plugin.getLogger().warning("Blueprint schematics path is not a folder: " + schematicDirectory);
        }
        cooldownSeconds = Math.max(0, config.getInt("cooldown-seconds", 3600));
        buildTimeSeconds = Math.max(1, config.getInt("build-time-seconds", 600));
        batchIntervalTicks = Math.max(1, config.getInt("batch-interval-ticks", 20));
        maxBlocksPerTick = Math.max(1, config.getInt("max-blocks-per-tick", 64));
        claimRecheckIntervalTicks = Math.max(20, config.getInt("claim-recheck-interval-ticks", 100));
        maxSchematicBytes = Math.max(1_048_576L, Math.min(536_870_912L,
                config.getLong("max-schematic-bytes", 33_554_432L)));
        maxSchematicBlocks = Math.max(1, Math.min(1_000_000,
                config.getInt("max-schematic-blocks", 250_000)));
        maxSchematicDimension = Math.max(1, Math.min(4_096,
                config.getInt("max-schematic-dimension", 512)));

        Map<String, BlueprintTemplate> loaded = new LinkedHashMap<>();
        ConfigurationSection templatesSection = config.getConfigurationSection("templates");
        if (templatesSection != null) {
            for (String name : templatesSection.getKeys(false)) {
                ConfigurationSection section = templatesSection.getConfigurationSection(name);
                if (section == null) {
                    continue;
                }
                String schematic = section.getString("schematic");
                if (schematic == null) {
                    plugin.getLogger().warning("Blueprint template '" + name + "' has no 'schematic' entry, skipping.");
                    continue;
                }
                String displayName = section.getString("display-name", name);

                Integer templateBuildTime = section.contains("build-time-seconds")
                        ? Math.max(1, section.getInt("build-time-seconds"))
                        : null;
                Integer templateMaxBpt = section.contains("max-blocks-per-tick")
                        ? Math.max(1, section.getInt("max-blocks-per-tick"))
                        : null;

                loaded.put(name.toLowerCase(Locale.ROOT), new BlueprintTemplate(
                        name,
                        schematic,
                        displayName,
                        templateBuildTime,
                        templateMaxBpt));
            }
        }
        templates.clear();
        templates.putAll(loaded);
        parsedTemplates.clear();
        cooldowns.clear();
        try {
            long now = System.currentTimeMillis();
            storage.loadCooldowns().forEach((uuid, availableAt) -> {
                if (availableAt > now) {
                    cooldowns.put(uuid, availableAt);
                } else {
                    persistCooldownRemoval(uuid);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load Blueprint cooldowns from the database.", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isOnCooldown(UUID uuid) {
        Long availableAt = cooldowns.get(uuid);
        return availableAt != null && availableAt > System.currentTimeMillis();
    }

    public long remainingCooldownMillis(UUID uuid) {
        Long availableAt = cooldowns.get(uuid);
        return availableAt == null ? 0 : Math.max(0, availableAt - System.currentTimeMillis());
    }

    public void startCooldown(UUID uuid) {
        long availableAt = System.currentTimeMillis() + cooldownSeconds * 1000L;
        cooldowns.put(uuid, availableAt);
        queueCooldownWrite(uuid, () -> storage.saveCooldown(uuid, availableAt),
                "Failed to persist Blueprint cooldown.");
    }

    /**
     * Removes a player's active Blueprint placement cooldown.
     *
     * @return true when the player had an active cooldown to remove
     */
    public boolean clearCooldown(UUID uuid) {
        boolean wasActive = cooldowns.remove(uuid) != null;
        queueCooldownWrite(uuid, () -> storage.deleteCooldown(uuid),
                "Failed to remove Blueprint cooldown.");
        return wasActive;
    }

    public int buildTimeSeconds() {
        return buildTimeSeconds;
    }

    public int batchIntervalTicks() {
        return batchIntervalTicks;
    }

    /**
     * Determines how many server ticks to wait between block batches.
     * If there are fewer blocks than available ticks, placement is throttled.
     */
    public int batchInterval(BlueprintTemplate template, int totalBlocks) {
        if (totalBlocks <= 0) {
            return 1;
        }
        int targetBuildTime = (template != null && template.buildTimeSeconds() != null)
                ? template.buildTimeSeconds()
                : buildTimeSeconds;

        long totalTicks = Math.max(1L, targetBuildTime * 20L);
        if (totalBlocks >= totalTicks) {
            return 1; // More blocks than ticks: place every tick
        }
        // Fewer blocks than ticks: spread them out evenly
        return (int) Math.max(1L, totalTicks / totalBlocks);
    }

    /** Hard safety cap for a single blueprint's work during one server tick. */
    public int maxBlocksPerTick() {
        return maxBlocksPerTick;
    }

    /**
     * Work rate resolved using global defaults.
     */
    public int blocksPerTick(int totalBlocks) {
        return blocksPerTick(null, totalBlocks);
    }

    /**
     * Work rate shared by the live builder and the remaining-time display.
     * Evaluates per-template configuration first, falling back to global defaults.
     */
    public int blocksPerTick(BlueprintTemplate template, int totalBlocks) {
        if (totalBlocks <= 0) {
            return 0;
        }

        int targetBuildTime = (template != null && template.buildTimeSeconds() != null)
                ? template.buildTimeSeconds()
                : buildTimeSeconds;

        int targetMaxBpt = (template != null && template.maxBlocksPerTick() != null)
                ? template.maxBlocksPerTick()
                : maxBlocksPerTick;

        long totalTicks = Math.max(1L, targetBuildTime * 20L);
        int calculated = (int) Math.ceil((double) totalBlocks / totalTicks);
        return Math.max(1, Math.min(targetMaxBpt, calculated));
    }

    /** Estimated wall-clock seconds based on the actual throttled build rate. */
    public long remainingBuildSeconds(ActiveBuild build) {
        int total = build.blocks() == null ? 0 : build.blocks().size();
        int remaining = Math.max(0, total - build.currentIndex());
        if (remaining == 0) {
            return 0;
        }

        int bpt = blocksPerTick(build.template(), total);
        if (bpt <= 0) {
            return 0;
        }

        int interval = batchInterval(build.template(), total);
        long batchesRemaining = (remaining + (long) bpt - 1L) / bpt;
        long totalTicksRemaining = batchesRemaining * interval;

        return (totalTicksRemaining + 19L) / 20L;
    }

    public int claimRecheckIntervalTicks() {
        return claimRecheckIntervalTicks;
    }

    public BlueprintTemplate getTemplate(String name) {
        if (name == null) {
            return null;
        }
        return templates.get(name.toLowerCase(Locale.ROOT));
    }

    /** Template names available to command completion and administration. */
    public List<String> getTemplateNames() {
        return templates.values().stream().map(BlueprintTemplate::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public Map<Integer, ActiveBuild> activeBuilds() {
        return activeBuilds;
    }

    public ActiveBuild activeBuildAt(Location beaconLocation) {
        for (ActiveBuild build : activeBuilds.values()) {
            if (sameBlock(build.anchor(), beaconLocation)) {
                return build;
            }
        }
        return null;
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && b.getWorld() != null
                && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private File schematicFile(BlueprintTemplate template) {
        File configured = new File(template.schematicFile());
        return configured.isAbsolute() ? configured : new File(new File(plugin.getDataFolder(), schematicsFolder),
                template.schematicFile());
    }

    private File snapshotFile(int buildId) {
        return new File(new File(plugin.getDataFolder(), "blueprint-snapshots"), buildId + ".schem");
    }

    /** Takes an immutable copy so editing a configured .schem cannot change an active build after a restart. */
    public void createSnapshot(int buildId, BlueprintTemplate template) throws IOException {
        File snapshot = snapshotFile(buildId);
        Files.createDirectories(snapshot.getParentFile().toPath());
        Files.copy(schematicFile(template).toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public boolean hasSnapshot(int buildId) {
        return snapshotFile(buildId).isFile();
    }

    public List<ActiveBuild.PendingBlock> flattenSnapshot(int buildId, BlueprintTemplate template) throws IOException {
        return parsed(snapshotTemplate(buildId, template)).blocks();
    }

    public BlockVector3[] relativeBoundsSnapshot(int buildId, BlueprintTemplate template) throws IOException {
        CachedTemplate cached = parsed(snapshotTemplate(buildId, template));
        return new BlockVector3[] { cached.relativeMin(), cached.relativeMax() };
    }

    public void deleteSnapshot(int buildId) {
        if (buildId < 0) {
            return;
        }
        try {
            Files.deleteIfExists(snapshotFile(buildId).toPath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to remove Blueprint snapshot for build " + buildId, e);
        }
    }

    private BlueprintTemplate snapshotTemplate(int buildId, BlueprintTemplate template) {
        return new BlueprintTemplate(template.name() + "__snapshot_" + buildId,
                snapshotFile(buildId).getAbsolutePath(), template.displayName(), template.buildTimeSeconds(),
                template.maxBlocksPerTick());
    }

    /**
     * Loads and flattens a template's .schem into placeable
     * (relative-offset, BlockData) pairs, skipping air so an already-built
     * area isn't overwritten with holes.
     */
    public List<ActiveBuild.PendingBlock> flatten(BlueprintTemplate template) throws IOException {
        return parsed(template).blocks();
    }

    /** The schematic's own bounding box, relative to its origin. */
    public BlockVector3[] relativeBounds(BlueprintTemplate template) throws IOException {
        CachedTemplate cached = parsed(template);
        return new BlockVector3[] { cached.relativeMin(), cached.relativeMax() };
    }

    private CachedTemplate parsed(BlueprintTemplate template) throws IOException {
        CachedTemplate alreadyLoaded = parsedTemplates.get(template.name().toLowerCase(Locale.ROOT));
        if (alreadyLoaded != null) {
            return alreadyLoaded;
        }

        File schemFile = schematicFile(template);
        if (!schemFile.isFile() || schemFile.length() > maxSchematicBytes) {
            throw new IOException("Schematic is missing or exceeds max-schematic-bytes (" + maxSchematicBytes
                    + "): " + schemFile);
        }
        ClipboardFormat format = ClipboardFormats.findByFile(schemFile);
        if (format == null) {
            throw new IOException("Unrecognized or missing schematic file: " + schemFile);
        }
        Clipboard clipboard;
        byte[] normalizedSchematic = normalizeLegacyPalette(template, schemFile);
        try (InputStream input = normalizedSchematic == null ? new FileInputStream(schemFile)
                : new ByteArrayInputStream(normalizedSchematic);
                ClipboardReader reader = format.getReader(input)) {
            clipboard = reader.read();
        }

        record RawBlock(int x, int y, int z, BlockData data) {
        }
        List<RawBlock> rawBlocks = new ArrayList<>();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockVector3 pos : clipboard.getRegion()) {
            BaseBlock block = clipboard.getFullBlock(pos);
            if (block.getBlockType().getMaterial().isAir()) {
                continue;
            }

            int x = pos.x();
            int y = pos.y();
            int z = pos.z();

            if (rawBlocks.size() >= maxSchematicBlocks) {
                throw new IOException("Schematic '" + template.name() + "' exceeds max-schematic-blocks ("
                        + maxSchematicBlocks + ").");
            }
            rawBlocks.add(new RawBlock(x, y, z, adaptBlockData(template, block)));

            if (x < minX)
                minX = x;
            if (y < minY)
                minY = y;
            if (z < minZ)
                minZ = z;
            if (x > maxX)
                maxX = x;
            if (y > maxY)
                maxY = y;
            if (z > maxZ)
                maxZ = z;
        }

        if (rawBlocks.isEmpty()) {
            CachedTemplate empty = new CachedTemplate(List.of(), BlockVector3.ZERO, BlockVector3.ZERO);
            parsedTemplates.put(template.name().toLowerCase(Locale.ROOT), empty);
            return empty;
        }

        long width = (long) maxX - minX + 1L;
        long height = (long) maxY - minY + 1L;
        long depth = (long) maxZ - minZ + 1L;
        if (width > maxSchematicDimension || height > maxSchematicDimension || depth > maxSchematicDimension) {
            throw new IOException("Schematic '" + template.name() + "' exceeds max-schematic-dimension ("
                    + maxSchematicDimension + ").");
        }

        // Anchor the schematic's bottom corner directly above the beacon
        int originX = minX;
        int originY = minY;
        int originZ = minZ;

        List<ActiveBuild.PendingBlock> blocks = new ArrayList<>(rawBlocks.size());
        for (RawBlock raw : rawBlocks) {
            int offsetX = raw.x() - originX;
            int offsetY = (raw.y() - originY) + 1; // +1 elevates the structure so the beacon is not overwritten
            int offsetZ = raw.z() - originZ;

            blocks.add(new ActiveBuild.PendingBlock(
                    BlockVector3.at(offsetX, offsetY, offsetZ),
                    raw.data()));
        }

        // Build bottom-to-top, layer-by-layer
        blocks.sort(Comparator
                .comparingInt((ActiveBuild.PendingBlock b) -> b.relativeOffset().getBlockY())
                .thenComparingInt(b -> b.relativeOffset().getBlockZ())
                .thenComparingInt(b -> b.relativeOffset().getBlockX()));

        BlockVector3 relMin = BlockVector3.at(0, 1, 0);
        BlockVector3 relMax = BlockVector3.at(maxX - minX, (maxY - minY) + 1, maxZ - minZ);

        CachedTemplate parsed = new CachedTemplate(List.copyOf(blocks), relMin, relMax);
        parsedTemplates.put(template.name().toLowerCase(Locale.ROOT), parsed);
        return parsed;
    }

    private BlockData adaptBlockData(BlueprintTemplate template, BaseBlock block) {
        String id = block.getBlockType().id();
        Material fallback = legacyMaterial(id);
        if (fallback == null) {
            return BukkitAdapter.adapt(block);
        }
        if (warnedLegacyBlockIds.add(id)) {
            plugin.getLogger().warning("Blueprint template '" + template.name() + "' uses retired block id '"
                    + id + "'. Replacing it with " + fallback + "; re-export the .schem with current FAWE "
                    + "to retain its exact block state.");
        }
        return fallback.createBlockData();
    }

    private static Material legacyMaterial(String id) {
        return switch (id) {
            case "minecraft:bed" -> Material.RED_BED;
            case "minecraft:sign", "minecraft:standing_sign" -> Material.OAK_SIGN;
            case "minecraft:wall_sign" -> Material.OAK_WALL_SIGN;
            case "minecraft:skull" -> Material.PLAYER_HEAD;
            case "minecraft:wall_skull" -> Material.PLAYER_WALL_HEAD;
            case "minecraft:banner", "minecraft:standing_banner" -> Material.WHITE_BANNER;
            case "minecraft:wall_banner" -> Material.WHITE_WALL_BANNER;
            default -> null;
        };
    }

    private byte[] normalizeLegacyPalette(BlueprintTemplate template, File schematic) throws IOException {
        NamedTag named;
        try (InputStream fileInput = new FileInputStream(schematic);
                GZIPInputStream gzipInput = new GZIPInputStream(fileInput);
                NBTInputStream nbtInput = new NBTInputStream(gzipInput)) {
            named = nbtInput.readNamedTag();
        } catch (java.util.zip.ZipException ignored) {
            return null;
        }
        if (!(named.getTag() instanceof CompoundTag root)) {
            return null;
        }

        Map<String, Tag<?, ?>> rootValues = new LinkedHashMap<>(root.getValue());
        Set<String> replaced = ConcurrentHashMap.newKeySet();
        boolean changed = normalizePalette(rootValues, "Palette", replaced);
        Tag<?, ?> blocks = rootValues.get("Blocks");
        if (blocks instanceof CompoundTag blocksCompound) {
            Map<String, Tag<?, ?>> blockValues = new LinkedHashMap<>(blocksCompound.getValue());
            if (normalizePalette(blockValues, "Palette", replaced)) {
                rootValues.put("Blocks", new CompoundTag(blockValues));
                changed = true;
            }
        }
        if (!changed) {
            return null;
        }

        for (String id : replaced) {
            if (warnedLegacyBlockIds.add(id)) {
                plugin.getLogger().warning("Blueprint template '" + template.name() + "' uses retired palette key '"
                        + id + "'. It was converted in memory to a valid modern block; re-export the .schem "
                        + "with current FAWE to retain its exact original variant.");
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutput = new GZIPOutputStream(bytes);
                NBTOutputStream nbtOutput = new NBTOutputStream(gzipOutput)) {
            nbtOutput.writeNamedTag(named.getName(), new CompoundTag(rootValues));
        }
        return bytes.toByteArray();
    }

    private static boolean normalizePalette(Map<String, Tag<?, ?>> container, String paletteKey, Set<String> replaced) {
        Tag<?, ?> rawPalette = container.get(paletteKey);
        if (!(rawPalette instanceof CompoundTag palette)) {
            return false;
        }
        Map<String, Tag<?, ?>> entries = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, Tag<?, ?>> entry : palette.getValue().entrySet()) {
            String normalized = normalizeLegacyPaletteKey(entry.getKey());
            if (!normalized.equals(entry.getKey())) {
                replaced.add(legacyId(entry.getKey()));
                changed = true;
            }
            entries.put(normalized, entry.getValue());
        }
        if (changed) {
            container.put(paletteKey, new CompoundTag(entries));
        }
        return changed;
    }

    private static String normalizeLegacyPaletteKey(String state) {
        String id = legacyId(state);
        Material fallback = legacyMaterial(id);
        if (fallback == null) {
            return state;
        }
        String properties = state.substring(id.length());
        return fallback.getKey().asString() + properties;
    }

    private static String legacyId(String state) {
        int properties = state.indexOf('[');
        return properties < 0 ? state : state.substring(0, properties);
    }

    /**
     * Whether every chunk touched by the schematic's bounding box (placed
     * at `anchor`) is claimed by `factionId` -- must be run on the main
     * thread (FactionsHook touches live claim state).
     */
    public boolean isFullyClaimedBy(Location anchor, BlockVector3 relativeMin, BlockVector3 relativeMax,
            int factionId) {
        if (factionId == FactionsHook.NO_FACTION) {
            return false;
        }

        World world = anchor.getWorld();
        if (world == null) {
            return false;
        }

        int lowestY = anchor.getBlockY() + Math.min(relativeMin.getBlockY(), relativeMax.getBlockY());
        int highestY = anchor.getBlockY() + Math.max(relativeMin.getBlockY(), relativeMax.getBlockY());
        if (lowestY < world.getMinHeight() || highestY >= world.getMaxHeight()) {
            return false;
        }

        int startChunkX = (anchor.getBlockX() + relativeMin.getBlockX()) >> 4;
        int endChunkX = (anchor.getBlockX() + relativeMax.getBlockX()) >> 4;
        int startChunkZ = (anchor.getBlockZ() + relativeMin.getBlockZ()) >> 4;
        int endChunkZ = (anchor.getBlockZ() + relativeMax.getBlockZ()) >> 4;

        int minChunkX = Math.min(startChunkX, endChunkX);
        int maxChunkX = Math.max(startChunkX, endChunkX);
        int minChunkZ = Math.min(startChunkZ, endChunkZ);
        int maxChunkZ = Math.max(startChunkZ, endChunkZ);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Location representative = new Location(world, (chunkX << 4) + 8, 64, (chunkZ << 4) + 8);
                if (FactionsHook.getClaimFactionId(representative) != factionId) {
                    return false;
                }
            }
        }
        return true;
    }

    public void register(ActiveBuild build) {
        activeBuilds.put(build.id(), build);
    }

    public void unregister(ActiveBuild build) {
        activeBuilds.remove(build.id());
    }

    public int persistNew(Location anchor, BlueprintTemplate template, UUID ownerUuid, int ownerFactionId,
            long startedAt) {
        try {
            return storage.insert(anchor, template.name(), ownerUuid.toString(), String.valueOf(ownerFactionId),
                    startedAt);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a new blueprint build.", e);
            return -1;
        }
    }

    /**
     * Database insert for a newly requested build; callers must return to the main
     * thread before touching Bukkit.
     */
    public CompletableFuture<Integer> persistNewAsync(Location anchor, BlueprintTemplate template, UUID ownerUuid,
            int ownerFactionId, long startedAt) {
        CompletableFuture<Integer> write = CompletableFuture.supplyAsync(() -> {
            try {
                return storage.insert(anchor, template.name(), ownerUuid.toString(), String.valueOf(ownerFactionId),
                        startedAt);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
        return write;
    }

    public int resolveFactionId(String persistedFaction) {
        if (persistedFaction == null || persistedFaction.isBlank()) {
            return FactionsHook.NO_FACTION;
        }
        try {
            return Integer.parseInt(persistedFaction);
        } catch (NumberFormatException ignored) {
            return FactionsHook.getFactionIdByTag(persistedFaction);
        }
    }

    public void persistProgress(ActiveBuild build) {
        queueWrite(build.id(), () -> storage.updateProgress(build.id(), build.currentIndex()),
                "Failed to persist blueprint build progress.");
    }

    /** Saves live build positions before shutdown; loaded builds are paused on the next boot. */
    public void pauseForRestart() {
        for (ActiveBuild build : activeBuilds.values()) {
            if (build.id() >= 0 && !build.isCancelled()) {
                build.pause();
                persistProgress(build);
            }
        }
    }

    public void persistRemoval(int id) {
        queueWrite(id, () -> storage.delete(id),
                "Failed to remove a completed/aborted blueprint build from the database.");
    }

    public List<BlueprintStorage.StoredBuild> loadAllFromDatabase() {
        try {
            return storage.loadAll();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load blueprint builds from the database.", e);
            return List.of();
        }
    }

    private void queueWrite(int buildId, SqlOperation operation, String errorMessage) {
        CompletableFuture<Void> write = writeChains.compute(buildId,
                (ignored, previous) -> (previous == null ? CompletableFuture.<Void>completedFuture(null)
                        : previous.handle((done, error) -> null))
                        .thenRunAsync(() -> {
                            try {
                                operation.run();
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.WARNING, errorMessage, e);
                            }
                        }));
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> {
            pendingWrites.remove(write);
            writeChains.remove(buildId, write);
        });
    }

    private void persistCooldownRemoval(UUID uuid) {
        queueCooldownWrite(uuid, () -> storage.deleteCooldown(uuid),
                "Failed to remove expired Blueprint cooldown.");
    }

    private void queueCooldownWrite(UUID uuid, SqlOperation operation, String errorMessage) {
        int writeKey = uuid.hashCode();
        CompletableFuture<Void> write = writeChains.compute(writeKey,
                (ignored, previous) -> (previous == null ? CompletableFuture.<Void>completedFuture(null)
                        : previous.handle((done, error) -> null))
                        .thenRunAsync(() -> {
                            try {
                                operation.run();
                            } catch (Exception e) {
                                plugin.getLogger().log(Level.WARNING, errorMessage, e);
                            }
                        }));
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> {
            pendingWrites.remove(write);
            writeChains.remove(writeKey, write);
        });
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed while waiting for blueprint writes.", e);
        }
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws Exception;
    }

    private record CachedTemplate(List<ActiveBuild.PendingBlock> blocks, BlockVector3 relativeMin,
            BlockVector3 relativeMax) {
    }
}
