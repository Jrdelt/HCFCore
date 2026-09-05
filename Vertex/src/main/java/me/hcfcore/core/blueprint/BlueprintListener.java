package me.hcfcore.core.blueprint;

import com.sk89q.worldedit.math.BlockVector3;
import eu.decentsoftware.holograms.api.DHAPI;
import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Blueprint placement/protection: placing a marked Beacon item starts a
 * build (after claim validation), the beacon is unbreakable/unmovable
 * while its build is active, and right-clicking it opens the progress GUI
 * instead of the vanilla beacon interface. The actual gradual placement
 * and periodic re-validation live in {@link #tickBuilds()}, scheduled by
 * HCFCorePlugin.
 */
public final class BlueprintListener implements Listener {

    private final Plugin plugin;
    private final BlueprintManager manager;
    private final Messages messages;
    private final NamespacedKey templateKey;
    private final boolean hologramsAvailable;

    public BlueprintListener(Plugin plugin, BlueprintManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
        this.templateKey = new NamespacedKey(plugin, "blueprint_template");
        this.hologramsAvailable = plugin.getServer().getPluginManager().getPlugin("DecentHolograms") != null;
    }

    public NamespacedKey templateKey() {
        return templateKey;
    }

    /**
     * Rebuilds every persisted, still-in-progress build from
     * {@link BlueprintStorage} on startup -- re-flattens the same .schem
     * file (deterministic order, see {@link BlueprintManager#flatten}) and
     * resumes from the saved index, but re-validates claim ownership
     * immediately first: land can change hands while the server is down.
     */
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
            UUID ownerUuid;
            try {
                ownerUuid = UUID.fromString(stored.ownerUuid());
            } catch (IllegalArgumentException e) {
                continue;
            }
            ActiveBuild build = new ActiveBuild(stored.id(), anchor, template, ownerUuid, stored.ownerFaction(),
                    stored.startedAt(), "blueprint_" + stored.id(), stored.currentIndex());
            try {
                BlockVector3[] bounds = manager.relativeBounds(template);
                if (!manager.isFullyClaimedBy(anchor, bounds[0], bounds[1], stored.ownerFaction())) {
                    manager.persistRemoval(stored.id());
                    continue;
                }
                build.setBlocks(manager.flatten(template));
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to resume blueprint build " + stored.id(), e);
                continue;
            }
            manager.register(build);
            createOrUpdateHologram(build, String.format("%.0f%%",
                    build.blocks().isEmpty() ? 100.0 : 100.0 * build.currentIndex() / build.blocks().size()));
        }
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
        if (manager.isOnCooldown(player.getUniqueId())) {
            long seconds = (manager.remainingCooldownMillis(player.getUniqueId()) + 999) / 1000;
            player.sendMessage(messages.get(player, "blueprint.on-cooldown", "seconds", String.valueOf(seconds)));
            event.setCancelled(true);
            return;
        }

        Location anchor = event.getBlock().getLocation();
        String playerFactionTag = FactionsHook.getFactionTag(player);
        if (playerFactionTag == null || playerFactionTag.isBlank()) {
            player.sendMessage(messages.get(player, "blueprint.no-faction"));
            event.setCancelled(true);
            return;
        }

        BlockVector3[] bounds;
        try {
            bounds = manager.relativeBounds(template);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to read schematic for blueprint '" + template.name() + "'.", e);
            player.sendMessage(messages.get(player, "blueprint.load-failed"));
            event.setCancelled(true);
            return;
        }
        if (!manager.isFullyClaimedBy(anchor, bounds[0], bounds[1], playerFactionTag)) {
            player.sendMessage(messages.get(player, "blueprint.claim-invalid"));
            event.setCancelled(true);
            return;
        }

        List<ActiveBuild.PendingBlock> blocks;
        try {
            blocks = manager.flatten(template);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load schematic for blueprint '" + template.name() + "'.", e);
            player.sendMessage(messages.get(player, "blueprint.load-failed"));
            event.setCancelled(true);
            return;
        }

        long startedAt = System.currentTimeMillis();
        int id = manager.persistNew(anchor, template, player.getUniqueId(), playerFactionTag, startedAt);
        if (id < 0) {
            player.sendMessage(messages.get(player, "blueprint.load-failed"));
            event.setCancelled(true);
            return;
        }
        String hologramName = "blueprint_" + id;
        ActiveBuild build = new ActiveBuild(id, anchor, template, player.getUniqueId(), playerFactionTag,
                startedAt, hologramName, 0);
        build.setBlocks(blocks);
        manager.register(build);
        manager.startCooldown(player.getUniqueId());
        createOrUpdateHologram(build, "0");
        player.sendMessage(messages.get(player, "blueprint.started", "template", template.displayName()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.BEACON) {
            return;
        }
        ActiveBuild build = manager.activeBuildAt(block.getLocation());
        if (build == null) {
            return;
        }
        event.setCancelled(true);
        BlueprintMenu.open(event.getPlayer(), messages, build);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (manager.activeBuildAt(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> manager.activeBuildAt(block.getLocation()) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> manager.activeBuildAt(block.getLocation()) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block moved : event.getBlocks()) {
            if (manager.activeBuildAt(moved.getLocation()) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block moved : event.getBlocks()) {
            if (manager.activeBuildAt(moved.getLocation()) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /**
     * Gradual placement + periodic re-validation, scheduled every
     * batch-interval-ticks by HCFCorePlugin. Each active build places
     * enough blocks this batch to stay on schedule for build-time-seconds,
     * and its origin claim is re-checked every claim-recheck-interval-ticks
     * -- if it's no longer 100% the owning faction's land, the build aborts
     * immediately (blocks placed so far stay, the beacon is not refunded).
     */
    public void tickBuilds() {
        long nowTicks = plugin.getServer().getCurrentTick();
        for (ActiveBuild build : List.copyOf(manager.activeBuilds().values())) {
            if (build.isCancelled()) {
                finish(build, false);
                continue;
            }
            if (nowTicks % manager.claimRecheckIntervalTicks() < manager.batchIntervalTicks()) {
                try {
                    BlockVector3[] bounds = manager.relativeBounds(build.template());
                    if (!manager.isFullyClaimedBy(build.anchor(), bounds[0], bounds[1], build.ownerFactionTag())) {
                        finish(build, false);
                        continue;
                    }
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to re-validate blueprint build " + build.id(), e);
                }
            }
            placeBatch(build);
            if (build.isComplete()) {
                finish(build, true);
            }
        }
    }

    private void placeBatch(ActiveBuild build) {
        List<ActiveBuild.PendingBlock> blocks = build.blocks();
        int totalBatches = Math.max(1, (manager.buildTimeSeconds() * 20) / manager.batchIntervalTicks());
        int perBatch = Math.max(1, blocks.size() / totalBatches);
        int end = Math.min(blocks.size(), build.currentIndex() + perBatch);
        Location anchor = build.anchor();
        for (int i = build.currentIndex(); i < end; i++) {
            ActiveBuild.PendingBlock pending = blocks.get(i);
            Location target = anchor.clone().add(pending.relativeOffset().x(), pending.relativeOffset().y(), pending.relativeOffset().z());
            target.getBlock().setBlockData(pending.data(), false);
        }
        build.setCurrentIndex(end);
        manager.persistProgress(build);
        createOrUpdateHologram(build, String.format("%.0f%%", 100.0 * end / blocks.size()));
    }

    private void finish(ActiveBuild build, boolean completed) {
        manager.unregister(build);
        manager.persistRemoval(build.id());
        if (hologramsAvailable) {
            try {
                DHAPI.setHologramLines(DHAPI.getHologram(build.hologramName()), List.of(
                        build.template().displayName(),
                        completed ? "<green>Complete" : "<red>Aborted -- land no longer claimed",
                        "<gray>Owner: " + build.ownerUuid()));
            } catch (Exception ignored) {
                // Hologram may already be gone (plugin reload, /dh command, etc.) -- nothing to clean up further.
            }
        }
    }

    private void createOrUpdateHologram(ActiveBuild build, String progress) {
        if (!hologramsAvailable) {
            return;
        }
        List<String> lines = List.of(
                build.template().displayName(),
                "<gray>Progress: <yellow>" + progress,
                "<gray>Owner: <white>" + build.ownerUuid());
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
}
