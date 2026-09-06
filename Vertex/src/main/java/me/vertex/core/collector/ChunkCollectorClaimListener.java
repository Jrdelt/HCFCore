package me.vertex.core.collector;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.event.FactionAutoDisbandEvent;
import dev.kitteh.factions.event.FactionDisbandEvent;
import dev.kitteh.factions.event.LandClaimEvent;
import dev.kitteh.factions.event.LandUnclaimAllEvent;
import dev.kitteh.factions.event.LandUnclaimEvent;
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
    public void onClaim(LandClaimEvent event) {
        if (event.isCancelled() || event.getFaction() == null) {
            return;
        }
        String claimingTag = event.getFaction().tag();
        for (Map.Entry<Location, ChunkCollectorData> entry : manager.getCollectorsInChunk(event.getLocation().asChunk())) {
            manager.transferFactionOwnership(entry.getKey(), claimingTag);
        }
    }

    /** Blocks unclaiming a single chunk outright while it has an active Collector. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUnclaim(LandUnclaimEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Chunk chunk = event.getLocation().asChunk();
        if (manager.getCollectorsInChunk(chunk).isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getFPlayer().asPlayer();
        if (player != null) {
            player.sendMessage(messages.get(player, "collector.unclaim-blocked"));
        }
    }

    /**
     * /f unclaimall isn't blocked -- it releases the whole faction's land at
     * once, and singling out which chunks to protect isn't practical -- but
     * every Collector in the land being released still gets dropped instead
     * of left behind in an unclaimed chunk.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnclaimAll(LandUnclaimAllEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Faction faction = event.getFPlayer().faction();
        int dropped = dropAllInFactionClaims(faction);
        if (dropped <= 0) {
            return;
        }
        Player player = event.getFPlayer().asPlayer();
        if (player != null) {
            player.sendMessage(messages.get(player, "collector.unclaimall-warning", "amount", String.valueOf(dropped)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(FactionDisbandEvent event) {
        if (event.isCancelled()) {
            return;
        }
        int dropped = dropAllInFactionClaims(event.getFaction());
        if (dropped <= 0) {
            return;
        }
        Player player = event.getFPlayer().asPlayer();
        if (player != null) {
            player.sendMessage(messages.get(player, "collector.disband-warning", "amount", String.valueOf(dropped)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAutoDisband(FactionAutoDisbandEvent event) {
        dropAllInFactionClaims(event.getFaction());
    }

    private int dropAllInFactionClaims(Faction faction) {
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
