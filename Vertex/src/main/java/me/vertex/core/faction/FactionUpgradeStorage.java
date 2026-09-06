package me.vertex.core.faction;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** SQL persistence for the small, stable faction-upgrade level matrix. */
public final class FactionUpgradeStorage {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS faction_upgrade_levels (
                faction_id INT NOT NULL,
                upgrade_key VARCHAR(64) NOT NULL,
                level INT NOT NULL,
                PRIMARY KEY (faction_id, upgrade_key)
            )""";
    private static final String SELECT_ALL =
            "SELECT faction_id, upgrade_key, level FROM faction_upgrade_levels";
    private static final String UPSERT_MYSQL = """
            INSERT INTO faction_upgrade_levels (faction_id, upgrade_key, level) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE level = VALUES(level)""";
    private static final String UPSERT_SQLITE = """
            INSERT INTO faction_upgrade_levels (faction_id, upgrade_key, level) VALUES (?, ?, ?)
            ON CONFLICT(faction_id, upgrade_key) DO UPDATE SET level = excluded.level""";
    private static final String DELETE_FACTION = "DELETE FROM faction_upgrade_levels WHERE faction_id = ?";

    private final Database database;
    private final String upsert;

    public FactionUpgradeStorage(Database database) {
        this.database = database;
        this.upsert = database.dialect() == Database.Dialect.SQLITE ? UPSERT_SQLITE : UPSERT_MYSQL;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.executeUpdate();
        }
    }

    public List<StoredLevel> loadAll() throws SQLException {
        List<StoredLevel> levels = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                levels.add(new StoredLevel(results.getInt("faction_id"), results.getString("upgrade_key"),
                        results.getInt("level")));
            }
        }
        return levels;
    }

    public void save(int factionId, String upgradeKey, int level) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setInt(1, factionId);
            statement.setString(2, upgradeKey);
            statement.setInt(3, level);
            statement.executeUpdate();
        }
    }

    public void deleteFaction(int factionId) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE_FACTION)) {
            statement.setInt(1, factionId);
            statement.executeUpdate();
        }
    }

    public record StoredLevel(int factionId, String upgradeKey, int level) {
    }
}
