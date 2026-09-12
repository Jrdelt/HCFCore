package me.vertex.core.season;

import me.vertex.core.network.NetworkManager;
import me.vertex.core.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Performs the map/season data reset as one database transaction. */
public final class SeasonResetManager {
    private final Plugin plugin;
    private final Database database;
    private final NetworkManager network;
    private final AtomicBoolean running = new AtomicBoolean();

    public SeasonResetManager(Plugin plugin, Database database, NetworkManager network) {
        this.plugin = plugin;
        this.database = database;
        this.network = network;
    }

    /** A reset is intentionally console-only in practice: every gameplay shard must be empty first. */
    public boolean networkIsEmpty() {
        if (!Bukkit.getOnlinePlayers().isEmpty()) return false;
        return network == null || !network.enabled()
                || (network.shards().stream().allMatch(shard -> shard.currentPlayers() == 0)
                && !network.hasActiveTransfers());
    }

    public CompletableFuture<Result> reset() {
        // Bukkit's live player collection is a primary-thread API. The admin
        // command calls this from the primary thread; reject accidental
        // asynchronous callers instead of reading Bukkit state unsafely.
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(new Result(false, Map.of(), "main-thread-required"));
        }
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new Result(false, Map.of(), "already-running"));
        }
        if (!networkIsEmpty()) {
            running.set(false);
            return CompletableFuture.completedFuture(new Result(false, Map.of(), "players-online"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Integer> changed = resetTransaction();
                if (network != null && network.enabled()) network.publishInvalidation("season-reset", Long.toString(System.currentTimeMillis()));
                return new Result(true, changed, null);
            } catch (Exception error) {
                plugin.getLogger().severe("Season reset failed and was rolled back: " + error.getMessage());
                return new Result(false, Map.of(), error.getClass().getSimpleName());
            } finally {
                running.set(false);
            }
        });
    }

    private Map<String, Integer> resetTransaction() throws SQLException {
        Map<String, Integer> changed = new LinkedHashMap<>();
        try (Connection connection = database.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                long nextRegen = System.currentTimeMillis()
                        + Math.max(1_000L, plugin.getConfig().getLong(
                                "factions.power.regeneration-interval-seconds", 120L) * 1_000L);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE vertex_player_power SET current_power=max_power,next_regen_at=?")) {
                    statement.setLong(1, nextRegen);
                    changed.put("vertex_player_power", statement.executeUpdate());
                }
                // Faction identity, membership, permanent max-power boosts, global Spawn/warps,
                // network topology, configuration and investigation/audit records deliberately survive.
                for (String table : SEASON_TABLES) {
                    try (Statement statement = connection.createStatement()) {
                        changed.put(table, statement.executeUpdate("DELETE FROM " + table));
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    changed.put("vertex_faction_home", statement.executeUpdate(
                            "UPDATE vertex_factions SET home_shard=NULL,home_world=NULL,home_x=NULL,home_y=NULL,home_z=NULL,home_yaw=NULL,home_pitch=NULL"));
                    changed.put("zone_players", statement.executeUpdate(
                            "UPDATE zone_players SET haven_kills=0,riftlands_kills=0,haven_cooldown_until=0,riftlands_cooldown_until=0,rift_session_id=NULL,winner_boost=0,winner_cycle=0"));
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
        return Map.copyOf(changed);
    }

    private static final List<String> SEASON_TABLES = List.of(
            "vertex_faction_claims", "base_claim_region_chunks", "base_claims",
            "base_claim_slot_purchases", "raid_claim_expirations", "vertex_faction_warps",
                    "vertex_faction_logs", "vertex_faction_archives", "vertex_faction_player_cooldowns", "vertex_faction_cooldowns",
            "vertex_teleport_cooldowns", "kit_cooldowns", "ability_cooldowns", "blueprint_cooldowns",
            "faction_upgrade_levels", "faction_banks", "vertex_faction_vaults",
            "faction_shield_activations", "faction_shield_overrides", "faction_shields",
            "faction_shield_weekly", "faction_grace", "ftop_scores", "ftop_schedule", "pvptop_points", "pvptop_log",
            "spawners", "chunk_collectors", "mine_koths", "mine_hot_zones", "shop_stock",
            "zone_event_scores", "zone_event_state", "vertex_player_stats",
            "chunk_buster_operations", "chunk_buster_log");

    public record Result(boolean success, Map<String, Integer> affectedRows, String error) {
        public int totalAffectedRows() { return affectedRows.values().stream().mapToInt(Integer::intValue).sum(); }
    }
}
