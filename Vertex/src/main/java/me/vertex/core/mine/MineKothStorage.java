package me.vertex.core.mine;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable Mine KOTH ownership.
 *
 * <p>{@code owned_since} is the point of this table. A reboot must not hand a
 * faction back a fresh booster ladder, so the moment they took the point is
 * stored rather than how long they have held it -- elapsed time is then
 * derived from the clock and survives any downtime.
 */
public final class MineKothStorage {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS mine_koths (
                mine_id VARCHAR(64) NOT NULL PRIMARY KEY,
                owner_faction INT NULL,
                control DOUBLE NOT NULL,
                owned_since BIGINT NOT NULL
            )""";
    private static final String SELECT_ALL = "SELECT mine_id, owner_faction, control, owned_since FROM mine_koths";
    private static final String UPSERT_MYSQL = """
            INSERT INTO mine_koths (mine_id, owner_faction, control, owned_since) VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE owner_faction = VALUES(owner_faction), control = VALUES(control),
                owned_since = VALUES(owned_since)""";
    private static final String UPSERT_SQLITE = """
            INSERT INTO mine_koths (mine_id, owner_faction, control, owned_since) VALUES (?, ?, ?, ?)
            ON CONFLICT(mine_id) DO UPDATE SET owner_faction = excluded.owner_faction,
                control = excluded.control, owned_since = excluded.owned_since""";

    private final Database database;
    private final String upsert;

    public MineKothStorage(Database database) {
        this.database = database;
        this.upsert = database.dialect() == Database.Dialect.SQLITE ? UPSERT_SQLITE : UPSERT_MYSQL;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.executeUpdate();
        }
    }

    public List<StoredKoth> loadAll() throws SQLException {
        List<StoredKoth> states = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                int owner = results.getInt("owner_faction");
                // Captured immediately: wasNull() reports on the last column
                // read, so any read in between would make this the wrong
                // answer and turn an unowned point into faction 0.
                Integer ownerFaction = results.wasNull() ? null : owner;
                states.add(new StoredKoth(results.getString("mine_id"), ownerFaction,
                        results.getDouble("control"),
                        results.getLong("owned_since")));
            }
        }
        return states;
    }

    public void save(String mineId, Integer ownerFaction, double control, long ownedSince) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setString(1, mineId);
            if (ownerFaction == null) {
                statement.setNull(2, java.sql.Types.INTEGER);
            } else {
                statement.setInt(2, ownerFaction);
            }
            statement.setDouble(3, control);
            statement.setLong(4, ownedSince);
            statement.executeUpdate();
        }
    }

    public record StoredKoth(String mineId, Integer ownerFaction, double control, long ownedSinceMillis) {
    }
}
