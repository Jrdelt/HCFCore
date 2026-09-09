package me.vertex.core.faction;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable F Top snapshot and schedule state, independent of FactionsUUID's power leaderboard. */
public final class FTopStorage {

    private static final String CREATE_SCORES = """
            CREATE TABLE IF NOT EXISTS ftop_scores (
                faction_id INT PRIMARY KEY,
                current_value DOUBLE NOT NULL,
                previous_rank INT NOT NULL,
                current_rank INT NOT NULL,
                updated_at BIGINT NOT NULL
            )""";
    private static final String CREATE_SCHEDULE = """
            CREATE TABLE IF NOT EXISTS ftop_schedule (
                singleton_key INT PRIMARY KEY,
                next_update_at BIGINT NOT NULL
            )""";

    private final Database database;

    public FTopStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement scores = connection.prepareStatement(CREATE_SCORES);
             PreparedStatement schedule = connection.prepareStatement(CREATE_SCHEDULE)) {
            scores.executeUpdate();
            schedule.executeUpdate();
        }
    }

    public Snapshot load() throws SQLException {
        Map<Integer, Score> scores = new LinkedHashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT faction_id, current_value, previous_rank, current_rank FROM ftop_scores");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                scores.put(results.getInt("faction_id"), new Score(results.getDouble("current_value"),
                        results.getInt("previous_rank"), results.getInt("current_rank")));
            }
        }
        long nextUpdateAt = 0L;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT next_update_at FROM ftop_schedule WHERE singleton_key = 1");
             ResultSet results = statement.executeQuery()) {
            if (results.next()) {
                nextUpdateAt = results.getLong(1);
            }
        }
        return new Snapshot(scores, nextUpdateAt);
    }

    public void save(Map<Integer, Score> scores, long nextUpdateAt, long updatedAt) throws SQLException {
        String scoreUpsert = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO ftop_scores (faction_id, current_value, previous_rank, current_rank, updated_at) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT(faction_id) DO UPDATE SET current_value = excluded.current_value, "
                + "previous_rank = excluded.previous_rank, current_rank = excluded.current_rank, updated_at = excluded.updated_at"
                : "INSERT INTO ftop_scores (faction_id, current_value, previous_rank, current_rank, updated_at) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE current_value = VALUES(current_value), previous_rank = VALUES(previous_rank), "
                + "current_rank = VALUES(current_rank), updated_at = VALUES(updated_at)";
        String scheduleUpsert = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO ftop_schedule (singleton_key, next_update_at) VALUES (1, ?) "
                + "ON CONFLICT(singleton_key) DO UPDATE SET next_update_at = excluded.next_update_at"
                : "INSERT INTO ftop_schedule (singleton_key, next_update_at) VALUES (1, ?) "
                + "ON DUPLICATE KEY UPDATE next_update_at = VALUES(next_update_at)";
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement clear = connection.prepareStatement("DELETE FROM ftop_scores");
                 PreparedStatement insert = connection.prepareStatement(scoreUpsert);
                 PreparedStatement schedule = connection.prepareStatement(scheduleUpsert)) {
                clear.executeUpdate();
                for (Map.Entry<Integer, Score> entry : scores.entrySet()) {
                    Score score = entry.getValue();
                    insert.setInt(1, entry.getKey());
                    insert.setDouble(2, score.currentValue());
                    insert.setInt(3, score.previousRank());
                    insert.setInt(4, score.currentRank());
                    insert.setLong(5, updatedAt);
                    insert.addBatch();
                }
                insert.executeBatch();
                schedule.setLong(1, nextUpdateAt);
                schedule.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public record Score(double currentValue, int previousRank, int currentRank) { }
    public record Snapshot(Map<Integer, Score> scores, long nextUpdateAt) { }
}
