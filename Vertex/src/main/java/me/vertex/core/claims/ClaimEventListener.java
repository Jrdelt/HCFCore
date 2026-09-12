package me.vertex.core.claims;

import me.vertex.core.factions.event.FactionClaimEvent;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import me.vertex.core.factions.event.FactionUnclaimAllEvent;
import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps {@link BaseClaimManager}/{@link RaidClaimManager} in sync with live
 * Vertex claim state. A new faction claim either joins an existing
 * Base Claim region (if it touches one and that region has room) or starts
 * a fresh Raid Claim expiration timer; an unclaim just stops tracking it
 * for Raid Claim purposes (Base Claim region membership deliberately
 * survives an unclaim -- see {@code ClaimStorage}'s region-chunks table doc).
 */
public final class ClaimEventListener implements Listener {

    private final Plugin plugin;
    private final BaseClaimManager baseClaims;
    private final RaidClaimManager raidClaims;
    private final Messages messages;

    private final Object writeLock=new Object();
    private CompletableFuture<Void> writeTail=CompletableFuture.completedFuture(null);

    public ClaimEventListener(Plugin plugin, BaseClaimManager baseClaims, RaidClaimManager raidClaims, Messages messages) {
        this.plugin=plugin;
        this.baseClaims = baseClaims;
        this.raidClaims = raidClaims;
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = true)
    public void onClaim(FactionClaimEvent event) {
        if (event.action() != FactionClaimEvent.Action.CLAIM) return;
        ChunkKey chunk = event.chunk();
        int factionId=event.faction().id();boolean system=event.faction().system();Player actor=event.actor();
        submit(()->{
            Integer previousOwner=event.previousOwnerId();
            if(previousOwner!=null&&previousOwner!=factionId){
                raidClaims.forget(chunk);
                baseClaims.handleUnclaimed(previousOwner,chunk);
            }
            if(system){raidClaims.forget(chunk);return;}
            BaseClaimManager.ConnectResult result=baseClaims.tryConnect(factionId,chunk);
            if(result==BaseClaimManager.ConnectResult.JOINED){raidClaims.forget(chunk);return;}
            if(event.raidExpiresAt()>0L)raidClaims.adoptPreclassified(factionId,chunk,event.raidExpiresAt());
            else raidClaims.track(factionId,chunk);
            if(actor!=null&&(result==BaseClaimManager.ConnectResult.REGION_FULL
                    ||result==BaseClaimManager.ConnectResult.PERSIST_FAILED)){
                Bukkit.getScheduler().runTask(plugin,()->{
                    if(!actor.isOnline())return;
                    if(result==BaseClaimManager.ConnectResult.REGION_FULL)actor.sendMessage(messages.get(actor,
                            "baseclaim.region-full","limit",String.valueOf(baseClaims.maxChunksPerRegion())));
                    else actor.sendMessage(messages.get(actor,"baseclaim.persist-failed"));
                });
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onUnclaim(FactionClaimEvent event) {
        if (event.action() == FactionClaimEvent.Action.UNCLAIMED || event.action() == FactionClaimEvent.Action.EXPIRE) {
            ChunkKey chunk=event.chunk();int factionId=event.faction().id();submit(()->{
                raidClaims.forget(chunk);
                baseClaims.handleUnclaimed(factionId, chunk);
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onUnclaimAll(FactionUnclaimAllEvent event) {
        int factionId=event.faction().id();submit(()->{
            raidClaims.forgetFaction(factionId);
            baseClaims.forgetFactionRegions(factionId);
        });
    }

    @EventHandler
    public void onFactionLifecycle(FactionLifecycleEvent event) {
        if (event.action() == FactionLifecycleEvent.Action.DISBAND) {
            int factionId=event.faction().id();submit(()->baseClaims.deleteFactionData(factionId));
        }
    }

    private void submit(Runnable operation){synchronized(writeLock){writeTail=writeTail.handle((ignored,error)->null).thenRunAsync(operation);}}
    public void awaitWrites(){CompletableFuture<Void> tail;synchronized(writeLock){tail=writeTail;}try{tail.get(10,TimeUnit.SECONDS);}catch(Exception error){plugin.getLogger().log(java.util.logging.Level.WARNING,"Timed out waiting for claim metadata writes.",error);}}
}
