package me.vertex.core.network;

import me.vertex.core.storage.Database;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/** Startup identity shared by claims, escrow and handoffs; never changed by a hot reload. */
public record NetworkDeployment(boolean enabled, String shardId, Database.Dialect dialect) {
    public static NetworkDeployment read(FileConfiguration config) {
        boolean enabled = config.getBoolean("network.enabled", false);
        Database.Dialect dialect = Database.dialectOf(config.getString("storage.type", "local"));
        String shard = config.getString("network.shard-id", "standalone").trim().toLowerCase(Locale.ROOT);
        if (enabled && dialect != Database.Dialect.MYSQL) {
            throw new IllegalArgumentException("network.enabled: true requires storage.type: mysql. "
                    + "Vertex will not silently open a separate local database. For one server set network.enabled: false.");
        }
        if (!shard.matches("[a-z0-9_-]{1,64}") || enabled && "standalone".equals(shard)) {
            throw new IllegalArgumentException("network.shard-id must be 1-64 letters, digits, '-' or '_'. "
                    + "Network mode requires a unique Velocity backend name, not 'standalone'.");
        }
        return new NetworkDeployment(enabled, shard, dialect);
    }

    /** Pins in-memory config readers to the boot identity; the operator's disk file is left untouched. */
    public void apply(FileConfiguration config) {
        config.set("network.enabled", enabled);
        config.set("network.shard-id", shardId);
        config.set("storage.type", dialect == Database.Dialect.MYSQL ? "mysql" : "local");
    }
}
