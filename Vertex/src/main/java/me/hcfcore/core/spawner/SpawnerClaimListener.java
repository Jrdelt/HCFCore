package me.hcfcore.core.spawner;

import dev.kitteh.factions.Board;
import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.event.FactionAutoDisbandEvent;
import dev.kitteh.factions.event.FactionDisbandEvent;
import dev.kitteh.factions.event.LandClaimEvent;
import dev.kitteh.factions.event.LandUnclaimAllEvent;
import dev.kitteh.factions.event.LandUnclaimEvent;
import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
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
 * spawners can't be casually /f unclaim'd away, and land that stops being
 * yours -- overclaimed, unclaimed entirely via /f unclaimall, or freed by a
 * disband -- drops every spawner in it as items rather than leaving them
 * floating around still tracked against nobody's claim.
 */
public final class SpawnerClaimListener implements Listener {

    private final SpawnerManager spawnerManager;
    private final Messages messages;

    public SpawnerClaimListener(SpawnerManager spawnerManager, Messages messages) {
        this.spawnerManager = spawnerManager;
        this.messages = messages;
    }

    /**
     * A chunk being overclaimed out from under its actual owner drops
     * whatever spawners were tracked there -- the land is changing hands,
     * so whoever placed them no longer controls it. A faction re-claiming
     * land that was already theirs (re-running /f claim, claim-filling,
     * etc.) must NOT trigger this -- there's no ownership change to react
     * to, so this only drops a spawner whose recorded owner tag actually
     * differs from the faction claiming right now.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClaim(LandClaimEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Chunk chunk = event.getLocation().asChunk();
        String claimingTag = event.getFaction() == null ? null : event.getFaction().tag();
        dropOverclaimedIn(chunk, claimingTag);
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
        dropAllInFactionClaims(event.getFaction());
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
        for (FLocation claim : Board.board().allClaims(faction)) {
            dropped += dropAllIn(claim.asChunk());
        }
        return dropped;
    }

    /** @return how many individual spawners (not stacks) were dropped. */
    private int dropAllIn(Chunk chunk) {
        int totalDropped = 0;
        for (Map.Entry<Location, SpawnerData> entry : spawnerManager.getSpawnersInChunk(chunk)) {
            totalDropped += dropSpawner(entry.getKey(), entry.getValue());
        }
        return totalDropped;
    }

    /**
     * Like dropAllIn, but only for spawners whose recorded owner tag
     * doesn't match the faction now claiming the chunk -- see onClaim's
     * javadoc for why this distinction matters.
     */
    private int dropOverclaimedIn(Chunk chunk, String claimingFactionTag) {
        int totalDropped = 0;
        for (Map.Entry<Location, SpawnerData> entry : spawnerManager.getSpawnersInChunk(chunk)) {
            SpawnerData data = entry.getValue();
            String ownerTag = data.ownerFactionTag();
            if (ownerTag != null && claimingFactionTag != null && ownerTag.equalsIgnoreCase(claimingFactionTag)) {
                continue;
            }
            totalDropped += dropSpawner(entry.getKey(), data);
        }
        return totalDropped;
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
