package me.vertex.core.faction;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!SqlSchema.lockFactionIfPresent(connection, database.dialect(), factionId)) {
                    connection.rollback();
                    throw new SQLException("Faction no longer exists");
                }
                try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                    statement.setInt(1, factionId);
                    statement.setString(2, upgradeKey);
                    statement.setInt(3, level);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Atomic compare-and-set used to prevent two shards buying the same level. */
    public boolean advance(int factionId, String upgradeKey, int expectedLevel, int nextLevel) throws SQLException {
        return advance(factionId, upgradeKey, expectedLevel, nextLevel, null);
    }

    /** Runtime purchase path: the durable purchaser must still be Leader/Co-Leader. */
    public boolean advanceAuthorized(int factionId, UUID purchaser, String upgradeKey,
            int expectedLevel, int nextLevel) throws SQLException {
        if (purchaser == null) return false;
        return advance(factionId, upgradeKey, expectedLevel, nextLevel, purchaser);
    }

    private boolean advance(int factionId, String upgradeKey, int expectedLevel, int nextLevel,
            UUID purchaser) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!SqlSchema.lockFactionIfPresent(connection, database.dialect(), factionId)) {
                    connection.rollback();
                    return false;
                }
                if (purchaser != null) {
                    String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
                    try (PreparedStatement member = connection.prepareStatement(
                            "SELECT role FROM vertex_faction_members WHERE player_uuid=? AND faction_id=?" + suffix)) {
                        member.setString(1, purchaser.toString());
                        member.setInt(2, factionId);
                        try (ResultSet row = member.executeQuery()) {
                            if (!row.next() || !(row.getString(1).equalsIgnoreCase("LEADER")
                                    || row.getString(1).equalsIgnoreCase("COLEADER"))) {
                                connection.rollback();
                                return false;
                            }
                        }
                    }
                }
                String ensureSql = database.dialect() == Database.Dialect.SQLITE
                        ? "INSERT OR IGNORE INTO faction_upgrade_levels(faction_id,upgrade_key,level) VALUES(?,?,0)"
                        : "INSERT IGNORE INTO faction_upgrade_levels(faction_id,upgrade_key,level) VALUES(?,?,0)";
                try (PreparedStatement ensure = connection.prepareStatement(ensureSql)) {
                    ensure.setInt(1, factionId); ensure.setString(2, upgradeKey); ensure.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE faction_upgrade_levels SET level=? WHERE faction_id=? AND upgrade_key=? AND level=?")) {
                    update.setInt(1, nextLevel); update.setInt(2, factionId);
                    update.setString(3, upgradeKey); update.setInt(4, expectedLevel);
                    boolean changed = update.executeUpdate() == 1;
                    if (changed) connection.commit(); else connection.rollback();
                    return changed;
                }
            } catch (SQLException error) {
                connection.rollback(); throw error;
            } finally { connection.setAutoCommit(previous); }
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
