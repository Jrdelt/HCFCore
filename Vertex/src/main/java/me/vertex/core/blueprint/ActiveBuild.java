package me.vertex.core.blueprint;

import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.UUID;

/**
 * Runtime state for one in-progress (or paused-across-restart) build. The
 * flattened block list is rebuilt from its private snapshot on load, rather
 * than persisted; only {@code currentIndex} is durable
 * (see {@link BlueprintStorage}).
 */
public final class ActiveBuild {

    private final int id;
    private final Location anchor;
    private final BlueprintTemplate template;
    private final UUID ownerUuid;
    /** Immutable Vertex faction id, so renaming a faction cannot change ownership. */
    private final int ownerFactionId;
    private final long startedAtMillis;
    private final String hologramName;

    private List<PendingBlock> blocks;
    private int currentIndex;
    private boolean cancelled;
    /** Restored builds wait for a faction member to explicitly resume them. */
    private boolean paused;
    /** Only a brand-new, not-yet-placed build may return its consumed item. */
    private boolean refundEligible;

    public ActiveBuild(int id, Location anchor, BlueprintTemplate template, UUID ownerUuid, int ownerFactionId,
                        long startedAtMillis, String hologramName, int currentIndex) {
        this.id = id;
        this.anchor = anchor;
        this.template = template;
        this.ownerUuid = ownerUuid;
        this.ownerFactionId = ownerFactionId;
        this.startedAtMillis = startedAtMillis;
        this.hologramName = hologramName;
        this.currentIndex = currentIndex;
    }

    public int id() {
        return id;
    }

    public Location anchor() {
        return anchor;
    }

    public BlueprintTemplate template() {
        return template;
    }

    public UUID ownerUuid() {
        return ownerUuid;
    }

    public int ownerFactionId() {
        return ownerFactionId;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public String hologramName() {
        return hologramName;
    }

    public List<PendingBlock> blocks() {
        return blocks;
    }

    public void setBlocks(List<PendingBlock> blocks) {
        this.blocks = blocks;
    }

    public int currentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    public boolean isComplete() {
        return blocks != null && currentIndex >= blocks.size();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isPaused() {
        return paused;
    }

    public void pause() {
        this.paused = true;
    }

    public void resume() {
        this.paused = false;
    }

    public boolean isRefundEligible() {
        return refundEligible;
    }

    public void allowInitialRefund() {
        this.refundEligible = true;
    }

    /** One block position (relative to the clipboard's own origin, converted to world-space at placement time) and its data. */
    public record PendingBlock(BlockVector3 relativeOffset, BlockData data) {
    }
}
