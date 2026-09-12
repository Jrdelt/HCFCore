package me.vertex.core.factions.event;

import me.vertex.core.claims.ChunkKey;
import me.vertex.core.factions.FactionData;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired before Vertex changes ownership of one claimed chunk. */
public final class FactionClaimEvent extends Event implements Cancellable {
    /**
     * {@link #EXPIRE} is the non-player removal path used when a timed Raid
     * Claim reaches its stored deadline. Listeners must release any
     * claim-bound state on this action instead of cancelling it: an expired
     * claim cannot be kept alive by a placed block.
     */
    public enum Action {
        /** A claim was durably stored and is now visible to integrations. */
        CLAIM,
        /** Pre-commit player unclaim. This is the only cancellable removal action. */
        UNCLAIM,
        /** A player unclaim was durably committed and is now visible to integrations. */
        UNCLAIMED,
        /** A timed Raid Claim was durably removed after reaching its deadline. */
        EXPIRE
    }
    private static final HandlerList HANDLERS = new HandlerList();
    private final Action action; private final FactionData faction; private final Player actor; private final ChunkKey chunk;
    private final Integer previousOwnerId; private final long raidExpiresAt; private boolean cancelled;
    public FactionClaimEvent(Action action, FactionData faction, Player actor, ChunkKey chunk) {
        this(action, faction, actor, chunk, null, 0L);
    }
    public FactionClaimEvent(Action action, FactionData faction, Player actor, ChunkKey chunk,
            Integer previousOwnerId, long raidExpiresAt) {
        this.action=action; this.faction=faction; this.actor=actor; this.chunk=chunk;
        this.previousOwnerId=previousOwnerId; this.raidExpiresAt=raidExpiresAt;
    }
    public Action action() { return action; } public FactionData faction() { return faction; } public Player actor() { return actor; } public ChunkKey chunk() { return chunk; }
    /** Previous durable owner for an overclaim; null for Wilderness. */
    public Integer previousOwnerId() { return previousOwnerId; }
    /** Default Raid deadline committed atomically with a normal claim. */
    public long raidExpiresAt() { return raidExpiresAt; }
    @Override public boolean isCancelled() { return cancelled; } @Override public void setCancelled(boolean value) { cancelled=value; }
    @Override public HandlerList getHandlers() { return HANDLERS; } public static HandlerList getHandlerList() { return HANDLERS; }
}
