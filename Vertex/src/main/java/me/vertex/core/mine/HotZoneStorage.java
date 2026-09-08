package me.vertex.core.mine;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The active Hot Zone and what ran most recently.
 *
 * <p>Start and end are stored as absolute timestamps rather than a remaining
 * duration, so a restart resumes exactly where it left off and a Hot Zone
 * that expired while the server was down is simply over -- it never comes
 * back with time added.
 */
public final class HotZoneStorage {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS mine_hot_zones (
                mine_id VARCHAR(64) NOT NULL PRIMARY KEY,
                started_at BIGINT NOT NULL,
                ends_at BIGINT NOT NULL
            )""";
    private static final String SELECT_ALL = "SELECT mine_id, started_at, ends_at FROM mine_hot_zones";
    private static final String UPSERT_MYSQL = """
            INSERT INTO mine_hot_zones (mine_id, started_at, ends_at) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE started_at = VALUES(started_at), ends_at = VALUES(ends_at)""";
    private static final String UPSERT_SQLITE = """
            INSERT INTO mine_hot_zones (mine_id, started_at, ends_at) VALUES (?, ?, ?)
            ON CONFLICT(mine_id) DO UPDATE SET started_at = excluded.started_at, ends_at = excluded.ends_at""";

    private final Database database;
    private final String upsert;

    public HotZoneStorage(Database database) {
        this.database = database;
        this.upsert = database.dialect() == Database.Dialect.SQLITE ? UPSERT_SQLITE : UPSERT_MYSQL;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.executeUpdate();
        }
    }

    public List<StoredHotZone> loadAll() throws SQLException {
        List<StoredHotZone> zones = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                zones.add(new StoredHotZone(results.getString("mine_id"),
                        results.getLong("started_at"), results.getLong("ends_at")));
            }
        }
        return zones;
    }

    public void save(String mineId, long startedAt, long endsAt) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setString(1, mineId);
            statement.setLong(2, startedAt);
            statement.setLong(3, endsAt);
            statement.executeUpdate();
        }
    }

    public record StoredHotZone(String mineId, long startedAtMillis, long endsAtMillis) {
        public boolean isActive(long nowMillis) {
            return nowMillis < endsAtMillis;
        }
    }
}
