package me.vertex.core.collector;

import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.event.FactionClaimEvent;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import me.vertex.core.factions.event.FactionUnclaimAllEvent;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;

/**
 * Keeps Chunk Collectors honest with faction land ownership, the same way
 * {@code SpawnerClaimListener} does for spawners: a chunk with an active
 * Collector can't be casually /f unclaim'd away, an overclaim keeps the
 * physical block and just changes who can open it, and unclaim-all/disband
 * return every Collector as an item rather than stranding it in unclaimed
 * land with a faction tag nobody can act on anymore.
 */
public final class ChunkCollectorClaimListener implements Listener {
    private final ChunkCollectorManager manager;
    private final Messages messages;

    public ChunkCollectorClaimListener(ChunkCollectorManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClaim(FactionClaimEvent event) {
        if (event.action() != FactionClaimEvent.Action.CLAIM || event.faction().system()) return;
        String claimingTag = event.faction().tag();
        org.bukkit.World world = event.chunk().isLocalShard() ? Bukkit.getWorld(event.chunk().localWorld()) : null;
        if (world == null) return;
        for (Map.Entry<Location, ChunkCollectorData> entry : manager.getCollectorsInChunk(world,event.chunk().x(),event.chunk().z())) {
            manager.transferFactionOwnership(entry.getKey(), claimingTag);
        }
    }

    /** Blocks unclaiming a single chunk outright while it has an active Collector. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUnclaim(FactionClaimEvent event) {
        if (event.action() != FactionClaimEvent.Action.UNCLAIM) return;
        org.bukkit.World world = event.chunk().isLocalShard() ? Bukkit.getWorld(event.chunk().localWorld()) : null;
        if (world == null) return;
        if (!manager.hasCollectorInChunk(world,event.chunk().x(),event.chunk().z())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.actor();
        if (player != null) {
            player.sendMessage(messages.get(player, "collector.unclaim-blocked"));
        }
    }

    /** Timed raid land must expire even when it contains a collector. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onExpire(FactionClaimEvent event) {
        if (event.action() != FactionClaimEvent.Action.EXPIRE) return;
        org.bukkit.World world = event.chunk().isLocalShard() ? Bukkit.getWorld(event.chunk().localWorld()) : null;
        if (world == null) return;
        for (Map.Entry<Location, ChunkCollectorData> entry : manager.getCollectorsInChunk(world,event.chunk().x(),event.chunk().z())) {
            dropCollector(entry.getKey(), entry.getValue());
        }
    }

    /**
     * /f unclaimall isn't blocked -- it releases the whole faction's land at
     * once, and singling out which chunks to protect isn't practical -- but
     * every Collector in the land being released still gets dropped instead
     * of left behind in an unclaimed chunk.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnclaimAll(FactionUnclaimAllEvent event) {
        int dropped = dropAllInFactionClaims(event.faction());
        if (dropped <= 0) {
            return;
        }
        Player player = event.actor();
        if (player != null) {
            player.sendMessage(messages.get(player, "collector.unclaimall-warning", "amount", String.valueOf(dropped)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(FactionLifecycleEvent event) {
        if (event.action() != FactionLifecycleEvent.Action.DISBAND) return;
        int dropped = dropAllInFactionClaims(event.faction());
        if (dropped <= 0) {
            return;
        }
        Player player = event.actor();
        if (player != null) {
            player.sendMessage(messages.get(player, "collector.disband-warning", "amount", String.valueOf(dropped)));
        }
    }

    private int dropAllInFactionClaims(FactionData faction) {
        if (faction == null) {
            return 0;
        }
        int dropped = 0;
        // Do not enumerate claims: FLocation.asChunk() calls
        // World#getChunkAt(), which synchronously loads every claimed chunk.
        // Iterating the collector index is linear in actual Collectors instead.
        for (Map.Entry<Location, ChunkCollectorData> entry : manager.getCollectorsOwnedBy(faction.tag())) {
            dropCollector(entry.getKey(), entry.getValue());
            dropped++;
        }
        return dropped;
    }

    private void dropCollector(Location location, ChunkCollectorData data) {
        location.getWorld().dropItemNaturally(location.clone().add(0.5, 0.5, 0.5),
                manager.createCollectorItem(manager.displayName(Bukkit.getConsoleSender()), data));
        manager.unregister(location);
        if (location.getBlock().getType() == Material.GREEN_SHULKER_BOX) {
            location.getBlock().setType(Material.AIR);
        }
    }
}
