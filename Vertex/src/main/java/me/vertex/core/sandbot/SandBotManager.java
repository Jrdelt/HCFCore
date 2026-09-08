package me.vertex.core.sandbot;

import dev.kitteh.factions.Faction;
import me.vertex.core.faction.FactionBankManager;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.shop.ShopManager;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sand Bots: a placed, Citizens-backed NPC that converts one of four
 * "trigger" building blocks (andesite/sandstone/red sandstone/any-color
 * concrete) into its falling-block equivalent (gravel/sand/red
 * sand/matching concrete powder) across a flat NxN footprint centered on
 * where it's placed, paid for per block from the owning faction's money.
 *
 * <p>Each qualifying column is converted exactly once -- the bot removes
 * the trigger block and spawns a falling-block entity in its place, which
 * then free-falls under normal vanilla gravity to wherever solid ground
 * stops it (bedrock, terrain, or an earlier drop from the same run). A
 * session finishes and despawns once a full pass finds no more trigger
 * blocks in range, or once neither the faction's native economy nor its
 * Vertex bank can cover the next block.
 */
public final class SandBotManager {

    private final Plugin plugin;
    private final FactionBankManager factionBankManager;
    private final ShopManager shopManager;
    private final Messages messages;
    private final NamespacedKey itemTagKey;

    private volatile boolean enabled;
    private volatile int radiusBlocks;
    private volatile long tickIntervalTicks;
    private volatile EntityType npcType;

    private final Map<Integer, SandBotSession> sessions = new ConcurrentHashMap<>();
    private BukkitTask tickTask;

    public SandBotManager(Plugin plugin, FactionBankManager factionBankManager, ShopManager shopManager,
                           Messages messages, boolean enabled, int radiusBlocks, long tickIntervalTicks,
                           EntityType npcType) {
        this.plugin = plugin;
        this.factionBankManager = factionBankManager;
        this.shopManager = shopManager;
        this.messages = messages;
        this.itemTagKey = new NamespacedKey(plugin, "sandbot-item");
        reconfigure(enabled, radiusBlocks, tickIntervalTicks, npcType);
    }

