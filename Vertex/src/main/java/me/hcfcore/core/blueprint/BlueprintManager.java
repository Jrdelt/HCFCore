package me.hcfcore.core.blueprint;

import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import me.hcfcore.core.factions.FactionsHook;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<Integer, ActiveBuild> activeBuilds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile String schematicsFolder;
    private volatile int cooldownSeconds;
    private volatile int buildTimeSeconds;
    private volatile int batchIntervalTicks;
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
        cooldownSeconds = Math.max(0, config.getInt("cooldown-seconds", 3600));
        buildTimeSeconds = Math.max(1, config.getInt("build-time-seconds", 600));
        batchIntervalTicks = Math.max(1, config.getInt("batch-interval-ticks", 20));
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

    public int buildTimeSeconds() {
        return buildTimeSeconds;
    }

    public int batchIntervalTicks() {
        return batchIntervalTicks;
    }

    public int claimRecheckIntervalTicks() {
        return claimRecheckIntervalTicks;
    }

    public BlueprintTemplate getTemplate(String name) {
        return templates.get(name.toLowerCase(Locale.ROOT));
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
            BlockData data = BukkitAdapter.adapt(block);
            blocks.add(new ActiveBuild.PendingBlock(pos.subtract(origin), data));
        }
        return blocks;
    }

    /** The schematic's own bounding box, relative to its origin (so callers can add an anchor to get world coordinates). */
    public BlockVector3[] relativeBounds(BlueprintTemplate template) throws IOException {
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
        return new BlockVector3[] {
                clipboard.getRegion().getMinimumPoint().subtract(origin),
                clipboard.getRegion().getMaximumPoint().subtract(origin)
        };
    }

    /**
     * Whether every chunk touched by the schematic's bounding box (placed
     * at `anchor`) is claimed by `factionTag` -- must be run on the main
     * thread (FactionsHook touches live claim state).
     */
    public boolean isFullyClaimedBy(Location anchor, BlockVector3 relativeMin, BlockVector3 relativeMax, String factionTag) {
        if (factionTag == null) {
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
                String claimTag = FactionsHook.getClaimFactionTag(representative);
                if (claimTag == null || !claimTag.equalsIgnoreCase(factionTag)) {
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

    public int persistNew(Location anchor, BlueprintTemplate template, UUID ownerUuid, String ownerFaction, long startedAt) {
        try {
            return storage.insert(anchor, template.name(), ownerUuid.toString(), ownerFaction, startedAt);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist a new blueprint build.", e);
            return -1;
        }
    }

    public void persistProgress(ActiveBuild build) {
        try {
            storage.updateProgress(build.id(), build.currentIndex());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to persist blueprint build progress.", e);
        }
    }

    public void persistRemoval(int id) {
        try {
            storage.delete(id);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to remove a completed/aborted blueprint build from the database.", e);
        }
    }

    public List<BlueprintStorage.StoredBuild> loadAllFromDatabase() {
        try {
            return storage.loadAll();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load blueprint builds from the database.", e);
            return List.of();
        }
    }
}
