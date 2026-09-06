package me.vertex.core.faction;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Durable storage for Vertex-owned faction money and experience balances. */
public final class FactionBankStorage {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS faction_banks (
                faction_id INT NOT NULL PRIMARY KEY,
                money DOUBLE NOT NULL,
                experience BIGINT NOT NULL
            )""";
    private static final String SELECT_ALL = "SELECT faction_id, money, experience FROM faction_banks";
    private static final String UPSERT_MYSQL = """
            INSERT INTO faction_banks (faction_id, money, experience) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE money = VALUES(money), experience = VALUES(experience)""";
    private static final String UPSERT_SQLITE = """
            INSERT INTO faction_banks (faction_id, money, experience) VALUES (?, ?, ?)
            ON CONFLICT(faction_id) DO UPDATE SET money = excluded.money, experience = excluded.experience""";
    private static final String DELETE = "DELETE FROM faction_banks WHERE faction_id = ?";

    private final Database database;
    private final String upsert;

    public FactionBankStorage(Database database) {
        this.database = database;
        this.upsert = database.dialect() == Database.Dialect.SQLITE ? UPSERT_SQLITE : UPSERT_MYSQL;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.executeUpdate();
        }
    }

    public List<StoredBank> loadAll() throws SQLException {
        List<StoredBank> banks = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                banks.add(new StoredBank(results.getInt("faction_id"), results.getDouble("money"),
                        results.getLong("experience")));
            }
        }
        return banks;
    }

    public void save(int factionId, double money, long experience) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setInt(1, factionId);
            statement.setDouble(2, money);
            statement.setLong(3, experience);
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

    public record StoredBank(int factionId, double money, long experience) {
    }
}
