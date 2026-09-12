package me.vertex.core.stats;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Durable network-wide kill/death totals used by Vertex placeholders. */
public final class PlayerStatsStorage {
    private final Database database;
    private final boolean sqlite;

    public PlayerStatsStorage(Database database) {
        this.database = database;
        this.sqlite = database.dialect() == Database.Dialect.SQLITE;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_player_stats (player_uuid VARCHAR(36) PRIMARY KEY, kills BIGINT NOT NULL DEFAULT 0, deaths BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL)");
        }
    }

    public Map<UUID, PlayerStats> loadAll() throws SQLException {
        Map<UUID, PlayerStats> result = new HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT player_uuid,kills,deaths,updated_at FROM vertex_player_stats");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                try {
                    UUID uuid = UUID.fromString(rows.getString(1));
                    result.put(uuid, new PlayerStats(rows.getLong(2), rows.getLong(3), rows.getLong(4)));
                } catch (IllegalArgumentException ignored) {
                    // A malformed legacy UUID must not prevent every valid stat row from loading.
                }
            }
        }
        return result;
    }

    public PlayerStats increment(UUID uuid, long kills, long deaths, long now) throws SQLException {
        String upsert = sqlite
                ? "INSERT INTO vertex_player_stats(player_uuid,kills,deaths,updated_at) VALUES(?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET kills=vertex_player_stats.kills+excluded.kills,deaths=vertex_player_stats.deaths+excluded.deaths,updated_at=excluded.updated_at"
                : "INSERT INTO vertex_player_stats(player_uuid,kills,deaths,updated_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE kills=kills+VALUES(kills),deaths=deaths+VALUES(deaths),updated_at=VALUES(updated_at)";
        try (Connection connection = database.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                    statement.setString(1, uuid.toString());
                    statement.setLong(2, Math.max(0L, kills));
                    statement.setLong(3, Math.max(0L, deaths));
                    statement.setLong(4, now);
                    statement.executeUpdate();
                }
                PlayerStats value;
                try (PreparedStatement statement = connection.prepareStatement("SELECT kills,deaths,updated_at FROM vertex_player_stats WHERE player_uuid=?")) {
                    statement.setString(1, uuid.toString());
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new SQLException("Player stats upsert did not produce a row");
                        value = new PlayerStats(row.getLong(1), row.getLong(2), row.getLong(3));
                    }
                }
                connection.commit();
                return value;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        }
    }

    public record PlayerStats(long kills, long deaths, long updatedAtMillis) {
        public static final PlayerStats EMPTY = new PlayerStats(0L, 0L, 0L);
    }
}
