package me.hcfcore.core.blueprint;

import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;

import java.util.List;
import java.util.UUID;

/**
 * Runtime state for one in-progress (or paused-across-restart) build. The
 * flattened block list is rebuilt from the same .schem file on load/resume
 * (see {@link BlueprintManager#flatten}) rather than persisted -- only
 * {@code currentIndex} is durable (see {@link BlueprintStorage}).
 */
public final class ActiveBuild {

    private final int id;
    private final Location anchor;
    private final BlueprintTemplate template;
    private final UUID ownerUuid;
    private final String ownerFactionTag;
    private final long startedAtMillis;
    private final String hologramName;

    private List<PendingBlock> blocks;
    private int currentIndex;
    private boolean cancelled;

    public ActiveBuild(int id, Location anchor, BlueprintTemplate template, UUID ownerUuid, String ownerFactionTag,
                        long startedAtMillis, String hologramName, int currentIndex) {
        this.id = id;
        this.anchor = anchor;
        this.template = template;
        this.ownerUuid = ownerUuid;
        this.ownerFactionTag = ownerFactionTag;
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

    public String ownerFactionTag() {
        return ownerFactionTag;
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

    /** One block position (relative to the clipboard's own origin, converted to world-space at placement time) and its data. */
    public record PendingBlock(BlockVector3 relativeOffset, BlockData data) {
    }
}
