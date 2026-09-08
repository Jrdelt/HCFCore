package me.vertex.core.faction;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Durable storage for Vertex-owned faction money, experience, and TNT balances. */
public final class FactionBankStorage {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS faction_banks (
                faction_id INT NOT NULL PRIMARY KEY,
                money DOUBLE NOT NULL,
                experience BIGINT NOT NULL,
                tnt BIGINT NOT NULL DEFAULT 0
            )""";
    private static final String SELECT_ALL = "SELECT faction_id, money, experience, tnt FROM faction_banks";
    private static final String UPSERT_MYSQL = """
            INSERT INTO faction_banks (faction_id, money, experience, tnt) VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE money = VALUES(money), experience = VALUES(experience), tnt = VALUES(tnt)""";
    private static final String UPSERT_SQLITE = """
            INSERT INTO faction_banks (faction_id, money, experience, tnt) VALUES (?, ?, ?, ?)
            ON CONFLICT(faction_id) DO UPDATE SET money = excluded.money,
                experience = excluded.experience, tnt = excluded.tnt""";
    private static final String DELETE = "DELETE FROM faction_banks WHERE faction_id = ?";

    private final Database database;
    private final String upsert;

    public FactionBankStorage(Database database) {
        this.database = database;
        this.upsert = database.dialect() == Database.Dialect.SQLITE ? UPSERT_SQLITE : UPSERT_MYSQL;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
                statement.executeUpdate();
            }
            migrateTntColumn(connection);
        }
    }

    /**
     * `CREATE TABLE IF NOT EXISTS` never updates an existing server's schema.
     * Faction banks predate Vertex owning the TNT balance, so a server
     * upgrading from that version has no tnt column and every SELECT/UPSERT
     * here would fail on startup without this.
     */
    private void migrateTntColumn(Connection connection) throws SQLException {
        try (PreparedStatement probe = connection.prepareStatement("SELECT tnt FROM faction_banks WHERE 1 = 0")) {
            probe.executeQuery();
        } catch (SQLException missingColumn) {
            try (PreparedStatement alter = connection.prepareStatement(
                    "ALTER TABLE faction_banks ADD COLUMN tnt BIGINT NOT NULL DEFAULT 0")) {
                alter.executeUpdate();
            }
        }
    }

    public List<StoredBank> loadAll() throws SQLException {
        List<StoredBank> banks = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                banks.add(new StoredBank(results.getInt("faction_id"), results.getDouble("money"),
                        results.getLong("experience"), results.getLong("tnt")));
            }
        }
        return banks;
    }

    public void save(int factionId, double money, long experience, long tnt) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setInt(1, factionId);
            statement.setDouble(2, money);
            statement.setLong(3, experience);
            statement.setLong(4, tnt);
            statement.executeUpdate();
        }
    }

    public void delete(int factionId) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setInt(1, factionId);
            statement.executeUpdate();
        }
    }

    public record StoredBank(int factionId, double money, long experience, long tnt) {
    }
}