    public void reconfigure(boolean enabled, int radiusBlocks, long tickIntervalTicks, EntityType npcType) {
        this.enabled = enabled;
        // Clamped well below anything that could turn one placement into a
        // server-noticeable scan/conversion burst -- radius 5 is already a
        // shy-of-11x11 footprint.
        this.radiusBlocks = Math.max(1, Math.min(5, radiusBlocks));
        this.tickIntervalTicks = Math.max(1, tickIntervalTicks);
        this.npcType = npcType != null && npcType.isAlive() ? npcType : EntityType.PLAYER;
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

    /** Only ANDESITE, SANDSTONE, RED_SANDSTONE, and the 16 *_CONCRETE colors qualify. */
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
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(net.kyori.adventure.text.Component.text("Sand Bot")
                .color(net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(List.of(
                net.kyori.adventure.text.Component.text("Place on top of andesite, sandstone,")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("red sandstone, or any concrete --")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                net.kyori.adventure.text.Component.text("must be in your own faction's claim.")
                        .color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(itemTagKey, PersistentDataType.BOOLEAN, true);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isGiveItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemTagKey, PersistentDataType.BOOLEAN);
    }

    /**
     * Spawns a bot at {@code spawnLocation}, standing on {@code triggerBlock}.
     * Caller is responsible for every gate (permission, own-faction claim,
     * trigger material) before calling this -- it only refuses if Citizens
     * itself isn't available or the spawn fails.
     */
    public boolean spawn(Player owner, Block triggerBlock, Location spawnLocation) {
        if (!CitizensAPI.hasImplementation()) {
            return false;
        }
        Material triggerMaterial = triggerBlock.getType();
        Material outputMaterial = outputFor(triggerMaterial);
        if (outputMaterial == null || !shopManager.isTradeable(outputMaterial)) {
            return false;
        }
        int factionId = FactionsHook.getFactionId(owner);
        Faction faction = FactionsHook.getFactionById(factionId);
        if (faction == null) {
            return false;
        }

        NPC npc = CitizensAPI.getNPCRegistry().createNPC(npcType, "Sand Bot");
        npc.setProtected(true);
        if (!npc.spawn(spawnLocation)) {
            npc.destroy();
            return false;
        }

        SandBotSession session = new SandBotSession(npc.getId(), owner.getUniqueId(), factionId, faction,
                triggerMaterial, outputMaterial, spawnLocation.getWorld(), spawnLocation.getBlockX(),
                spawnLocation.getBlockY(), spawnLocation.getBlockZ());
        sessions.put(npc.getId(), session);
        return true;
    }

    /** Stops and despawns every active session owned by {@code ownerId}. Returns how many were stopped. */
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
        NPC npc = CitizensAPI.getNPCRegistry().getById(session.npcId);
        if (npc == null || !npc.isSpawned()) {
            sessions.remove(session.npcId);
            return;
        }

        World world = session.world;
        int floorY = session.centerY - 1;
        List<Block> queue = new ArrayList<>();
        for (int dx = -radiusBlocks; dx <= radiusBlocks; dx++) {
            for (int dz = -radiusBlocks; dz <= radiusBlocks; dz++) {
                Block block = world.getBlockAt(session.centerX + dx, floorY, session.centerZ + dz);
                if (block.getType() == session.triggerMaterial) {
                    queue.add(block);
                }
            }
        }

        if (queue.isEmpty()) {
            notifyOwner(session, "sandbot.finished");
            destroy(session);
            return;
        }

        if (!shopManager.isTradeable(session.outputMaterial)) {
            // No Shop entry (removed from shop.yml since spawn(), or never
            // added for a newer Minecraft concrete color) means buyPrice()
            // would silently return 0 -- refuse rather than convert for
            // free.
            notifyOwner(session, "sandbot.out-of-funds");
            destroy(session);
            return;
        }

        for (Block block : queue) {
            // Re-priced per block, not once for the whole tick's batch --
            // matches how ShopManager.totalBuyCost prices a real /shop buy,
            // so a batch under dynamic pricing (if ever enabled for one of
            // these materials) can't undercharge by billing every block at
            // the tick's starting price.
            double cost = shopManager.buyPrice(session.outputMaterial);
            if (!charge(session.faction, session.factionId, cost)) {
                notifyOwner(session, "sandbot.out-of-funds");
                destroy(session);
                return;
            }
            Location dropLocation = block.getLocation().add(0.5, 0, 0.5);
            block.setType(Material.AIR);
            world.spawn(dropLocation, FallingBlock.class,
                    fallingBlock -> fallingBlock.setBlockData(session.outputMaterial.createBlockData()));
        }
    }

    /** Native /f money first, Vertex's own /fbank as fallback -- see FactionsHook for why these are separate. */
    private boolean charge(Faction faction, int factionId, double cost) {
        if (FactionsHook.hasFactionMoney(faction, cost)) {
            return FactionsHook.withdrawFactionMoney(faction, cost);
        }
        if (factionBankManager != null && factionBankManager.money(factionId) >= cost) {
            // Fire-and-forget is safe here: the synchronous balance check
            // just above already confirmed the funds, and this manager is
            // only ever mutated from the main thread.
            factionBankManager.withdrawMoney(factionId, cost);
            return true;
        }
        return false;
    }

    private void notifyOwner(SandBotSession session, String messageKey) {
        Player owner = Bukkit.getPlayer(session.ownerId);
        if (owner != null && messages != null) {
            owner.sendMessage(messages.get(owner, messageKey));
        }
    }

    private void destroy(SandBotSession session) {
        sessions.remove(session.npcId);
        NPC npc = CitizensAPI.getNPCRegistry().getById(session.npcId);
        if (npc != null) {
            npc.destroy();
        }
    }

    private static final class SandBotSession {
        final int npcId;
        final UUID ownerId;
        final int factionId;
        final Faction faction;
        final Material triggerMaterial;
        final Material outputMaterial;
        final World world;
        final int centerX;
        final int centerY;
        final int centerZ;

        SandBotSession(int npcId, UUID ownerId, int factionId, Faction faction, Material triggerMaterial,
                       Material outputMaterial, World world, int centerX, int centerY, int centerZ) {
            this.npcId = npcId;
            this.ownerId = ownerId;
            this.factionId = factionId;
            this.faction = faction;
            this.triggerMaterial = triggerMaterial;
            this.outputMaterial = outputMaterial;
            this.world = world;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
        }
    }
}
