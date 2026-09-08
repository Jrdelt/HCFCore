package me.vertex.core.sandbot;

import dev.kitteh.factions.Faction;
import me.vertex.core.faction.FactionBankManager;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.shop.ShopManager;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SandBotManager {

    private final Plugin plugin;
    private final FactionBankManager factionBankManager;
    private final ShopManager shopManager;
    private final Messages messages;
    private final NamespacedKey itemTagKey;

    private volatile boolean enabled;
    private volatile int radiusBlocks;
    private volatile long tickIntervalTicks;
    private volatile int placementsPerColumnPerTick;
    private volatile int maxPlacementsPerTick;
    private final Map<String, SandBotSession> sessions = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> debugPlayers = ConcurrentHashMap.newKeySet();
    private BukkitTask tickTask;

    public SandBotManager(Plugin plugin, FactionBankManager factionBankManager, ShopManager shopManager,
            Messages messages, boolean enabled, int radiusBlocks, long tickIntervalTicks,
            int placementsPerColumnPerTick, int maxPlacementsPerTick,
            org.bukkit.entity.EntityType ignoredNpcType) {
        this.plugin = plugin;
        this.factionBankManager = factionBankManager;
        this.shopManager = shopManager;
        this.messages = messages;
        this.itemTagKey = new NamespacedKey(plugin, "sandbot-item");
        reconfigure(enabled, radiusBlocks, tickIntervalTicks, placementsPerColumnPerTick, maxPlacementsPerTick,
                ignoredNpcType);
    }

    public void reconfigure(boolean enabled, int radiusBlocks, long tickIntervalTicks,
            int placementsPerColumnPerTick, int maxPlacementsPerTick,
            org.bukkit.entity.EntityType ignoredNpcType) {
        long previousInterval = this.tickIntervalTicks;
        this.enabled = enabled;
        this.radiusBlocks = Math.max(1, Math.min(5, radiusBlocks));
        this.tickIntervalTicks = Math.max(1, tickIntervalTicks);
        this.placementsPerColumnPerTick = Math.max(1, Math.min(16, placementsPerColumnPerTick));
        this.maxPlacementsPerTick = Math.max(1, Math.min(200, maxPlacementsPerTick));
        // A reload must apply a changed interval immediately; the scheduler
        // otherwise continues using the period it was created with.
        if (tickTask != null && previousInterval != this.tickIntervalTicks) {
            start();
        }
    }

    public void start() {
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
        for (SandBotSession session : List.copyOf(sessions.values())) {
            destroy(session);
        }
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

        Component nameComp = messages != null ? messages.get(null, "sandbot.item.name") : Component.text("§eSand Bot");
        meta.displayName(nameComp.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("§7Place on any block -- scans for andesite,").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(Component.text("§7sandstone, red sandstone, or concrete").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        lore.add(Component.text("§7below it within range; must be in your claim.").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(lore);

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
        Faction faction = FactionsHook.getFactionById(factionId);
        if (faction == null) {
            return false;
        }

        String npcId = "vertex_sandbot_" + UUID.randomUUID();
        NpcData data = new NpcData(npcId, owner.getUniqueId(), spawnLocation);
        data.setDisplayName("<green>Sand Bot <gray>[Active]");
        data.setSkin(owner.getName());
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

        SandBotSession session = new SandBotSession(npcId, npc, owner.getUniqueId(), factionId, faction,
                spawnLocation.getWorld(), spawnLocation.getBlockX(),
                spawnLocation.getBlockY(), spawnLocation.getBlockZ());
        sessions.put(npcId, session);
        return true;
    }

    public SandBotSession getSessionByNpcId(String npcId) {
        return sessions.get(npcId);
    }

    public boolean toggleDebug(Player player) {
        if (debugPlayers.remove(player.getUniqueId())) {
            debug(player, "Diagnostics disabled.");
            return false;
        }
        debugPlayers.add(player.getUniqueId());
        debug(player, "Diagnostics enabled. Place a Bot, then right-click it; scan and payment results will appear here.");
        return true;
    }

    public void debug(Player player, String detail) {
        if (debugPlayers.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("§c§lSTAFF§r §7> §e[SAND BOT] §7" + detail));
        }
    }

    public void handleNpcClick(String npcId, Player player) {
        debug(player, "Received FancyNPCs click for " + npcId + ".");
        SandBotSession session = sessions.get(npcId);
        if (session == null) {
            debug(player, "No live Vertex session matches that NPC id.");
            return;
        }
        if (!player.getUniqueId().equals(session.ownerId) && !player.hasPermission("vertex.sandbot.admin")) {
            debug(player, "Click denied: you are not the owner or a Sand Bot admin.");
            player.sendMessage("§cYou do not own this Sand Bot.");
            return;
        }
        debug(player, "Opening control panel.");
        openControlPanel(player, session);
    }

    public void openControlPanel(Player player, SandBotSession session) {
        Component titleComp = messages != null ? messages.get(player, "sandbot.gui.title") : Component.text("Sand Bot Control Panel");
        String titleStr = PlainTextComponentSerializer.plainText().serialize(titleComp);
        Inventory gui = Bukkit.createInventory(new ControlPanelHolder(session.npcId), 27, titleStr.replace("&", "§"));

        Component statusComp = messages != null
                ? messages.get(player, session.active ? "sandbot.gui.active-status" : "sandbot.gui.paused-status")
                : Component.text(session.active ? "Status: Active" : "Status: Paused");

        ItemStack statusItem = new ItemStack(session.active ? Material.LIME_CONCRETE : Material.RED_CONCRETE);
        ItemMeta statusMeta = statusItem.getItemMeta();
        statusMeta.displayName(statusComp.decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        statusItem.setItemMeta(statusMeta);
        gui.setItem(11, statusItem);

        Component despawnComp = messages != null ? messages.get(player, "sandbot.gui.despawn") : Component.text("Despawn Sand Bot");
        ItemStack despawnItem = new ItemStack(Material.BARRIER);
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

        session.pendingColumns.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(entityId -> {
                org.bukkit.entity.Entity entity = Bukkit.getEntity(entityId);
                return entity == null || !entity.isValid() || entity.isDead();
            });
            return entry.getValue().isEmpty();
        });

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
                int pending = session.pendingColumns.getOrDefault(key, Set.of()).size();
                int needed = openGap - pending;
                if (needed > 0) {
                    queue.add(new Column(anchor, output, key, needed, pending));
                }
            }
        }

        if (queue.isEmpty()) {
            // A filled column is an idle state, not completion. Keep the Bot
            // visible and let it resume automatically if falling blocks are
            // removed or a new anchor/gap appears in its scan radius.
            if (session.pendingColumns.isEmpty()) {
                debugOwner(session, "Idle: anchors=" + anchors + ", complete=" + completeColumns
                        + ", blocked=" + blockedColumns + ", outside-claim=" + unclaimedColumns
                        + ", ready=0 at Y=" + floorY + ".");
            }
            return;
        }

        int placementBudget = maxPlacementsPerTick;
        for (Column column : queue) {
            if (placementBudget == 0) {
                break;
            }
            if (!shopManager.isTradeable(column.output)) {
                debugOwner(session, "Skipped " + column.output + " at " + format(column.anchor.getLocation())
                        + ": it has no tradeable Shop entry.");
                continue;
            }
            int placements = Math.min(Math.min(placementsPerColumnPerTick, column.needed), placementBudget);
            double cost = shopManager.buyPrice(column.output);
            for (int placement = 0; placement < placements; placement++) {
                if (!charge(session.faction, session.factionId, cost)) {
                    debugOwner(session, "Stopped: faction cannot pay " + cost + " for " + column.output + ".");
                    notifyOwner(session, "sandbot.out-of-funds");
                    destroy(session);
                    return;
                }

                // Anchors remain in place. Multiple falling blocks may be in
                // flight for the same deep column, but never more than its
                // remaining empty space, so the column cannot overfill.
                Location dropLocation = column.anchor.getLocation().add(0.5, -1, 0.5);
                FallingBlock fallingBlock = world.spawn(dropLocation, FallingBlock.class,
                        entity -> entity.setBlockData(column.output.createBlockData()));
                fallingBlock.setDropItem(false);
                session.pendingColumns.computeIfAbsent(column.key, ignored -> new HashSet<>())
                        .add(fallingBlock.getUniqueId());
                placementBudget--;
            }
            debugOwner(session, "Spawned " + placements + "x " + column.output + " below "
                    + format(column.anchor.getLocation()) + " (gap=" + (column.needed + column.pending)
                    + ", in-flight=" + (column.pending + placements) + ").");
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
            if (block.getType() == output || !block.isPassable()) {
                return open;
            }
            open++;
        }
        return open;
    }

    private boolean charge(Faction faction, int factionId, double cost) {
        if (FactionsHook.hasFactionMoney(faction, cost)) {
            return FactionsHook.withdrawFactionMoney(faction, cost);
        }
        if (factionBankManager != null && factionBankManager.money(factionId) >= cost) {
            factionBankManager.withdrawMoney(factionId, cost);
            return true;
        }
        return false;
    }

    public void destroy(SandBotSession session) {
        sessions.remove(session.npcId);
        FancyNpcsPlugin.get().getNpcManager().removeNpc(session.npc);
        session.npc.removeForAll();
    }

    private boolean isFancyNpcsAvailable() {
        return Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")
                && FancyNpcsPlugin.get().getNpcManager().isLoaded();
    }

    private static void setNpcStatus(Npc npc, boolean active) {
        String expected = active ? "<green>Sand Bot <gray>[Active]" : "<red>Sand Bot <gray>[Paused]";
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

    private void debugOwner(SandBotSession session, String detail) {
        Player owner = Bukkit.getPlayer(session.ownerId);
        if (owner != null && owner.isOnline()) {
            debug(owner, detail);
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
        public final Faction faction;
        public final World world;
        public final int centerX;
        public final int centerY;
        public final int centerZ;
        private final Map<ColumnKey, Set<UUID>> pendingColumns = new HashMap<>();
        public boolean active = true;

        SandBotSession(String npcId, Npc npc, UUID ownerId, int factionId, Faction faction,
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

    private record Column(Block anchor, Material output, ColumnKey key, int needed, int pending) {
    }

    private record ColumnKey(int x, int y, int z) {
    }

}
