package me.vertex.core.blueprint;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
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
    /** Parsed schematics are immutable, so retain their bounds and flattened blocks across batches. */
    private final Map<String, CachedTemplate> parsedTemplates = new ConcurrentHashMap<>();
    private final Map<Integer, ActiveBuild> activeBuilds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /** Avoid a console flood when an old schematic has thousands of one retired block type. */
    private final Set<String> warnedLegacyBlockIds = ConcurrentHashMap.newKeySet();
    private final java.util.Set<CompletableFuture<?>> pendingWrites = ConcurrentHashMap.newKeySet();
    private final Map<Integer, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile String schematicsFolder;
    private volatile int cooldownSeconds;
    private volatile int buildTimeSeconds;
    private volatile int batchIntervalTicks;
    private volatile int maxBlocksPerTick;
    private volatile int claimRecheckIntervalTicks;

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
                loaded.put(name.toLowerCase(Locale.ROOT), new BlueprintTemplate(name, schematic, displayName));
            }
        }
        templates.clear();
        templates.putAll(loaded);
        parsedTemplates.clear();
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
        cooldowns.put(uuid, System.currentTimeMillis() + cooldownSeconds * 1000L);
    }

    /**
     * Removes a player's active Blueprint placement cooldown. Kept separate
     * from command permissions so a future store/reward integration can call
     * the same safe operation after completing its purchase.
     *
     * @return true when the player had an active cooldown to remove
     */
    public boolean clearCooldown(UUID uuid) {
        return cooldowns.remove(uuid) != null;
    }

    public int buildTimeSeconds() {
        return buildTimeSeconds;
    }

    public int batchIntervalTicks() {
        return batchIntervalTicks;
    }

    /** Hard safety cap for a single blueprint's work during one server tick. */
    public int maxBlocksPerTick() {
        return maxBlocksPerTick;
    }

    public int claimRecheckIntervalTicks() {
        return claimRecheckIntervalTicks;
    }

    public BlueprintTemplate getTemplate(String name) {
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
        return a.getWorld().equals(b.getWorld()) && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY() && a.getBlockZ() == b.getBlockZ();
    }

    private File schematicFile(BlueprintTemplate template) {
        return new File(new File(plugin.getDataFolder(), schematicsFolder), template.schematicFileName());
    }

    /**
     * Loads and flattens a template's .schem into placeable
     * (relative-offset, BlockData) pairs, skipping air so an already-built
     * area isn't overwritten with holes. Iteration order is deterministic
     * for a given file, which is what makes resuming by a saved index
     * (rather than persisting the whole block list) correct.
     */
    public List<ActiveBuild.PendingBlock> flatten(BlueprintTemplate template) throws IOException {
        return parsed(template).blocks();
    }

    /** The schematic's own bounding box, relative to its origin. */
    public BlockVector3[] relativeBounds(BlueprintTemplate template) throws IOException {
        CachedTemplate cached = parsed(template);
        return new BlockVector3[] {cached.relativeMin(), cached.relativeMax()};
    }

    private CachedTemplate parsed(BlueprintTemplate template) throws IOException {
        CachedTemplate alreadyLoaded = parsedTemplates.get(template.name().toLowerCase(Locale.ROOT));
        if (alreadyLoaded != null) {
            return alreadyLoaded;
        }
        File schemFile = schematicFile(template);
        ClipboardFormat format = ClipboardFormats.findByFile(schemFile);
        if (format == null) {
            throw new IOException("Unrecognized or missing schematic file: " + schemFile);
        }
        Clipboard clipboard;
        try (ClipboardReader reader = format.getReader(new FileInputStream(schemFile))) {
            clipboard = reader.read();
        }
        BlockVector3 origin = clipboard.getOrigin();
        List<ActiveBuild.PendingBlock> blocks = new ArrayList<>();
        for (BlockVector3 pos : clipboard.getRegion()) {
            BaseBlock block = clipboard.getFullBlock(pos);
            if (block.getBlockType().getMaterial().isAir()) {
                continue;
            }
            BlockData data = adaptBlockData(template, block);
            blocks.add(new ActiveBuild.PendingBlock(pos.subtract(origin), data));
        }
        // Build a visible, deterministic foundation first: each horizontal
        // row completes before the next row, and each Y layer completes
        // before the structure moves upward. The same ordering is rebuilt on
        // restart, so currentIndex remains a safe persistence checkpoint.
        blocks.sort(Comparator
                .comparingInt((ActiveBuild.PendingBlock block) -> block.relativeOffset().y())
                .thenComparingInt(block -> block.relativeOffset().z())
                .thenComparingInt(block -> block.relativeOffset().x()));
        CachedTemplate parsed = new CachedTemplate(List.copyOf(blocks),
                clipboard.getRegion().getMinimumPoint().subtract(origin),
                clipboard.getRegion().getMaximumPoint().subtract(origin));
        CachedTemplate raced = parsedTemplates.putIfAbsent(template.name().toLowerCase(Locale.ROOT), parsed);
        return raced == null ? parsed : raced;
    }

    /**
     * Older schematic exports used generic ids such as {@code minecraft:bed}
     * and {@code minecraft:sign}. Those ids no longer exist in modern Paper,
     * so directly adapting them makes Paper reject every affected block and
     * can leave a Blueprint seemingly empty. Use a valid modern default so
     * the structure can still build; re-exporting the schematic with current
     * FAWE remains the way to retain the original color/wood variant.
     */
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

    /**
     * Whether every chunk touched by the schematic's bounding box (placed
     * at `anchor`) is claimed by `factionId` -- must be run on the main
     * thread (FactionsHook touches live claim state).
     */
    public boolean isFullyClaimedBy(Location anchor, BlockVector3 relativeMin, BlockVector3 relativeMax, int factionId) {
        if (factionId == FactionsHook.NO_FACTION) {
            return false;
        }
        World world = anchor.getWorld();
        int minChunkX = (anchor.getBlockX() + relativeMin.x()) >> 4;
        int maxChunkX = (anchor.getBlockX() + relativeMax.x()) >> 4;
        int minChunkZ = (anchor.getBlockZ() + relativeMin.z()) >> 4;
        int maxChunkZ = (anchor.getBlockZ() + relativeMax.z()) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Location representative = new Location(world, (chunkX << 4) + 8, anchor.getY(), (chunkZ << 4) + 8);
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

    public int persistNew(Location anchor, BlueprintTemplate template, UUID ownerUuid, int ownerFactionId, long startedAt) {
        try {
            return storage.insert(anchor, template.name(), ownerUuid.toString(), String.valueOf(ownerFactionId), startedAt);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a new blueprint build.", e);
            return -1;
        }
    }

    /** Database insert for a newly requested build; callers must return to the main thread before touching Bukkit. */
    public CompletableFuture<Integer> persistNewAsync(Location anchor, BlueprintTemplate template, UUID ownerUuid,
                                                      int ownerFactionId, long startedAt) {
        CompletableFuture<Integer> write = CompletableFuture.supplyAsync(() -> {
            try {
                return storage.insert(anchor, template.name(), ownerUuid.toString(), String.valueOf(ownerFactionId), startedAt);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));
        return write;
    }

    /**
     * Reads both current stable ids and the old tag-based rows. New rows are
     * always ids; legacy rows can be resumed once if their faction still has
     * the same tag.
     */
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

    public void persistRemoval(int id) {
        queueWrite(id, () -> storage.delete(id), "Failed to remove a completed/aborted blueprint build from the database.");
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
        CompletableFuture<Void> write = writeChains.compute(buildId, (ignored, previous) ->
                (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous.handle((done, error) -> null))
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
