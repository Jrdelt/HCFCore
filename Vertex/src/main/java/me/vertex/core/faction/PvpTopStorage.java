package me.vertex.core.faction;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent PvP objective points and their audit trail. */
public final class PvpTopStorage {
    private final Database database;

    public PvpTopStorage(Database database) { this.database = database; }

    public void init() throws SQLException {
        String points = "CREATE TABLE IF NOT EXISTS pvptop_points (faction_id INT PRIMARY KEY, points BIGINT NOT NULL, updated_at BIGINT NOT NULL)";
        String log = database.dialect() == Database.Dialect.SQLITE
                ? "CREATE TABLE IF NOT EXISTS pvptop_log (id INTEGER PRIMARY KEY AUTOINCREMENT, faction_id INT NOT NULL, points BIGINT NOT NULL, source VARCHAR(128) NOT NULL, actor_uuid VARCHAR(36), created_at BIGINT NOT NULL)"
                : "CREATE TABLE IF NOT EXISTS pvptop_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, faction_id INT NOT NULL, points BIGINT NOT NULL, source VARCHAR(128) NOT NULL, actor_uuid VARCHAR(36), created_at BIGINT NOT NULL)";
        try (Connection connection = database.getConnection(); PreparedStatement a = connection.prepareStatement(points);
             PreparedStatement b = connection.prepareStatement(log)) {
            a.executeUpdate(); b.executeUpdate();
        }
    }

    public Map<Integer, Long> load() throws SQLException {
        Map<Integer, Long> values = new LinkedHashMap<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT faction_id, points FROM pvptop_points"); ResultSet results = statement.executeQuery()) {
            while (results.next()) values.put(results.getInt(1), results.getLong(2));
        }
        return values;
    }

    public long award(int factionId, long points, String source, String actorUuid, long now) throws SQLException {
        String upsert = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO pvptop_points (faction_id, points, updated_at) VALUES (?, ?, ?) ON CONFLICT(faction_id) DO UPDATE SET points = pvptop_points.points + excluded.points, updated_at = excluded.updated_at"
                : "INSERT INTO pvptop_points (faction_id, points, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE points = points + VALUES(points), updated_at = VALUES(updated_at)";
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement update = connection.prepareStatement(upsert);
                 PreparedStatement log = connection.prepareStatement("INSERT INTO pvptop_log (faction_id, points, source, actor_uuid, created_at) VALUES (?, ?, ?, ?, ?)")) {
                update.setInt(1, factionId); update.setLong(2, points); update.setLong(3, now); update.executeUpdate();
                log.setInt(1, factionId); log.setLong(2, points); log.setString(3, source); log.setString(4, actorUuid); log.setLong(5, now); log.executeUpdate();
                try (PreparedStatement read = connection.prepareStatement("SELECT points FROM pvptop_points WHERE faction_id = ?")) {
                    read.setInt(1, factionId);
                    try (ResultSet result = read.executeQuery()) { result.next(); long total = result.getLong(1); connection.commit(); return total; }
                }
            } catch (SQLException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(true); }
        }
    }
}
