package me.vertex.core.storage;

import java.util.Locale;

/**
 * Shard-qualifies a world name for tables shared across a MySQL network,
 * the same way {@code me.vertex.core.claims.ChunkKey} already does for
 * claims (ISS-15). Two shards can each have a world literally named
 * {@code world}; without a shard-qualified key, a block-location table keyed
 * by bare world name/coordinates lets one shard silently overwrite or delete
 * another shard's row at the same coordinates, and loading can mistake a
 * remote shard's row for a local block.
 *
 * <p>Blank (the default, and always for a standalone/non-MySQL deployment)
 * keeps every method a no-op passthrough, so a server that never enables
 * network sharing sees byte-identical stored world names to before this
 * existed -- no migration is forced on anyone not actually sharing a
 * database.
 */
public final class ShardScope {
    private static final String SEPARATOR = "::";
    private static volatile String localShard = "";

    private ShardScope() {
    }

    /** Configure once, before any table using this is loaded. Blank disables qualification entirely. */
    public static void configure(String shardId) {
        localShard = normalize(shardId);
    }

    /** @return {@code worldName} prefixed with the local shard, unless already qualified or shard scoping is off. */
    public static String qualify(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("world");
        }
        return localShard.isBlank() || worldName.contains(SEPARATOR) ? worldName : localShard + SEPARATOR + worldName;
    }

    /**
     * Reads a value straight from storage as a qualified world name. A value
     * with no separator predates this scheme (or was written by a standalone
     * server); treated as belonging to whichever shard is loading it now --
     * exactly {@code ChunkKey.fromStorage}'s migrate-on-read strategy.
     */
    public static String fromStorage(String storedWorld) {
        return storedWorld != null && storedWorld.contains(SEPARATOR) ? storedWorld : qualify(storedWorld);
    }

    public static String shardId(String qualifiedWorld) {
        int split = qualifiedWorld.indexOf(SEPARATOR);
        return split < 0 ? localShard : qualifiedWorld.substring(0, split);
    }

    /** @return the bare Bukkit world name, with any shard qualifier stripped. */
    public static String localWorld(String qualifiedWorld) {
        int split = qualifiedWorld.indexOf(SEPARATOR);
        return split < 0 ? qualifiedWorld : qualifiedWorld.substring(split + SEPARATOR.length());
    }

    /** @return true when {@code qualifiedWorld} belongs to this shard (or shard scoping is off). */
    public static boolean isLocalShard(String qualifiedWorld) {
        return localShard.isBlank() || shardId(qualifiedWorld).equalsIgnoreCase(localShard);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
