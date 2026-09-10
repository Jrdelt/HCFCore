package me.vertex.core.claims;

import dev.kitteh.factions.Board;
import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.event.LandClaimEvent;
import dev.kitteh.factions.event.LandUnclaimAllEvent;
import dev.kitteh.factions.event.LandUnclaimEvent;
import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Keeps {@link BaseClaimManager}/{@link RaidClaimManager} in sync with live
 * FactionsUUID claim state. A new faction claim either joins an existing
 * Base Claim region (if it touches one and that region has room) or starts
 * a fresh Raid Claim expiration timer; an unclaim just stops tracking it
 * for Raid Claim purposes (Base Claim region membership deliberately
 * survives an unclaim -- see {@code ClaimStorage}'s region-chunks table doc).
 */
public final class ClaimEventListener implements Listener {

    private final BaseClaimManager baseClaims;
    private final RaidClaimManager raidClaims;
    private final Messages messages;

    public ClaimEventListener(BaseClaimManager baseClaims, RaidClaimManager raidClaims, Messages messages) {
        this.baseClaims = baseClaims;
        this.raidClaims = raidClaims;
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClaim(LandClaimEvent event) {
        Faction faction = event.getFaction();
        if (faction == null || faction.isWilderness() || !faction.isNormal()) {
            return;
        }
        ChunkKey chunk = toChunkKey(event.getLocation());
        BaseClaimManager.ConnectResult result = baseClaims.tryConnect(faction.id(), chunk);
        if (result == BaseClaimManager.ConnectResult.JOINED) {
            raidClaims.untrack(chunk);
            return;
        }
        if (result == BaseClaimManager.ConnectResult.REGION_FULL) {
            Player player = event.getFPlayer() == null || !event.getFPlayer().isOnline()
                    ? null : event.getFPlayer().asPlayer();
            if (player != null) {
                player.sendMessage(messages.get(player, "baseclaim.region-full",
                        "limit", String.valueOf(baseClaims.maxChunksPerRegion())));
            }
        }
        // Not connected to any Base Claim region -- track as a Raid Claim.
        // Deliberately not logged anywhere -- see RaidClaimManager's class doc.
        raidClaims.track(faction.id(), chunk);
    }

    @EventHandler(ignoreCancelled = true)
    public void onUnclaim(LandUnclaimEvent event) {
        raidClaims.untrack(toChunkKey(event.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onUnclaimAll(LandUnclaimAllEvent event) {
        Faction faction = event.getFaction();
        if (faction == null) {
            return;
        }
        for (FLocation location : Board.board().allClaims(faction)) {
            raidClaims.untrack(new ChunkKey(location.worldName(), location.x(), location.z()));
        }
    }

    private static ChunkKey toChunkKey(FLocation location) {
        return new ChunkKey(location.worldName(), location.x(), location.z());
    }
}
