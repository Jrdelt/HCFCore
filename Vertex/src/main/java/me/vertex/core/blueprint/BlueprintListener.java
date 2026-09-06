package me.vertex.core.blueprint;

import com.sk89q.worldedit.math.BlockVector3;
import eu.decentsoftware.holograms.api.DHAPI;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Blueprint placement/protection: placing a marked Beacon opens an
 * activation confirmation. The build starts only when its Enable button is
 * clicked; it then becomes unbreakable/unmovable while active, and right-
 * clicking its beacon opens the progress GUI instead of the vanilla beacon
 * interface. The actual gradual placement and periodic re-validation live
 * in {@link #tickBuilds()}, scheduled by VertexPlugin.
 */
public final class BlueprintListener implements Listener {

    private final Plugin plugin;
    private final BlueprintManager manager;
    private final Messages messages;
    private final NamespacedKey templateKey;
    private final boolean hologramsAvailable;
    private final Set<UUID> pendingPlacements = ConcurrentHashMap.newKeySet();

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
            int ownerFactionId = manager.resolveFactionId(stored.ownerFaction());
            ActiveBuild build = new ActiveBuild(stored.id(), anchor, template, ownerUuid, ownerFactionId,
                    stored.startedAt(), "blueprint_" + stored.id(), stored.currentIndex());
            try {
                BlockVector3[] bounds = manager.relativeBounds(template);
                if (!manager.isFullyClaimedBy(anchor, bounds[0], bounds[1], ownerFactionId)) {
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
        // The item is deliberately not consumed yet. The player must opt in
        // using the confirmation GUI before an expensive schematic load or a
        // database row is created.
        event.setCancelled(true);
        Location anchor = event.getBlock().getLocation();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                BlueprintActivationMenu.open(player, messages, anchor, template);
            }
        });
    }

    /** Starts the selected blueprint after its placement confirmation is clicked. */
    void activate(Player player, Location anchor, String templateName) {
        if (!pendingPlacements.add(player.getUniqueId())) {
            return;
        }
        BlueprintTemplate template = manager.getTemplate(templateName);
        if (template == null) {
            failActivation(player, "blueprint.unknown-template");
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
            plugin.getLogger().log(Level.SEVERE, "Failed to load schematic for blueprint '" + template.name() + "'.", e);
            failActivation(player, "blueprint.load-failed");
            return;
        }
        if (!manager.isFullyClaimedBy(anchor, bounds[0], bounds[1], playerFactionId)) {
            failActivation(player, "blueprint.claim-invalid");
            return;
        }
        if (blocks.isEmpty()) {
            plugin.getLogger().warning("Blueprint template '" + template.name() + "' has no non-air blocks, refusing placement.");
            failActivation(player, "blueprint.load-failed");
            return;
        }

        long startedAt = System.currentTimeMillis();
        // The insert needs the generated ID, but database work must never
        // hold the click's server tick hostage. The second validation below
        // closes the gap while the database operation is in flight.
        manager.persistNewAsync(anchor, template, player.getUniqueId(), playerFactionId, startedAt)
                .whenComplete((id, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    pendingPlacements.remove(player.getUniqueId());
                    if (error != null || id == null || id < 0 || !player.isOnline()
                            || FactionsHook.getFactionId(player) != playerFactionId
                            || !manager.isFullyClaimedBy(anchor, bounds[0], bounds[1], playerFactionId)
                            || anchor.getBlock().getType() != Material.AIR || !consumeBlueprint(player, templateName)) {
                        if (id != null && id >= 0) {
                            manager.persistRemoval(id);
                        }
                        if (player.isOnline()) {
                            player.sendMessage(messages.get(player, "blueprint.load-failed"));
                        }
                        return;
                    }
                    anchor.getBlock().setType(Material.BEACON, false);
                    ActiveBuild build = new ActiveBuild(id, anchor, template, player.getUniqueId(), playerFactionId,
                            startedAt, "blueprint_" + id, 0);
                    build.setBlocks(blocks);
                    manager.register(build);
                    manager.startCooldown(player.getUniqueId());
                    createOrUpdateHologram(build, "0");
                    player.sendMessage(messages.get(player, "blueprint.started", "template", template.displayName()));
                }));
    }

    private void failActivation(Player player, String messageKey) {
        pendingPlacements.remove(player.getUniqueId());
        player.sendMessage(messages.get(player, messageKey));
    }

    private boolean consumeBlueprint(Player player, String templateName) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getItemMeta() == null
                    || !templateName.equalsIgnoreCase(item.getItemMeta().getPersistentDataContainer()
                    .get(templateKey, PersistentDataType.STRING))) {
                continue;
            }
            if (item.getAmount() == 1) {
                player.getInventory().setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(slot, item);
            }
            return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
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
     * Gradual placement + periodic re-validation, scheduled every server
     * tick by VertexPlugin. Each active build places a small bottom-to-top
     * row slice each tick to stay on schedule for build-time-seconds, and
     * its origin claim is re-checked every claim-recheck-interval-ticks
     * -- if it's no longer 100% the owning faction's land, the build aborts
     * immediately (blocks placed so far stay, the beacon is not refunded).
     */
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
                        finish(build);
                        continue;
                    }
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to re-validate blueprint build " + build.id(), e);
                }
            }
            placeBatch(build, nowTicks);
            if (build.isComplete()) {
                finish(build);
            }
        }
    }

    private void placeBatch(ActiveBuild build, long nowTicks) {
        List<ActiveBuild.PendingBlock> blocks = build.blocks();
        int totalTicks = Math.max(1, manager.buildTimeSeconds() * 20);
        int blocksPerTick = Math.min(manager.maxBlocksPerTick(),
                Math.max(1, (int) Math.ceil((double) blocks.size() / totalTicks)));
        int end = Math.min(blocks.size(), build.currentIndex() + blocksPerTick);
        Location anchor = build.anchor();
        for (int i = build.currentIndex(); i < end; i++) {
            ActiveBuild.PendingBlock pending = blocks.get(i);
            Location target = anchor.clone().add(pending.relativeOffset().x(), pending.relativeOffset().y(), pending.relativeOffset().z());
            target.getBlock().setBlockData(pending.data(), false);
        }
        build.setCurrentIndex(end);
        // Persisting to MySQL and rewriting hologram lines every tick would
        // be needless churn. Building remains tick-by-tick; these visible/
        // durable checkpoints are throttled to the configured interval.
        int checkpointInterval = Math.max(20, manager.batchIntervalTicks());
        if (end == blocks.size() || nowTicks % checkpointInterval == 0) {
            manager.persistProgress(build);
            createOrUpdateHologram(build, String.format("%.0f%%", 100.0 * end / blocks.size()));
        }
    }

    private void finish(ActiveBuild build) {
        manager.unregister(build);
        manager.persistRemoval(build.id());
        removeHologram(build);
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

    /** Never leave a finished, cancelled, or claim-aborted Blueprint hologram behind. */
    private void removeHologram(ActiveBuild build) {
        if (!hologramsAvailable) {
            return;
        }
        try {
            DHAPI.removeHologram(build.hologramName());
        } catch (Exception ignored) {
            // It may already have been removed with /dh or by a plugin reload.
        }
    }

    /**
     * DecentHolograms parses legacy ampersand codes, not MiniMessage. Every
     * line therefore goes through MessageFormatter before being handed to its
     * API; otherwise strings such as {@code <gold>} render with the wrong
     * colour (or literally) in the hologram.
     */
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
        String faction = FactionsHook.getFactionName(build.ownerFactionId());
        return faction == null || faction.isBlank() ? "-" : faction;
    }
}
