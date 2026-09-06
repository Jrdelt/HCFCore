package me.vertex.core.collector;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.event.FactionAutoDisbandEvent;
import dev.kitteh.factions.event.FactionDisbandEvent;
import dev.kitteh.factions.event.LandClaimEvent;
import dev.kitteh.factions.event.LandUnclaimAllEvent;
import dev.kitteh.factions.event.LandUnclaimEvent;
import me.vertex.core.lang.Messages;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;

/** Applies faction land changes to collectors exactly as it does to spawners. */
public final class ChunkCollectorClaimListener implements Listener {
    private final ChunkCollectorManager manager;
    private final Messages messages;

    public ChunkCollectorClaimListener(ChunkCollectorManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClaim(LandClaimEvent event) {
        if (event.isCancelled()) {
            return;
        }
        String claimingTag = event.getFaction() == null ? null : event.getFaction().tag();
        for (Map.Entry<Location, ChunkCollectorData> entry : manager.getCollectorsInChunk(event.getLocation().asChunk())) {
            String ownerTag = entry.getValue().ownerFactionTag();
            // Pre-ownership collectors cannot be safely identified as an
            // overclaim, so never destroy legacy player property on a normal
            // same-faction claim refresh.
            if (ownerTag != null && (claimingTag == null || !ownerTag.equalsIgnoreCase(claimingTag))) {
                drop(entry.getKey(), entry.getValue());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUnclaim(LandUnclaimEvent event) {
        if (event.isCancelled() || manager.getCollectorsInChunk(event.getLocation().asChunk()).isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getFPlayer().asPlayer();
        if (player != null) {
            player.sendMessage(messages.get(player, "collector.unclaim-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnclaimAll(LandUnclaimAllEvent event) {
        if (event.isCancelled()) {
            return;
        }
        int dropped = dropAll(event.getFPlayer().faction());
        Player player = event.getFPlayer().asPlayer();
        if (dropped > 0 && player != null) {
            player.sendMessage(messages.get(player, "collector.unclaimall-warning", "amount", String.valueOf(dropped)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDisband(FactionDisbandEvent event) {
        if (!event.isCancelled()) {
            int dropped = dropAll(event.getFaction());
            Player player = event.getFPlayer().asPlayer();
            if (dropped > 0 && player != null) {
                player.sendMessage(messages.get(player, "collector.disband-warning", "amount", String.valueOf(dropped)));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAutoDisband(FactionAutoDisbandEvent event) {
        dropAll(event.getFaction());
    }

    private int dropAll(Faction faction) {
        if (faction == null) {
            return 0;
        }
        int dropped = 0;
        // Indexed collectors only: do not force every faction claim chunk to
        // load just to discover that it contains nothing.
        for (Map.Entry<Location, ChunkCollectorData> entry : manager.getCollectorsOwnedBy(faction.tag())) {
            drop(entry.getKey(), entry.getValue());
            dropped++;
        }
        return dropped;
    }

    private void drop(Location location, ChunkCollectorData data) {
        location.getWorld().dropItemNaturally(location.clone().add(.5, .5, .5),
                manager.createCollectorItem(manager.displayName(org.bukkit.Bukkit.getConsoleSender()), data));
        manager.unregister(location);
        if (location.getBlock().getType() == Material.GREEN_SHULKER_BOX) {
            location.getBlock().setType(Material.AIR);
        }
    }
}
