package me.vertex.core.faction;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;

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
                ? "CREATE TABLE IF NOT EXISTS pvptop_log (id INTEGER PRIMARY KEY AUTOINCREMENT, faction_id INT NOT NULL, points BIGINT NOT NULL, source VARCHAR(128) NOT NULL, operation_key VARCHAR(191), actor_uuid VARCHAR(36), created_at BIGINT NOT NULL)"
                : "CREATE TABLE IF NOT EXISTS pvptop_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, faction_id INT NOT NULL, points BIGINT NOT NULL, source VARCHAR(128) NOT NULL, operation_key VARCHAR(191), actor_uuid VARCHAR(36), created_at BIGINT NOT NULL)";
        try (Connection connection = database.getConnection(); PreparedStatement a = connection.prepareStatement(points);
             PreparedStatement b = connection.prepareStatement(log)) {
            a.executeUpdate();
            b.executeUpdate();
            SqlSchema.ensureColumn(connection, "pvptop_log", "operation_key", "VARCHAR(191) NULL");
            String legacyKey = database.dialect() == Database.Dialect.SQLITE
                    ? "'legacy:' || id" : "CONCAT('legacy:', id)";
            try (PreparedStatement migrate = connection.prepareStatement(
                    "UPDATE pvptop_log SET operation_key=" + legacyKey
                            + " WHERE operation_key IS NULL OR operation_key=''")) {
                migrate.executeUpdate();
            }
            SqlSchema.ensureIndex(connection, "pvptop_log", "idx_pvptop_operation", true,
                    "operation_key");
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

    public AwardResult award(int factionId, long points, String source, String operationKey,
            String actorUuid, long now) throws SQLException {
        if (points <= 0L || operationKey == null || operationKey.isBlank()) {
            throw new SQLException("PvP Top award requires positive points and an operation key");
        }
        String upsert = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO pvptop_points (faction_id, points, updated_at) VALUES (?, ?, ?) ON CONFLICT(faction_id) DO UPDATE SET points = excluded.points, updated_at = excluded.updated_at"
                : "INSERT INTO pvptop_points (faction_id, points, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE points = VALUES(points), updated_at = VALUES(updated_at)";
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!SqlSchema.lockFactionIfPresent(connection, database.dialect(), factionId)) {
                    connection.rollback();
                    throw new SQLException("Faction no longer exists");
                }
                Long current = total(connection, factionId);
                if (operationExists(connection, operationKey)) {
                    connection.commit();
                    return new AwardResult(current == null ? 0L : current, false);
                }
                long next;
                try {
                    next = Math.addExact(current == null ? 0L : current, points);
                } catch (ArithmeticException overflow) {
                    throw new SQLException("PvP Top point total overflow for faction " + factionId, overflow);
                }
                try (PreparedStatement update = connection.prepareStatement(upsert);
                 PreparedStatement log = connection.prepareStatement("INSERT INTO pvptop_log (faction_id, points, source, operation_key, actor_uuid, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    log.setInt(1, factionId); log.setLong(2, points); log.setString(3, source);
                    log.setString(4, operationKey); log.setString(5, actorUuid); log.setLong(6, now);
                    log.executeUpdate();
                    update.setInt(1, factionId); update.setLong(2, next); update.setLong(3, now);
                    update.executeUpdate();
                    connection.commit();
                    return new AwardResult(next, true);
                }
            } catch (SQLException error) {
                connection.rollback();
                // A second shard can win the unique-key race after our first
                // check. Treat that row as the already-completed operation.
                if (operationExists(connection, operationKey)) {
                    Long current = total(connection, factionId);
                    return new AwardResult(current == null ? 0L : current, false);
                }
                throw error;
            }
            finally { connection.setAutoCommit(true); }
        }
    }

    private static boolean operationExists(Connection connection, String operationKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pvptop_log WHERE operation_key=?")) {
            statement.setString(1, operationKey);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private static Long total(Connection connection, int factionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT points FROM pvptop_points WHERE faction_id=?")) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : null;
            }
        }
    }

    public record AwardResult(long total, boolean awarded) { }
}
