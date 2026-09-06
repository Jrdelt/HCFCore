package me.vertex.core.blueprint;

import com.sk89q.worldedit.math.BlockVector3;
import eu.decentsoftware.holograms.api.DHAPI;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Chunk;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Blueprint placement/protection: placing a marked Beacon saves template
 * metadata.
 * Right-clicking an unactivated beacon opens confirmation; clicking enable
 * starts
 * construction. Completed beacons monitor structural integrity and can be
 * repaired.
 * Protects anchors from manual breaks and restricts GUI access to claiming
 * faction.
 */
public final class BlueprintListener implements Listener {

    public record CompletedAnchor(Location anchor, String templateName, String holoName) {
    }

    private final Plugin plugin;
    private final BlueprintManager manager;
    private final Messages messages;
    private final NamespacedKey templateKey;
    private final NamespacedKey completedKey;
    private final NamespacedKey holoNameKey;
    private final NamespacedKey activeBuildKey;
    private final boolean hologramsAvailable;
    private final Set<UUID> pendingPlacements = ConcurrentHashMap.newKeySet();
    private final Set<CompletedAnchor> completedAnchors = ConcurrentHashMap.newKeySet();
    private final AtomicInteger nextRepairId = new AtomicInteger(-1);

    public BlueprintListener(Plugin plugin, BlueprintManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
        this.templateKey = new NamespacedKey(plugin, "blueprint_template");
        this.completedKey = new NamespacedKey(plugin, "blueprint_completed_template");
        this.holoNameKey = new NamespacedKey(plugin, "blueprint_completed_holo");
        this.activeBuildKey = new NamespacedKey(plugin, "blueprint_active_build_id");
        this.hologramsAvailable = plugin.getServer().getPluginManager().getPlugin("DecentHolograms") != null;
    }

    public NamespacedKey templateKey() {
        return templateKey;
    }

    public NamespacedKey completedKey() {
        return completedKey;
    }

    public NamespacedKey holoNameKey() {
        return holoNameKey;
    }

    public boolean isCompletedAnchor(Location loc) {
        if (loc == null)
            return false;
        for (CompletedAnchor ca : completedAnchors) {
            if (sameBlock(ca.anchor(), loc)) {
                return true;
            }
        }
        if (loc.getBlock().getState() instanceof org.bukkit.block.Beacon beacon) {
            return beacon.getPersistentDataContainer().has(completedKey, PersistentDataType.STRING);
        }
        return false;
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && b.getWorld() != null
                && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    public void resumeAll() {
        if (!manager.isEnabled()) {
            return;
        }
        for (BlueprintStorage.StoredBuild stored : manager.loadAllFromDatabase()) {
            org.bukkit.World world = plugin.getServer().getWorld(stored.world());
            if (world == null) {
                continue;
            }
            BlueprintTemplate template = manager.getTemplate(stored.template());
            if (template == null) {
                plugin.getLogger().warning("Blueprint build " + stored.id() + " references unknown template '"
                        + stored.template() + "', leaving it as-is (not resumed).");
                continue;
            }
            Location anchor = new Location(world, stored.x(), stored.y(), stored.z());
            if (!isSavedActiveAnchor(anchor, stored.id()) && !migrateLegacyActiveAnchor(anchor, stored.id())) {
                // The anchor was actually removed while Vertex was offline.
                // Never resume a structure without the beacon that owns it.
                manager.persistRemoval(stored.id());
                continue;
            }
            UUID ownerUuid;
            try {
                ownerUuid = UUID.fromString(stored.ownerUuid());
            } catch (IllegalArgumentException e) {
                continue;
            }
            int ownerFactionId = manager.resolveFactionId(stored.ownerFaction());
            ActiveBuild build = new ActiveBuild(stored.id(), anchor, template, ownerUuid, ownerFactionId,
                    stored.startedAt(), "blueprint_" + stored.id(), stored.currentIndex());
            try {
                if (!manager.hasSnapshot(stored.id())) {
                    // One-time compatibility fallback for builds started
                    // before snapshots existed. New builds always snapshot
                    // before their active marker is written.
                    manager.createSnapshot(stored.id(), template);
                    plugin.getLogger().warning("Blueprint build " + stored.id()
                            + " had no snapshot; created one from the current template for future restarts.");
                }
                BlockVector3[] bounds = manager.relativeBoundsSnapshot(stored.id(), template);
                if (!manager.isFullyClaimedBy(anchor, bounds[0], bounds[1], ownerFactionId)) {
                    manager.persistRemoval(stored.id());
                    continue;
                }
                build.setBlocks(manager.flattenSnapshot(stored.id(), template));
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to resume blueprint build " + stored.id(), e);
                continue;
            }
            manager.register(build);
            createOrUpdateHologram(build, String.format("%.0f%%",
                    build.blocks().isEmpty() ? 100.0 : 100.0 * build.currentIndex() / build.blocks().size()));
        }
        Bukkit.getScheduler().runTask(plugin, this::restoreLoadedCompletedAnchors);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!manager.isEnabled() || event.getBlock().getType() != Material.BEACON) {
            return;
        }
        ItemMeta meta = event.getItemInHand().getItemMeta();
        String templateName = meta == null ? null
                : meta.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING);
        if (templateName == null) {
            return;
        }
        BlueprintTemplate template = manager.getTemplate(templateName);
        Player player = event.getPlayer();
        if (template == null) {
            player.sendMessage(messages.get(player, "blueprint.unknown-template"));
            event.setCancelled(true);
            return;
        }

