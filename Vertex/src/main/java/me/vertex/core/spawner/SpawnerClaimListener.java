package me.vertex.core.spawner;

import dev.kitteh.factions.Faction;
import dev.kitteh.factions.event.FactionAutoDisbandEvent;
import dev.kitteh.factions.event.FactionDisbandEvent;
import dev.kitteh.factions.event.LandUnclaimAllEvent;
import dev.kitteh.factions.event.LandUnclaimEvent;
import dev.kitteh.factions.event.LandClaimEvent;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;

/**
 * Keeps spawners honest with faction land ownership: a chunk with active
 * spawners can't be casually /f unclaim'd away. An overclaim retains the
 * physical spawners and transfers their ownership to the new faction;
 * unclaim-all and disband still return spawners as items rather than
 * leaving them in unclaimed territory.
 */
public final class SpawnerClaimListener implements Listener {

    private final SpawnerManager spawnerManager;
    private final Messages messages;

    public SpawnerClaimListener(SpawnerManager spawnerManager, Messages messages) {
        this.spawnerManager = spawnerManager;
        this.messages = messages;
    }

    /**
     * Overclaiming keeps every placed spawner where it is. Its faction tag
     * moves to the faction that now owns the land, avoiding dropped items
     * and ensuring the old faction cannot later remove it on disband.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClaim(LandClaimEvent event) {
        if (event.isCancelled() || event.getFaction() == null) {
            return;
        }
        String claimingTag = event.getFaction().tag();
        for (Map.Entry<Location, SpawnerData> entry : spawnerManager.getSpawnersInChunk(event.getLocation().asChunk())) {
            spawnerManager.transferOwnership(entry.getKey(), claimingTag);
        }
    }

    /** Blocks unclaiming a single chunk outright while it has active spawners. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUnclaim(LandUnclaimEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Chunk chunk = event.getLocation().asChunk();
        if (spawnerManager.getSpawnersInChunk(chunk).isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getFPlayer().asPlayer();
        if (player != null) {
            player.sendMessage(messages.get(player, "spawner.unclaim-blocked"));
        }
    }

    /**
     * /f unclaimall isn't blocked -- it releases the whole faction's land
     * at once, and singling out which chunks to protect isn't practical --
     * but every spawner in the land being released still gets dropped
     * instead of left behind floating in an unclaimed chunk.
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
            player.sendMessage(messages.get(player, "spawner.unclaimall-warning", "amount", String.valueOf(dropped)));
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
            player.sendMessage(messages.get(player, "spawner.disband-warning", "amount", String.valueOf(dropped)));
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
        // Iterating the spawner index is linear in actual spawners instead.
        for (Map.Entry<Location, SpawnerData> entry : spawnerManager.getSpawnersOwnedBy(faction.tag())) {
            dropped += dropSpawner(entry.getKey(), entry.getValue());
        }
        return dropped;
    }

    private int dropSpawner(Location location, SpawnerData data) {
        SpawnerManager.MobConfig config = spawnerManager.getMobConfig(data.mobType());
        Component displayName = config != null ? MessageFormatter.deserialize(config.displayName())
                : Component.text(data.mobType().name());
        for (int i = 0; i < data.stackSize(); i++) {
            location.getWorld().dropItemNaturally(location.clone().add(0.5, 0.5, 0.5),
                    SpawnerManager.createSpawnerItem(data.mobType(), displayName));
        }
        spawnerManager.remove(location);
        if (location.getBlock().getType() == org.bukkit.Material.SPAWNER) {
            location.getBlock().setType(org.bukkit.Material.AIR);
        }
        return data.stackSize();
    }
}
