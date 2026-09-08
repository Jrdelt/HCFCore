package me.vertex.core.shop;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** Durable storage for the Shop's per-block market state (how far each price has drifted from base). */
public final class ShopStorage {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS shop_stock (
                material VARCHAR(64) NOT NULL PRIMARY KEY,
                net_volume DOUBLE NOT NULL
            )""";
    private static final String SELECT_ALL = "SELECT material, net_volume FROM shop_stock";
    private static final String DELETE = "DELETE FROM shop_stock WHERE material = ?";

    private final Database database;
    private final String upsert;

    public ShopStorage(Database database) {
        this.database = database;
        this.upsert = switch (database.dialect()) {
            case SQLITE -> "INSERT INTO shop_stock (material, net_volume) VALUES (?, ?) "
                    + "ON CONFLICT(material) DO UPDATE SET net_volume = excluded.net_volume";
            case MYSQL -> "INSERT INTO shop_stock (material, net_volume) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE net_volume = VALUES(net_volume)";
        };
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(CREATE_TABLE)) {
            statement.executeUpdate();
        }
    }

    public Map<String, Double> loadAll() throws SQLException {
        Map<String, Double> values = new HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_ALL);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                values.put(results.getString("material"), results.getDouble("net_volume"));
            }
        }
        return values;
    }

    public void save(String material, double netVolume) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setString(1, material);
            statement.setDouble(2, netVolume);
            statement.executeUpdate();
        }
    }

    /** Removes a block's row once its net-volume has fully recovered to (effectively) zero, keeping the table small. */
    public void delete(String material) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setString(1, material);
            statement.executeUpdate();
        }
    }
}