        Location loc = event.getBlock().getLocation();
        String previewHoloName = "blueprint_preview_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();

        if (event.getBlock().getState() instanceof org.bukkit.block.Beacon beacon) {
            beacon.getPersistentDataContainer().set(templateKey, PersistentDataType.STRING, template.name());
            beacon.getPersistentDataContainer().set(holoNameKey, PersistentDataType.STRING, previewHoloName);
            beacon.update(true, false);
        }

        // Open confirmation after the block has been committed to the world.
        // The next tick also lets other placement listeners cancel first.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !isUnactivatedAnchor(loc, template.name())) {
                return;
            }
            createPreviewHologram(loc, template, player);
            BlueprintActivationMenu.open(player, messages, loc, template);
        });
    }

    void activate(Player player, Location anchor, String templateName) {
        if (!pendingPlacements.add(player.getUniqueId())) {
            return;
        }

        BlueprintTemplate template = manager.getTemplate(templateName);
        if (template == null) {
            failActivation(player, "blueprint.unknown-template");
            return;
        }

        if (!isUnactivatedAnchor(anchor, template.name())) {
            failActivation(player, "blueprint.activation-expired");
            return;
        }

        if (manager.isOnCooldown(player.getUniqueId())) {
            long seconds = (manager.remainingCooldownMillis(player.getUniqueId()) + 999) / 1000;
            player.sendMessage(messages.get(player, "blueprint.on-cooldown", "seconds", String.valueOf(seconds)));
            pendingPlacements.remove(player.getUniqueId());
            return;
        }

        int playerFactionId = FactionsHook.getFactionId(player);
        if (playerFactionId == FactionsHook.NO_FACTION) {
            failActivation(player, "blueprint.no-faction");
            return;
        }

        BlockVector3[] bounds;
        List<ActiveBuild.PendingBlock> blocks;
        try {
            bounds = manager.relativeBounds(template);
            blocks = manager.flatten(template);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load schematic for blueprint '" + template.name() + "'.",
                    e);
            failActivation(player, "blueprint.load-failed");
            return;
        }

        if (blocks.isEmpty()) {
            plugin.getLogger()
                    .warning("Blueprint template '" + template.name() + "' has no non-air blocks, refusing placement.");
            failActivation(player, "blueprint.load-failed");
            return;
        }

        org.bukkit.World world = anchor.getWorld();
        if (world == null) {
            failActivation(player, "blueprint.load-failed");
            return;
        }

        int lowestY = anchor.getBlockY() + Math.min(bounds[0].getBlockY(), bounds[1].getBlockY());
        int highestY = anchor.getBlockY() + Math.max(bounds[0].getBlockY(), bounds[1].getBlockY());
        if (lowestY < world.getMinHeight() || highestY >= world.getMaxHeight()) {
            failActivation(player, "blueprint.height-limit-exceeded");
            return;
        }

        if (!manager.isFullyClaimedBy(anchor, bounds[0], bounds[1], playerFactionId)) {
            failActivation(player, "blueprint.claim-invalid");
            return;
        }

        long startedAt = System.currentTimeMillis();

        manager.persistNewAsync(anchor, template, player.getUniqueId(), playerFactionId, startedAt)
                .whenComplete((id, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    pendingPlacements.remove(player.getUniqueId());

                    if (error != null || id == null || id < 0 || !player.isOnline()
                            || FactionsHook.getFactionId(player) != playerFactionId
                            || !manager.isFullyClaimedBy(anchor, bounds[0], bounds[1], playerFactionId)
                            || !isUnactivatedAnchor(anchor, template.name())) {
                        if (id != null && id >= 0) {
                            manager.persistRemoval(id);
                        }
                        if (player.isOnline()) {
                            player.sendMessage(messages.get(player, "blueprint.load-failed"));
                        }
                        return;
                    }

                    String previewHolo = "blueprint_preview_" + anchor.getBlockX() + "_" + anchor.getBlockY() + "_"
                            + anchor.getBlockZ();
                    if (hologramsAvailable) {
                        try {
                            DHAPI.removeHologram(previewHolo);
                        } catch (Exception ignored) {
                        }
                    }

                    if (anchor.getBlock().getState() instanceof org.bukkit.block.Beacon beacon) {
                        try {
                            manager.createSnapshot(id, template);
                        } catch (IOException e) {
                            manager.persistRemoval(id);
                            player.sendMessage(messages.get(player, "blueprint.load-failed"));
                            return;
                        }
                        beacon.getPersistentDataContainer().remove(templateKey);
                        beacon.getPersistentDataContainer().set(activeBuildKey, PersistentDataType.INTEGER, id);
                        beacon.update(true, false);
                    }

                    ActiveBuild build = new ActiveBuild(id, anchor, template, player.getUniqueId(), playerFactionId,
                            startedAt, "blueprint_" + id, 0);
                    build.setBlocks(blocks);
                    manager.register(build);
                    manager.startCooldown(player.getUniqueId());
                    createOrUpdateHologram(build, "0%");
                    player.sendMessage(messages.get(player, "blueprint.started", "template", template.displayName()));
                }));
    }

    private void failActivation(Player player, String messageKey) {
        pendingPlacements.remove(player.getUniqueId());
        player.sendMessage(messages.get(player, messageKey));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.BEACON) {
            return;
        }

        Location loc = block.getLocation();
        ActiveBuild activeBuild = manager.activeBuildAt(loc);
        boolean isCompleted = isCompletedAnchor(loc);

        // Not a blueprint anchor block
        if (activeBuild == null && !isCompleted && !(block.getState() instanceof org.bukkit.block.Beacon b
                && b.getPersistentDataContainer().has(templateKey, PersistentDataType.STRING))) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        // Check faction claim membership
        int playerFactionId = FactionsHook.getFactionId(player);
        int claimFactionId = FactionsHook.getClaimFactionId(loc);

        if (playerFactionId == FactionsHook.NO_FACTION || playerFactionId != claimFactionId) {
            player.sendMessage(messages.get(player, "blueprint.not-your-faction"));
            return;
        }

        // 1. In-progress build progress / cancel menu
        if (activeBuild != null) {
            BlueprintMenu.openActive(player, manager, messages, activeBuild);
            return;
        }

        // 2. Beacon interaction: Completed build repair menu or Unactivated build
        // activation GUI
        if (block.getState() instanceof org.bukkit.block.Beacon beacon) {
            String completedTemplate = beacon.getPersistentDataContainer().get(completedKey, PersistentDataType.STRING);
            if (completedTemplate != null) {
                BlueprintTemplate template = manager.getTemplate(completedTemplate);
                if (template != null) {
                    List<ActiveBuild.PendingBlock> missing = findMissingBlocks(loc, template);
                    String holoName = beacon.getPersistentDataContainer().get(holoNameKey, PersistentDataType.STRING);

                    if (holoName != null) {
                        completedAnchors.add(new CompletedAnchor(loc, completedTemplate, holoName));
                    }

                    updateCompletedHologram(loc, template, missing.size(), holoName);
                    BlueprintMenu.openCompleted(player, manager, messages, loc, template, missing);
                }
                return;
            }

            String unactivatedTemplate = beacon.getPersistentDataContainer().get(templateKey,
                    PersistentDataType.STRING);
            if (unactivatedTemplate != null) {
                BlueprintTemplate template = manager.getTemplate(unactivatedTemplate);
                if (template != null) {
                    BlueprintActivationMenu.open(player, messages, loc, template);
                }
            }
        }
    }

    private boolean isUnactivatedAnchor(Location anchor, String templateName) {
        if (anchor == null || anchor.getWorld() == null || anchor.getBlock().getType() != Material.BEACON) {
            return false;
        }
        if (!(anchor.getBlock().getState() instanceof org.bukkit.block.Beacon beacon)) {
            return false;
        }
        String storedTemplate = beacon.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING);
        return storedTemplate != null && storedTemplate.equalsIgnoreCase(templateName);
    }

    private boolean isSavedActiveAnchor(Location anchor, int buildId) {
        if (anchor.getWorld() == null || anchor.getBlock().getType() != Material.BEACON
                || !(anchor.getBlock().getState() instanceof org.bukkit.block.Beacon beacon)) {
            return false;
        }
        Integer storedId = beacon.getPersistentDataContainer().get(activeBuildKey, PersistentDataType.INTEGER);
        return storedId != null && storedId == buildId;
    }

    /** Backfills the active-build marker for a pre-marker active beacon. */
    private boolean migrateLegacyActiveAnchor(Location anchor, int buildId) {
        if (anchor.getWorld() == null || anchor.getBlock().getType() != Material.BEACON
                || !(anchor.getBlock().getState() instanceof org.bukkit.block.Beacon beacon)) {
            return false;
        }
        if (beacon.getPersistentDataContainer().has(activeBuildKey, PersistentDataType.INTEGER)
                || beacon.getPersistentDataContainer().has(templateKey, PersistentDataType.STRING)
                || beacon.getPersistentDataContainer().has(completedKey, PersistentDataType.STRING)) {
            return false;
        }
        beacon.getPersistentDataContainer().set(activeBuildKey, PersistentDataType.INTEGER, buildId);
        beacon.update(true, false);
        plugin.getLogger().info("Migrated legacy active Blueprint anchor for build " + buildId + ".");
        return true;
    }

    /** Rechecks faction and claim ownership when a previously-open GUI is clicked. */
    public boolean canManage(Player player, Location anchor) {
        if (player == null || anchor == null) {
            return false;
        }
        int playerFactionId = FactionsHook.getFactionId(player);
        return playerFactionId != FactionsHook.NO_FACTION
                && playerFactionId == FactionsHook.getClaimFactionId(anchor);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        restoreCompletedAnchors(event.getChunk());
    }

    private void restoreLoadedCompletedAnchors() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                restoreCompletedAnchors(chunk);
            }
        }
    }

    private void restoreCompletedAnchors(Chunk chunk) {
        for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof org.bukkit.block.Beacon beacon)) {
                continue;
            }
            String templateName = beacon.getPersistentDataContainer().get(completedKey, PersistentDataType.STRING);
            String hologramName = beacon.getPersistentDataContainer().get(holoNameKey, PersistentDataType.STRING);
            if (templateName == null || hologramName == null || manager.getTemplate(templateName) == null) {
                continue;
            }
            Location anchor = beacon.getLocation();
            completedAnchors.removeIf(completed -> sameBlock(completed.anchor(), anchor));
            completedAnchors.add(new CompletedAnchor(anchor, templateName, hologramName));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();

        // 1. Block breaking active builds or completed anchors completely
        if (manager.activeBuildAt(loc) != null || isCompletedAnchor(loc)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(messages.get(event.getPlayer(), "blueprint.cannot-break-anchor"));
            return;
        }

        // 2. Allow breaking unactivated preview beacons
        if (event.getBlock().getType() == Material.BEACON
                && event.getBlock().getState() instanceof org.bukkit.block.Beacon beacon) {
            String unactivatedTemplate = beacon.getPersistentDataContainer().get(templateKey,
                    PersistentDataType.STRING);
            if (unactivatedTemplate != null) {
                BlueprintTemplate template = manager.getTemplate(unactivatedTemplate);

                // Prevent vanilla beacon drop
                event.setDropItems(false);

                // Clean up preview hologram
                String previewHolo = "blueprint_preview_" + loc.getBlockX() + "_" + loc.getBlockY() + "_"
                        + loc.getBlockZ();
                if (hologramsAvailable) {
                    try {
                        DHAPI.removeHologram(previewHolo);
                    } catch (Exception ignored) {
                    }
                }

                // Drop the custom blueprint item with PDC data
                if (template != null) {
                    ItemStack item = createBlueprintItem(template);
                    loc.getWorld().dropItemNaturally(loc, item);
                }
            }
        }
    }

    public List<ActiveBuild.PendingBlock> findMissingBlocks(Location anchor, BlueprintTemplate template) {
        List<ActiveBuild.PendingBlock> expectedBlocks;
        try {
            expectedBlocks = manager.flatten(template);
        } catch (IOException e) {
            return List.of();
        }

        java.util.Map<String, org.bukkit.block.data.BlockData> expectedMap = new java.util.HashMap<>();
        for (ActiveBuild.PendingBlock pb : expectedBlocks) {
            String key = pb.relativeOffset().getBlockX() + "," + pb.relativeOffset().getBlockY() + ","
                    + pb.relativeOffset().getBlockZ();
            expectedMap.put(key, pb.data());
        }

        List<ActiveBuild.PendingBlock> missing = new ArrayList<>();
        org.bukkit.block.data.BlockData airData = Material.AIR.createBlockData();

        BlockVector3[] bounds;
        try {
            bounds = manager.relativeBounds(template);
        } catch (IOException e) {
            return List.of();
        }

        int minX = Math.min(bounds[0].getBlockX(), bounds[1].getBlockX());
        int maxX = Math.max(bounds[0].getBlockX(), bounds[1].getBlockX());
        int minY = Math.min(bounds[0].getBlockY(), bounds[1].getBlockY());
        int maxY = Math.max(bounds[0].getBlockY(), bounds[1].getBlockY());
        int minZ = Math.min(bounds[0].getBlockZ(), bounds[1].getBlockZ());
        int maxZ = Math.max(bounds[0].getBlockZ(), bounds[1].getBlockZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }

                    String coordKey = x + "," + y + "," + z;
                    org.bukkit.block.data.BlockData expected = expectedMap.get(coordKey);
                    Location worldLoc = anchor.clone().add(x, y, z);
                    Block worldBlock = worldLoc.getBlock();
                    org.bukkit.block.data.BlockData currentData = worldBlock.getBlockData();

                    if (expected != null) {
                        if (!currentData.matches(expected)) {
                            missing.add(new ActiveBuild.PendingBlock(BlockVector3.at(x, y, z), expected));
                        } else if (currentData instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) {
                            if (!(expected instanceof org.bukkit.block.data.Waterlogged expWl)
                                    || !expWl.isWaterlogged()) {
                                wl.setWaterlogged(false);
                                missing.add(new ActiveBuild.PendingBlock(BlockVector3.at(x, y, z), wl));
                            }
                        }
                    } else {
                        Material mat = currentData.getMaterial();
                        if (mat == Material.WATER || mat == Material.LAVA || mat == Material.SEAGRASS
                                || mat == Material.KELP) {
                            missing.add(new ActiveBuild.PendingBlock(BlockVector3.at(x, y, z), airData));
                        }
                    }
                }
            }
        }

        // Air/fluid-clearing tasks must occur first, then construction tasks from
        // bottom to top
        missing.sort((a, b) -> {
            boolean aIsAir = a.data().getMaterial().isAir();
            boolean bIsAir = b.data().getMaterial().isAir();

            if (aIsAir && !bIsAir)
                return -1;
            if (!aIsAir && bIsAir)
                return 1;

            int yCompare = Integer.compare(a.relativeOffset().getBlockY(), b.relativeOffset().getBlockY());
            if (yCompare != 0)
                return yCompare;

            int zCompare = Integer.compare(a.relativeOffset().getBlockZ(), b.relativeOffset().getBlockZ());
            if (zCompare != 0)
                return zCompare;

            return Integer.compare(a.relativeOffset().getBlockX(), b.relativeOffset().getBlockX());
        });

        return missing;
    }

    public void startRepair(Player player, Location anchor, String templateName,
            List<ActiveBuild.PendingBlock> missingBlocks) {
        BlueprintTemplate template = manager.getTemplate(templateName);
        if (template == null || missingBlocks.isEmpty() || manager.activeBuildAt(anchor) != null
                || !isCompletedAnchor(anchor)) {
            return;
        }

        int playerFactionId = FactionsHook.getFactionId(player);
        if (playerFactionId == FactionsHook.NO_FACTION
                || playerFactionId != FactionsHook.getClaimFactionId(anchor)) {
            player.sendMessage(messages.get(player, "blueprint.not-your-faction"));
            return;
        }
        long startedAt = System.currentTimeMillis();
        int tempId = nextRepairId.getAndDecrement();

        String canonicalHologramName = completedHologramName(anchor);
        if (canonicalHologramName == null) {
            return;
        }
        ActiveBuild repairBuild = new ActiveBuild(tempId, anchor, template, player.getUniqueId(), playerFactionId,
                startedAt, canonicalHologramName, 0);
        repairBuild.setBlocks(missingBlocks);
        manager.register(repairBuild);

        String repairHoloProgress = hologramMessage("blueprint.holo-repair-progress", "progress", "0%");
        createOrUpdateHologram(repairBuild, repairHoloProgress);

        player.sendMessage(
                messages.get(player, "blueprint.repair-started", "missing", String.valueOf(missingBlocks.size())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplodedBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplodedBlocks(event.blockList());
    }

    private void handleExplodedBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            Location loc = block.getLocation();

            ActiveBuild activeBuild = manager.activeBuildAt(loc);
            if (activeBuild != null) {
                manager.unregister(activeBuild);
                if (activeBuild.id() >= 0) {
                    manager.persistRemoval(activeBuild.id());
                }
                removeHologram(activeBuild);
                continue;
            }

            if (block.getType() == Material.BEACON && block.getState() instanceof org.bukkit.block.Beacon beacon) {
                String holoName = beacon.getPersistentDataContainer().get(holoNameKey, PersistentDataType.STRING);
                if (holoName != null) {
                    completedAnchors.removeIf(ca -> sameBlock(ca.anchor(), loc));
                    if (hologramsAvailable) {
                        try {
                            DHAPI.removeHologram(holoName);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
    }

    private String completedHologramName(Location anchor) {
        if (anchor.getBlock().getState() instanceof org.bukkit.block.Beacon beacon) {
            return beacon.getPersistentDataContainer().get(holoNameKey, PersistentDataType.STRING);
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block moved : event.getBlocks()) {
            if (manager.activeBuildAt(moved.getLocation()) != null || isCompletedAnchor(moved.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block moved : event.getBlocks()) {
            if (manager.activeBuildAt(moved.getLocation()) != null || isCompletedAnchor(moved.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    public void updateCompletedHologram(Location anchor, BlueprintTemplate template, int missingCount,
            String holoName) {
        if (!hologramsAvailable || holoName == null) {
            return;
        }
        try {
            eu.decentsoftware.holograms.api.holograms.Hologram holo = DHAPI.getHologram(holoName);
            String statusLine = missingCount > 0
                    ? hologramMessage("blueprint.holo-completed-damaged", "missing", String.valueOf(missingCount))
                    : hologramMessage("blueprint.holo-completed-intact");

            List<String> lines = List.of(
                    MessageFormatter.legacyAmpersand(template.displayName()),
                    statusLine,
                    hologramMessage("blueprint.holo-completed-manage"));
            if (holo == null) {
                DHAPI.createHologram(holoName, anchor.clone().add(.5, 2, .5), true, lines);
            } else {
                DHAPI.setHologramLines(holo, lines);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update completed hologram state", e);
        }
    }

    public void tickBuilds() {
        long nowTicks = plugin.getServer().getCurrentTick();

        for (ActiveBuild build : List.copyOf(manager.activeBuilds().values())) {
            if (build.isCancelled()) {
                finish(build);
                continue;
            }
            if (nowTicks % manager.claimRecheckIntervalTicks() == 0) {
                try {
                    BlockVector3[] bounds = manager.relativeBounds(build.template());
                    if (!manager.isFullyClaimedBy(build.anchor(), bounds[0], bounds[1], build.ownerFactionId())) {
                        plugin.getLogger()
                                .warning("[Blueprint Debug] Build cancelled due to claim check failing: " + build.id());
                        finish(build);
                        continue;
                    }
                } catch (Throwable t) {
                    plugin.getLogger().log(Level.SEVERE, "[Blueprint Debug] Error during claim check re-validation", t);
                    continue;
                }
            }

            try {
                placeBatch(build, nowTicks);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE, "[Blueprint Debug] Failed placing batch for build " + build.id(),
                        t);
            }

            if (build.isComplete()) {
                finish(build);
            }
        }

        if (nowTicks % 100 == 0 && !completedAnchors.isEmpty()) {
            checkCompletedIntegrity();
        }
    }

    private void checkCompletedIntegrity() {
        for (CompletedAnchor completed : completedAnchors) {
            Location anchor = completed.anchor();

            if (anchor.getWorld() == null
                    || !anchor.getWorld().isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
                continue;
            }

            if (!(anchor.getBlock().getState() instanceof org.bukkit.block.Beacon)) {
                completedAnchors.remove(completed);
                removeHologram(completed.holoName());
                continue;
            }

            if (manager.activeBuildAt(anchor) != null) {
                continue;
            }

            BlueprintTemplate template = manager.getTemplate(completed.templateName());
            if (template == null) {
                continue;
            }

            List<ActiveBuild.PendingBlock> missing = findMissingBlocks(anchor, template);
            updateCompletedHologram(anchor, template, missing.size(), completed.holoName());
        }
    }

    private void placeBatch(ActiveBuild build, long nowTicks) {
        List<ActiveBuild.PendingBlock> blocks = build.blocks();
        int totalBlocks = blocks.size();

        // 1. Check if this build should wait on this tick
        int interval = manager.batchInterval(build.template(), totalBlocks);
        if (interval > 1 && nowTicks % interval != 0) {
            return;
        }

        int blocksPerTick = manager.blocksPerTick(build.template(), totalBlocks);
        int end = Math.min(totalBlocks, build.currentIndex() + blocksPerTick);
        Location anchor = build.anchor();

        for (int i = build.currentIndex(); i < end; i++) {
            ActiveBuild.PendingBlock pending = blocks.get(i);
            Location target = anchor.clone().add(
                    pending.relativeOffset().x(),
                    pending.relativeOffset().y(),
                    pending.relativeOffset().z());
            Block targetBlock = target.getBlock();
            boolean isClearing = pending.data().getMaterial().isAir();

            targetBlock.setBlockData(pending.data(), isClearing);
        }
        build.setCurrentIndex(end);

        int checkpointInterval = Math.max(20, manager.batchIntervalTicks());
        if (end == totalBlocks || nowTicks % checkpointInterval == 0) {
            if (build.id() >= 0) {
                manager.persistProgress(build);
            }
            String progressText = String.format("%.0f%%", 100.0 * end / totalBlocks);
            String displayProgress = build.id() < 0
                    ? hologramMessage("blueprint.holo-repair-progress", "progress", progressText)
                    : progressText;
            createOrUpdateHologram(build, displayProgress);
        }
    }

    private void finish(ActiveBuild build) {
        manager.unregister(build);
        if (build.id() >= 0) {
            manager.persistRemoval(build.id());
            manager.deleteSnapshot(build.id());
        }
        if (build.isComplete()) {
            String holoName = build.hologramName();

            // Broadcast completion to the owning faction (differentiating repair vs initial
            // build)
            String messageKey = build.id() < 0 ? "blueprint.repair-completed" : "blueprint.completed";
            Component completeMsg = messages.get(null, messageKey,
                    "template", build.template().displayName(),
                    "x", String.valueOf(build.anchor().getBlockX()),
                    "y", String.valueOf(build.anchor().getBlockY()),
                    "z", String.valueOf(build.anchor().getBlockZ()));

            FactionsHook.messageFaction(build.ownerFactionId(), completeMsg);

            // Rest of your beacon tag & hologram completion logic...
            if (build.anchor().getBlock().getState() instanceof org.bukkit.block.Beacon beacon) {
                beacon.getPersistentDataContainer().remove(activeBuildKey);
                beacon.getPersistentDataContainer().set(completedKey, PersistentDataType.STRING,
                        build.template().name());
                beacon.getPersistentDataContainer().set(holoNameKey, PersistentDataType.STRING, holoName);
                beacon.update(true, false);
            }

            completedAnchors.removeIf(existing -> sameBlock(existing.anchor(), build.anchor()));
            CompletedAnchor completed = new CompletedAnchor(build.anchor(), build.template().name(), holoName);
            completedAnchors.add(completed);
            updateCompletedHologram(build.anchor(), build.template(), 0, holoName);
        } else {
            removeHologram(build);

            Block beaconBlock = build.anchor().getBlock();
            if (beaconBlock.getType() == Material.BEACON) {
                beaconBlock.setType(Material.AIR, false);

                ItemStack blueprintItem = createBlueprintItem(build.template());
                Player owner = Bukkit.getPlayer(build.ownerUuid());
                if (owner != null && owner.isOnline()) {
                    owner.getInventory().addItem(blueprintItem).values()
                            .forEach(leftover -> owner.getWorld().dropItemNaturally(owner.getLocation(), leftover));
                } else {
                    build.anchor().getWorld().dropItemNaturally(build.anchor(), blueprintItem);
                }
            }
        }
    }

    public ItemStack createBlueprintItem(BlueprintTemplate template) {
        ItemStack item = new ItemStack(Material.BEACON);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageFormatter.deserialize(template.displayName()));
        meta.getPersistentDataContainer().set(templateKey, PersistentDataType.STRING, template.name());
        item.setItemMeta(meta);
        return item;
    }

    private void createOrUpdateHologram(ActiveBuild build, String progress) {
        if (!hologramsAvailable) {
            return;
        }
        List<String> lines = activeHologramLines(build, progress);
        try {
            eu.decentsoftware.holograms.api.holograms.Hologram existing = DHAPI.getHologram(build.hologramName());
            if (existing == null) {
                Location above = build.anchor().clone().add(0.5, 2, 0.5);
                DHAPI.createHologram(build.hologramName(), above, true, lines);
            } else {
                DHAPI.setHologramLines(existing, lines);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to update blueprint hologram for build " + build.id(), e);
        }
    }

    private void createPreviewHologram(Location anchor, BlueprintTemplate template, Player player) {
        if (!hologramsAvailable) {
            return;
        }
        String holoName = "blueprint_preview_" + anchor.getBlockX() + "_" + anchor.getBlockY() + "_"
                + anchor.getBlockZ();
        Location above = anchor.clone().add(0.5, 2, 0.5);
        List<String> lines = List.of(
                MessageFormatter.legacyAmpersand(template.displayName()),
                hologramMessage("blueprint.holo-preview-activate"),
                hologramMessage("blueprint.holo-preview-placed-by", "faction",
                        factionName(FactionsHook.getFactionId(player)), "player", player.getName()));
        try {
            eu.decentsoftware.holograms.api.holograms.Hologram existing = DHAPI.getHologram(holoName);
            if (existing == null) {
                DHAPI.createHologram(holoName, above, true, lines);
            } else {
                DHAPI.setHologramLines(existing, lines);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to create preview hologram", e);
        }
    }

    private void removeHologram(ActiveBuild build) {
        removeHologram(build.hologramName());
    }

    private void removeHologram(String hologramName) {
        if (!hologramsAvailable || hologramName == null || hologramName.isBlank()) {
            return;
        }
        try {
            DHAPI.removeHologram(hologramName);
        } catch (Exception ignored) {
        }
    }

    private List<String> activeHologramLines(ActiveBuild build, String progress) {
        return List.of(
                MessageFormatter.legacyAmpersand(build.template().displayName()),
                hologramMessage("blueprint.hologram-progress", "progress", progress),
                hologramMessage("blueprint.hologram-owner", "faction", factionName(build), "owner", ownerName(build)));
    }

    private String hologramMessage(String key, String... placeholders) {
        return MessageFormatter.legacyAmpersand(messages.getRaw(Bukkit.getConsoleSender(), key, placeholders));
    }

    private String ownerName(ActiveBuild build) {
        String name = Bukkit.getOfflinePlayer(build.ownerUuid()).getName();
        return name == null || name.isBlank() ? build.ownerUuid().toString() : name;
    }

    private String factionName(ActiveBuild build) {
        return factionName(build.ownerFactionId());
    }

    private String factionName(int factionId) {
        String faction = FactionsHook.getFactionName(factionId);
        return faction == null || faction.isBlank() ? "-" : faction;
    }
}
