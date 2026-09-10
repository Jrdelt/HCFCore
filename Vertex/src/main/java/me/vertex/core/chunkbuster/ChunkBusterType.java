package me.vertex.core.chunkbuster;

import java.util.Locale;

/**
 * The four independently-configurable Chunk Buster area-clear types.
 *
 * <p>Every type's affected area is confined to the single chunk containing
 * the placement point -- what differs is the vertical (and, for
 * {@link #SINGLE_COLUMN}, horizontal) extent cleared within that chunk:
 *
 * <ul>
 *   <li>{@link #UPWARD} -- every column in the chunk, from the placement
 *       block up to the world's build height.
 *   <li>{@link #DOWNWARD} -- every column in the chunk, from the world's
 *       minimum height up to the placement block.
 *   <li>{@link #FULL} -- every column in the chunk, full height.
 *   <li>{@link #SINGLE_COLUMN} -- only the one column at the placement
 *       point, full height -- not the whole chunk.
 * </ul>
 *
 * <p>{@code configKey} is the {@code chunkbuster.yml} / {@code shop.yml}
 * map key for this type (kebab-case, matching the file convention
 * elsewhere in this codebase) and doubles as the value persisted in the
 * {@code chunk_buster_operations}/{@code chunk_buster_log} tables' {@code
 * type} column, so renaming an enum constant alone never silently changes
 * what's on disk.
 */
public enum ChunkBusterType {
    UPWARD("upward"),
    DOWNWARD("downward"),
    FULL("full"),
    SINGLE_COLUMN("single-column");

    private final String configKey;

    ChunkBusterType(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return configKey;
    }

    /** @return the matching type for a stored/configured key, or null if none match. */
    public static ChunkBusterType fromConfigKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (ChunkBusterType type : values()) {
            if (type.configKey.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
