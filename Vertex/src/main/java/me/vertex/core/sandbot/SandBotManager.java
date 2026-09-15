package me.vertex.core.sandbot;

import me.vertex.core.faction.FactionBankManager;
import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.shop.ShopManager;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SandBotManager {

    private final Plugin plugin;
    private final FactionBankManager factionBankManager;
    private final ShopManager shopManager;
    private final Messages messages;
    private final NamespacedKey itemTagKey;
    private final File stateFile;
    private final Object stateLock = new Object();
    /** Orders state writes so a slower asynchronous save never overwrites a newer one. */
    private final java.util.concurrent.atomic.AtomicLong stateVersion = new java.util.concurrent.atomic.AtomicLong();
    private long writtenStateVersion;
    /** Set when a prepaid balance changed since the last successful state write. */
    private volatile boolean prepaidDirty;
    private long lastPrepaidSaveAt;

    private volatile boolean enabled;
    private volatile int radiusBlocks;
    private volatile long tickIntervalTicks;
    private volatile int placementsPerColumnPerTick;
    private volatile int maxPlacementsPerTick;
    /**
     * Number of recent placement passes kept prepaid.  Bank writes are
     * intentionally asynchronous; without a small balance owned by the bot,
     * a moving cannon can only receive a block after every database round
     * trip.
     */
    private volatile int prepaidBufferPasses;
    private volatile double lowBankWarningThreshold;
    private volatile long lowBankWarningCooldownMillis;
    private final Map<String, SandBotSession> sessions = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> debugPlayers = ConcurrentHashMap.newKeySet();
    private BukkitTask tickTask;
    private volatile boolean loadedPersisted;

    public SandBotManager(Plugin plugin, FactionBankManager factionBankManager, ShopManager shopManager,
            Messages messages, boolean enabled, int radiusBlocks, long tickIntervalTicks,
            int placementsPerColumnPerTick, int maxPlacementsPerTick,
            int prepaidBufferPasses,
            double lowBankWarningThreshold, long lowBankWarningCooldownSeconds,
            org.bukkit.entity.EntityType ignoredNpcType) {
        this.plugin = plugin;
        this.factionBankManager = factionBankManager;
        this.shopManager = shopManager;
        this.messages = messages;
        this.itemTagKey = new NamespacedKey(plugin, "sandbot-item");
        this.stateFile = new File(plugin.getDataFolder(), "sandbots.yml");
        reconfigure(enabled, radiusBlocks, tickIntervalTicks, placementsPerColumnPerTick, maxPlacementsPerTick,
                prepaidBufferPasses,
                lowBankWarningThreshold, lowBankWarningCooldownSeconds, ignoredNpcType);
    }

    Plugin plugin(){return plugin;}

    public void reconfigure(boolean enabled, int radiusBlocks, long tickIntervalTicks,
            int placementsPerColumnPerTick, int maxPlacementsPerTick,
            int prepaidBufferPasses,
            double lowBankWarningThreshold, long lowBankWarningCooldownSeconds,
            org.bukkit.entity.EntityType ignoredNpcType) {
        long previousInterval = this.tickIntervalTicks;
        this.enabled = enabled;
        this.radiusBlocks = Math.max(1, Math.min(5, radiusBlocks));
        this.tickIntervalTicks = Math.max(1, tickIntervalTicks);
        this.placementsPerColumnPerTick = Math.max(1, Math.min(16, placementsPerColumnPerTick));
        this.maxPlacementsPerTick = Math.max(1, Math.min(200, maxPlacementsPerTick));
        this.prepaidBufferPasses = Math.max(1, Math.min(40, prepaidBufferPasses));
        this.lowBankWarningThreshold = Math.max(0D, Double.isFinite(lowBankWarningThreshold)
                ? lowBankWarningThreshold : 500_000D);
        this.lowBankWarningCooldownMillis = Math.max(5_000L, lowBankWarningCooldownSeconds * 1_000L);
        // A reload must apply a changed interval immediately; the scheduler
        // otherwise continues using the period it was created with.
        if (tickTask != null && previousInterval != this.tickIntervalTicks) {
            start();
        }
    }

    public void start() {
        if (!loadedPersisted && isFancyNpcsAvailable()) {
            loadPersisted();
            loadedPersisted = true;
        }
        if (tickTask != null) {
            tickTask.cancel();
        }
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, tickIntervalTicks, tickIntervalTicks);
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        // FancyNPCs NPCs are runtime objects. Remove only the display objects
        // on shutdown; the durable bot records must remain for the next boot.
        for (SandBotSession session : List.copyOf(sessions.values())) {
            removePendingBlocks(session);
            removeNpcOnly(session);
            refundPrepaidBalance(session);
        }
        // Record the refunded (zero) reserves so the next boot does not restore
        // them a second time. Skip when nothing was loaded, or this write would
        // erase bot records that were never read.
        if (loadedPersisted) saveState();
        sessions.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public static boolean isTriggerMaterial(Material material) {
        return outputFor(material) != null;
    }

    private static Material outputFor(Material trigger) {
        return switch (trigger) {
            case ANDESITE -> Material.GRAVEL;
            case SANDSTONE -> Material.SAND;
            case RED_SANDSTONE -> Material.RED_SAND;
            default -> trigger.name().endsWith("_CONCRETE")
                    ? Material.matchMaterial(trigger.name() + "_POWDER")
                    : null;
        };
    }

    public ItemStack createGiveItem() {
        ItemStack item = new ItemStack(Material.SAND);
        ItemMeta meta = item.getItemMeta();
        int area = (radiusBlocks * 2) + 1;

        Component nameComp = messages.getGui(null, "sandbot.item.name");
        meta.displayName(nameComp.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));

        List<Component> lore = messages.getGuiList(null, "sandbot.item.lore", "area", String.valueOf(area));
        meta.lore(lore.stream().map(line -> line.decoration(
                net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());

        meta.getPersistentDataContainer().set(itemTagKey, PersistentDataType.BOOLEAN, true);
        meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isGiveItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemTagKey, PersistentDataType.BOOLEAN);
    }

    public boolean spawn(Player owner, Block surfaceBlock, Location spawnLocation) {
        if (!isFancyNpcsAvailable()) {
            return false;
        }
        int factionId = FactionsHook.getFactionId(owner);
        FactionData faction = FactionsHook.getFactionById(factionId).orElse(null);
        if (faction == null) {
            return false;
        }

        String npcId = "vertex_sandbot_" + UUID.randomUUID();
        SandBotSession session = createSession(npcId, owner.getUniqueId(), owner.getName(), factionId, faction,
                spawnLocation, true);
        if (session == null) return false;
        saveState();
        return true;
    }

    private SandBotSession createSession(String npcId, UUID ownerId, String ownerName, int factionId,
            FactionData faction, Location spawnLocation, boolean active) {
        if (spawnLocation == null || spawnLocation.getWorld() == null) return null;
        NpcData data = new NpcData(npcId, ownerId, spawnLocation);
        data.setDisplayName(messages.getRaw(null, active ? "sandbot.npc-active" : "sandbot.npc-paused"));
        data.setSkin(ownerName == null || ownerName.isBlank() ? ownerId.toString() : ownerName);
        data.setShowInTab(false);
        data.setCollidable(false);
        data.setTurnToPlayer(true);
        data.setType(org.bukkit.entity.EntityType.PLAYER);
        // FancyNPCs invokes this callback itself after receiving the packet
        // interaction. It is more reliable than trying to match a client-only
        // NPC through Bukkit's normal entity interaction events.
        data.setOnClick(player -> handleNpcClick(npcId, player));
        Npc npc = FancyNpcsPlugin.get().getNpcAdapter().apply(data);
        npc.setSaveToFile(false);
        FancyNpcsPlugin.get().getNpcManager().registerNpc(npc);
        npc.create();
        npc.spawnForAll();

        SandBotSession session = new SandBotSession(npcId, npc, ownerId, factionId, faction,
                spawnLocation.getWorld(), spawnLocation.getBlockX(),
                spawnLocation.getBlockY(), spawnLocation.getBlockZ());
        session.active = active;
        sessions.put(npcId, session);
        return session;
    }

    private void loadPersisted() {
        if (!stateFile.exists()) return;
        ConfigurationSection bots = YamlConfiguration.loadConfiguration(stateFile).getConfigurationSection("bots");
        if (bots == null) return;
        for (String npcId : bots.getKeys(false)) {
            try {
                String ownerText = bots.getString(npcId + ".owner");
                String worldName = bots.getString(npcId + ".world");
                int factionId = bots.getInt(npcId + ".faction");
                UUID ownerId = UUID.fromString(ownerText);
                World world = Bukkit.getWorld(worldName == null ? "" : worldName);
                FactionData faction = FactionsHook.getFactionById(factionId).orElse(null);
                double prepaid = bots.getDouble(npcId + ".prepaid", 0D);
                if (!Double.isFinite(prepaid) || prepaid < 0D) prepaid = 0D;
                if (world == null || faction == null) {
                    plugin.getLogger().warning("Skipping Sand Bot " + npcId
                            + " because its world or faction is no longer available.");
                    if (prepaid > 0.000001D) {
                        if (faction != null && factionBankManager != null) {
                            factionBankManager.depositMoney(factionId, prepaid);
                            plugin.getLogger().warning("Returned " + prepaid + " prepaid by skipped Sand Bot "
                                    + npcId + " to faction " + factionId + ".");
                        } else {
                            plugin.getLogger().severe("Sand Bot " + npcId + " held " + prepaid
                                    + " prepaid for faction " + factionId
                                    + ", which no longer exists; the amount could not be returned.");
                        }
                    }
                    continue;
                }
                Location location = new Location(world, bots.getDouble(npcId + ".x"),
                        bots.getDouble(npcId + ".y"), bots.getDouble(npcId + ".z"));
                String ownerName = bots.getString(npcId + ".owner-name", ownerId.toString());
                SandBotSession session = createSession(npcId, ownerId, ownerName, factionId, faction, location,
                        bots.getBoolean(npcId + ".active", true));
                // A crash skips stop()'s refund, so the reserve that was saved
                // resumes on the same Bot instead of being lost.
                session.prepaidBalance = prepaid;
            } catch (RuntimeException error) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "Skipping invalid persisted Sand Bot " + npcId, error);
            }
        }
    }

    private void saveState() {
        writeState(snapshotState(), false);
    }

    private void saveStateAsync() {
        writeState(snapshotState(), true);
    }

    private YamlConfiguration snapshotState() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (SandBotSession session : sessions.values()) {
            String path = "bots." + session.npcId;
            yaml.set(path + ".owner", session.ownerId.toString());
            yaml.set(path + ".owner-name", Bukkit.getOfflinePlayer(session.ownerId).getName());
            yaml.set(path + ".faction", session.factionId);
            yaml.set(path + ".world", session.world.getName());
            yaml.set(path + ".x", session.centerX + 0.5D);
            yaml.set(path + ".y", session.centerY);
            yaml.set(path + ".z", session.centerZ + 0.5D);
            yaml.set(path + ".active", session.active);
            yaml.set(path + ".prepaid", session.prepaidBalance);
        }
        return yaml;
    }

    private void writeState(YamlConfiguration yaml, boolean async) {
        long version = stateVersion.incrementAndGet();
        prepaidDirty = false;
        lastPrepaidSaveAt = System.currentTimeMillis();
        Runnable write = () -> {
            synchronized (stateLock) {
                if (version <= writtenStateVersion) return;
                try {
                    if (!stateFile.getParentFile().exists() && !stateFile.getParentFile().mkdirs()) {
                        throw new IOException("Could not create plugin data directory");
                    }
                    yaml.save(stateFile);
                    writtenStateVersion = version;
                } catch (IOException error) {
                    prepaidDirty = true;
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "Could not persist Sand Bot state", error);
                }
            }
        };
        if (async && plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
        } else {
            write.run();
        }
    }

    /** Pausing returns the unused reserve; resuming funds a new one on the next pass. */
    public void setActive(SandBotSession session, boolean active) {
        session.active = active;
        if (!active) refundPrepaidBalance(session);
        saveState();
    }

    public SandBotSession getSessionByNpcId(String npcId) {
        return sessions.get(npcId);
    }

    public boolean toggleDebug(Player player) {
        if (debugPlayers.remove(player.getUniqueId())) {
            debug(player, "disabled");
            return false;
        }
        debugPlayers.add(player.getUniqueId());
        debug(player, "enabled");
        return true;
    }

    public void debug(Player player, String key, String... placeholders) {
        if (debugPlayers.contains(player.getUniqueId())) {
            player.sendMessage(messages.get(player, "sandbot.debug." + key, placeholders));
        }
    }

    public void handleNpcClick(String npcId, Player player) {
        debug(player, "npc-click", "id", npcId);
        SandBotSession session = sessions.get(npcId);
        if (session == null) {
            debug(player, "npc-session-missing");
            return;
        }
        if (!player.getUniqueId().equals(session.ownerId) && !player.hasPermission("vertex.sandbot.admin")) {
            debug(player, "npc-click-denied");
            player.sendMessage(messages.get(player, "sandbot.not-owner"));
            return;
        }
        debug(player, "opening-panel");
        openControlPanel(player, session);
    }

    public void openControlPanel(Player player, SandBotSession session) {
        Component titleComp = messages.getGui(player, "sandbot.gui.title");
        Inventory gui = Bukkit.createInventory(new ControlPanelHolder(session.npcId), 27, titleComp);

        Component statusComp = messages.getGui(player,
                session.active ? "sandbot.gui.active-status" : "sandbot.gui.paused-status");

        ItemStack statusItem = new ItemStack(session.active ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
        ItemMeta statusMeta = statusItem.getItemMeta();
        statusMeta.displayName(statusComp.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        statusItem.setItemMeta(statusMeta);
        gui.setItem(11, statusItem);

        Component despawnComp = messages.getGui(player, "sandbot.gui.despawn");
        ItemStack despawnItem = new ItemStack(Material.NETHER_STAR);
        ItemMeta despawnMeta = despawnItem.getItemMeta();
        despawnMeta.displayName(despawnComp.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        despawnItem.setItemMeta(despawnMeta);
        gui.setItem(15, despawnItem);

        player.openInventory(gui);
    }

    public int activeSessionsOwnedBy(UUID ownerId) {
        int count = 0;
        for (SandBotSession session : sessions.values()) {
            if (session.ownerId.equals(ownerId)) {
                count++;
            }
        }
        return count;
    }

    public int stopOwnedBy(UUID ownerId) {
        int stopped = 0;
        for (SandBotSession session : List.copyOf(sessions.values())) {
            if (session.ownerId.equals(ownerId)) {
                destroy(session);
                stopped++;
            }
        }
        return stopped;
    }

    private void tickAll() {
        // Spending only lowers a reserve, so saving it about once a second keeps
        // a crash from losing the money while avoiding a disk write every tick.
        if (prepaidDirty && System.currentTimeMillis() - lastPrepaidSaveAt >= 1_000L) {
            saveStateAsync();
        }
        if (!enabled) {
            return;
        }
        for (SandBotSession session : List.copyOf(sessions.values())) {
            tickOne(session);
        }
    }

    private void tickOne(SandBotSession session) {
        // FancyNPCs can emit its interaction event for a temporary NPC before
        // getNpcById() has indexed it. Keep the exact NPC object returned by
        // registerNpc() instead of treating that short lookup delay as a dead
        // Bot and deleting the entire Vertex session.
        Npc npc = session.npc;

        if (!session.active) {
            setNpcStatus(npc, false);
            return;
        }

        setNpcStatus(npc, true);

        World world = session.world;
        int floorY = session.centerY - 1;

        reconcilePendingBlocks(session);

        int anchors = 0;
        int completeColumns = 0;
        int blockedColumns = 0;
        int unclaimedColumns = 0;
        Map<Long, Boolean> claimCache = new HashMap<>();
        List<Column> queue = new ArrayList<>();
        for (int dx = -radiusBlocks; dx <= radiusBlocks; dx++) {
            for (int dz = -radiusBlocks; dz <= radiusBlocks; dz++) {
                Block anchor = world.getBlockAt(session.centerX + dx, floorY, session.centerZ + dz);
                Material output = outputFor(anchor.getType());
                if (output == null) {
                    continue;
                }
                if (!isOwnedClaim(anchor, session, claimCache)) {
                    unclaimedColumns++;
                    continue;
                }
                anchors++;
                ColumnKey key = new ColumnKey(anchor.getX(), anchor.getY(), anchor.getZ());
                int openGap = openGapUnder(anchor, output);
                // A completed stack has reached the underside of its anchor.
                if (openGap == 0 && anchor.getRelative(0, -1, 0).getType() == output) {
                    completeColumns++;
                    continue;
                }
                // A solid block immediately beneath the anchor leaves no gap
                // for a falling block to fill.
                if (openGap == 0) {
                    blockedColumns++;
                    continue;
                }
                // Only one falling entity may be in flight from a source
                // column. It is deliberately allowed to be moved sideways by
                // a piston or slime block after spawning.
                if (pendingCount(session, key) == 0 && openGap > 0) {
                    int targetY = anchor.getY() - openGap;
                    queue.add(new Column(anchor, output, key, targetY));
                }
            }
        }

        if (queue.isEmpty()) {
            // A filled column is an idle state, not completion. Keep the Bot
            // visible and let it resume automatically if cannon movement
            // clears a block or a new anchor/gap appears in its scan radius.
            if (session.pendingColumns.isEmpty()) {
                debugOwner(session, "idle", "anchors", String.valueOf(anchors), "complete", String.valueOf(completeColumns),
                        "blocked", String.valueOf(blockedColumns), "outside", String.valueOf(unclaimedColumns), "y", String.valueOf(floorY));
            }
            return;
        }

        // Begin every available column before returning to a column that has
        // already had a turn. The previous fixed scan order always spent a
        // small global budget at the same edge of the footprint first, making
        // a large print look like staggered horizontal bands instead of a
        // uniform sand wall.
        int queueSize = queue.size();
        int startingIndex = Math.floorMod(session.nextPlacementCursor, queueSize);
        int placementBudget = maxPlacementsPerTick;
        List<PaidPlacement> planned = new ArrayList<>();
        double totalCost = 0D;
        for (int offset = 0; offset < queueSize; offset++) {
            if (placementBudget == 0) {
                break;
            }
            int index = (startingIndex + offset) % queueSize;
            Column column = queue.get(index);
            session.nextPlacementCursor = (index + 1) % queueSize;
            if (!shopManager.isTradeable(column.output)) {
                debugOwner(session, "not-tradeable", "material", column.output.name(), "location", format(column.anchor.getLocation()));
                continue;
            }
            // A single falling entity per source column makes the wall start
            // uniformly without creating overlapping entity physics.
            int placements = Math.min(1, Math.min(placementsPerColumnPerTick, placementBudget));
            double cost = shopManager.buyPrice(column.output);
            if (!Double.isFinite(cost) || cost <= 0D) continue;
            for (int placement = 0; placement < placements; placement++) {
                planned.add(new PaidPlacement(column.anchor, column.output, column.key, column.targetY(), cost));
                totalCost += cost;
                placementBudget--;
            }
        }
        if (planned.isEmpty()) return;
        double bankBalance = factionBankManager == null ? 0D : factionBankManager.money(session.factionId);
        warnLowBankIfNeeded(session, bankBalance);
        if (session.prepaidBalance + 0.000001D >= totalCost) {
            // The bank has already been debited for this small reserve. Do not
            // make a cannon wait for another SQL round trip before each row of
            // its clock cycle; refill below runs in parallel with physics.
            spawnPrepaidPlacements(session, planned);
            refillPrepaidBalance(session, totalCost, false);
            return;
        }

        // The first wave (or a bot that has exhausted its reserve) still
        // waits for a durable debit. Once funded it keeps several placement
        // passes available, so a moving slime/piston path receives entities
        // at the configured tick rate rather than the storage latency.
        refillPrepaidBalance(session, totalCost, true, planned);
    }

    private void refillPrepaidBalance(SandBotSession session, double required, boolean requiredNow) {
        refillPrepaidBalance(session, required, requiredNow, null);
    }

    private void refillPrepaidBalance(SandBotSession session, double required, boolean requiredNow,
            List<PaidPlacement> placementsWaitingForFunds) {
        if (factionBankManager == null || session.prepaidFundingPending) {
            return;
        }
        double missing = Math.max(0D, required - session.prepaidBalance);
        if (requiredNow && missing > 0D
                && factionBankManager.money(session.factionId) + 0.000001D < missing) {
            debugOwner(session, "cannot-pay", "cost", String.valueOf(required));
            pauseForFunds(session);
            return;
        }
        double desired = Math.max(missing, (required * prepaidBufferPasses) - session.prepaidBalance);
        double available = factionBankManager.money(session.factionId);
        double funding = Math.min(desired, Math.max(0D, available));
        if (funding <= 0.000001D) {
            if (requiredNow) {
                debugOwner(session, "cannot-pay", "cost", String.valueOf(required));
                pauseForFunds(session);
            }
            return;
        }
        session.prepaidFundingPending = true;
        factionBankManager.withdrawMoney(session.factionId, funding).whenComplete((withdrawn, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> settlePrepaidFunding(session, funding,
                        placementsWaitingForFunds, error == null && Boolean.TRUE.equals(withdrawn))));
    }

    private void settlePrepaidFunding(SandBotSession session, double funding,
            List<PaidPlacement> placementsWaitingForFunds, boolean withdrawn) {
        session.prepaidFundingPending = false;
        if (!withdrawn) {
            if (sessions.get(session.npcId) == session) {
                if (placementsWaitingForFunds != null) pauseForFunds(session);
            }
            return;
        }
        if (sessions.get(session.npcId) != session || !session.active) {
            factionBankManager.depositMoney(session.factionId, funding);
            return;
        }
        session.prepaidBalance += funding;
        // The bank debit is already durable; record the reserve right away.
        saveStateAsync();
        if (placementsWaitingForFunds != null) {
            spawnPrepaidPlacements(session, placementsWaitingForFunds);
        }
    }

    /** Spawns only against a balance that was successfully debited first. */
    private void spawnPrepaidPlacements(SandBotSession session, List<PaidPlacement> planned) {
        double spent = 0D;
        int spawned = 0;
        List<String> spawnedLocations = new ArrayList<>();
        Map<Long, Boolean> claims = new HashMap<>();
        for (PaidPlacement placement : planned) {
            if (session.prepaidBalance + 0.000001D < placement.cost) break;
            if (outputFor(placement.anchor.getType()) != placement.output
                    || !isOwnedClaim(placement.anchor, session, claims)) continue;
            if (pendingCount(session, placement.key) != 0) continue;
            int openGap = openGapUnder(placement.anchor, placement.output);
            if (openGap <= 0) continue;
            int targetY = placement.anchor.getY() - openGap;
            if (targetY != placement.targetY) continue;

            Location drop = placement.anchor.getLocation().add(0.5D, -1D, 0.5D);
            FallingBlock falling = session.world.spawn(drop, FallingBlock.class,
                    entity -> entity.setBlockData(placement.output.createBlockData()));
            falling.setDropItem(false);
            session.pendingColumns.computeIfAbsent(placement.key, ignored -> new HashMap<>())
                    .put(falling.getUniqueId(), new PendingPlacement());
            spent += placement.cost;
            session.prepaidBalance = Math.max(0D, session.prepaidBalance - placement.cost);
            spawned++;
            // Keep the diagnostic useful when a Bot is servicing several
            // anchors. The old message showed only the first coordinate,
            // which made a missing right-side anchor look as though it had
            // been supplied whenever any other anchor had spawned.
            if (spawnedLocations.size() < 12) {
                spawnedLocations.add(placement.output.name() + "@" + format(drop)
                        + " gap=" + openGap);
            }
        }
        if (spent > 0D) {
            prepaidDirty = true;
            debugOwner(session, "spawned-blocks", "amount", String.valueOf(spawned),
                    "placements", String.join("; ", spawnedLocations));
        }
    }

    /**
     * A Sand Bot fills only columns inside its owner faction's own claim.
     * The scan radius is a square around the Bot, so it routinely spills over
     * a claim border -- without this, a trigger block placed just outside
     * would still be filled, spending the owner's faction bank to build on
     * land the faction does not hold.
     *
     * <p>Cached per chunk rather than per block: claims are chunk-granular,
     * so one lookup covers every anchor in that chunk instead of repeating
     * the same query for each block in the radius on every tick.
     */
    private static boolean isOwnedClaim(Block anchor, SandBotSession session, Map<Long, Boolean> cache) {
        long chunkKey = ((long) (anchor.getX() >> 4) << 32) | ((anchor.getZ() >> 4) & 0xffffffffL);
        return cache.computeIfAbsent(chunkKey,
                ignored -> FactionsHook.getClaimFactionId(anchor.getLocation()) == session.factionId);
    }

    /** Returns empty, fillable blocks from directly below an anchor downward. */
    private static int openGapUnder(Block anchor, Material output) {
        World world = anchor.getWorld();
        int open = 0;
        for (int y = anchor.getY() - 1; y >= world.getMinHeight(); y--) {
            Block block = world.getBlockAt(anchor.getX(), y, anchor.getZ());
            // Fluids and other passable blocks are not valid deterministic
            // sand targets. Treating them as air lets fluid physics move the
            // falling block out of its column.
            if (block.getType() == output || !block.getType().isAir()) {
                return open;
            }
            open++;
        }
        return open;
    }

    private static int pendingCount(SandBotSession session, ColumnKey key) {
        Map<UUID, PendingPlacement> pending = session.pendingColumns.get(key);
        return pending == null ? 0 : pending.size();
    }

    /**
     * Releases a source column after its falling block has landed, disappeared,
     * fallen out of the source cell, or has been pushed out of that source
     * cell. A deep vertical gap must release the source too: retaining the
     * entry until the block lands made a 44-block drop delay the next output
     * for the entire fall. Releasing the tracking entry never removes the
     * entity itself, so earlier blocks continue through the cannon.
     */
    private static void reconcilePendingBlocks(SandBotSession session) {
        session.pendingColumns.entrySet().removeIf(columnEntry -> {
            ColumnKey key = columnEntry.getKey();
            Map<UUID, PendingPlacement> pending = columnEntry.getValue();
            pending.entrySet().removeIf(pendingEntry -> {
                org.bukkit.entity.Entity entity = Bukkit.getEntity(pendingEntry.getKey());
                if (entity == null || !entity.isValid() || entity.isDead() || !(entity instanceof FallingBlock)) {
                    return true;
                }
                Location location = entity.getLocation();
                boolean stillInSourceCell = location.getWorld() == session.world
                        && Math.abs(location.getX() - (key.x() + 0.5D)) <= 0.75D
                        && Math.abs(location.getZ() - (key.z() + 0.5D)) <= 0.75D
                        // New entities start in the block directly below an
                        // anchor (key.y() - 1). Once gravity carries them out
                        // of that cell, another entity may safely start at the
                        // source without deleting or replacing the first.
                        && location.getY() >= key.y() - 1.75D;
                return !stillInSourceCell;
            });
            return pending.isEmpty();
        });
    }

    private static void removePendingBlocks(SandBotSession session) {
        for (Map<UUID, PendingPlacement> pending : session.pendingColumns.values()) {
            for (UUID entityId : pending.keySet()) {
                org.bukkit.entity.Entity entity = Bukkit.getEntity(entityId);
                if (entity != null && entity.isValid()) {
                    entity.remove();
                }
            }
        }
        session.pendingColumns.clear();
    }

    public void destroy(SandBotSession session) {
        if (session == null) return;
        sessions.remove(session.npcId);
        removePendingBlocks(session);
        removeNpcOnly(session);
        refundPrepaidBalance(session);
        saveState();
    }

    /**
     * A prepaid balance belongs to the faction, never to the NPC.  Returning
     * it here means pausing or despawning a Bot cannot strand the amount that
     * was set aside to keep its physical output smooth. Callers save state
     * afterwards; until then the reserve is also recorded in {@code sandbots.yml}.
     */
    private void refundPrepaidBalance(SandBotSession session) {
        double refund = session.prepaidBalance;
        session.prepaidBalance = 0D;
        prepaidDirty = true;
        if (refund > 0.000001D && factionBankManager != null) {
            factionBankManager.depositMoney(session.factionId, refund);
        }
    }

    private void removeNpcOnly(SandBotSession session) {
        if (!isFancyNpcsAvailable()) return;
        FancyNpcsPlugin.get().getNpcManager().removeNpc(session.npc);
        session.npc.removeForAll();
    }

    private boolean isFancyNpcsAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")
                && FancyNpcsPlugin.get().getNpcManager().isLoaded();
    }

    private void setNpcStatus(Npc npc, boolean active) {
        String expected = messages.getRaw(null, active ? "sandbot.npc-active" : "sandbot.npc-paused");
        if (!expected.equals(npc.getData().getDisplayName())) {
            npc.getData().setDisplayName(expected);
            npc.updateForAll();
        }
    }

    private void notifyOwner(SandBotSession session, String messageKey) {
        Player owner = Bukkit.getPlayer(session.ownerId);
        if (owner != null && owner.isOnline() && messages != null) {
            owner.sendMessage(messages.get(owner, messageKey));
        }
    }

    private void warnLowBankIfNeeded(SandBotSession session, double balance) {
        long now = System.currentTimeMillis();
        if (balance >= lowBankWarningThreshold
                || now - session.lastLowBankWarningAt < lowBankWarningCooldownMillis) return;
        session.lastLowBankWarningAt = now;
        notifyFaction(session, "sandbot.low-bank",
                "amount", String.format(java.util.Locale.ROOT, "%,.2f", balance),
                "threshold", String.format(java.util.Locale.ROOT, "%,.2f", lowBankWarningThreshold));
    }

    private void pauseForFunds(SandBotSession session) {
        setActive(session, false);
        setNpcStatus(session.npc, false);
        notifyOwner(session, "sandbot.paused-out-of-funds");
    }

    private void notifyFaction(SandBotSession session, String key, String... placeholders) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (FactionsHook.getFactionId(online) == session.factionId) {
                online.sendMessage(messages.get(online, key, placeholders));
            }
        }
    }

    private void debugOwner(SandBotSession session, String key, String... placeholders) {
        Player owner = Bukkit.getPlayer(session.ownerId);
        if (owner != null && owner.isOnline()) {
            debug(owner, key, placeholders);
        }
    }

    private static String format(Location location) {
        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }

    public static final class SandBotSession {
        public final String npcId;
        private final Npc npc;
        public final UUID ownerId;
        public final int factionId;
        public final FactionData faction;
        public final World world;
        public final int centerX;
        public final int centerY;
        public final int centerZ;
        private final Map<ColumnKey, Map<UUID, PendingPlacement>> pendingColumns = new HashMap<>();
        private boolean prepaidFundingPending;
        /** Money already withdrawn from the faction bank for imminent output. */
        private double prepaidBalance;
        /** Next starting position for a fair, rotating placement pass. */
        private int nextPlacementCursor;
        public boolean active = true;
        private long lastLowBankWarningAt;

        SandBotSession(String npcId, Npc npc, UUID ownerId, int factionId, FactionData faction,
                World world, int centerX, int centerY, int centerZ) {
            this.npcId = npcId;
            this.npc = npc;
            this.ownerId = ownerId;
            this.factionId = factionId;
            this.faction = faction;
            this.world = world;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
        }
    }

    public static final class ControlPanelHolder implements InventoryHolder {
        private final String npcId;

        ControlPanelHolder(String npcId) {
            this.npcId = npcId;
        }

        public String npcId() {
            return npcId;
        }

        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    private record Column(Block anchor, Material output, ColumnKey key, int targetY) {
    }

    private record PaidPlacement(Block anchor, Material output, ColumnKey key, int targetY, double cost) { }

    private record PendingPlacement() { }

    private record ColumnKey(int x, int y, int z) { }

}
