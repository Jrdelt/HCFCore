package me.hcfcore.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MySQLStorage implements Storage {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS kit_cooldowns (
                uuid CHAR(36) NOT NULL,
                kit_name VARCHAR(64) NOT NULL,
                available_at BIGINT NOT NULL,
                PRIMARY KEY (uuid, kit_name)
            )""";

    private static final String SELECT_COOLDOWNS = "SELECT kit_name, available_at FROM kit_cooldowns WHERE uuid = ?";
    private static final String UPSERT_COOLDOWN = """
            INSERT INTO kit_cooldowns (uuid, kit_name, available_at) VALUES (?, ?, ?)
            ON DUPLICATE KEY UPDATE available_at = VALUES(available_at)""";

    private final Database database;

    public MySQLStorage(Database database) {
        this.database = database;
    }

    @Override
    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_TABLE);
        }
    }

    @Override
    public Map<String, Long> loadCooldowns(UUID uuid) throws SQLException {
        Map<String, Long> cooldowns = new HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_COOLDOWNS)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    cooldowns.put(resultSet.getString("kit_name"), resultSet.getLong("available_at"));
                }
            }
        }
        return cooldowns;
    }

    @Override
    public void saveCooldown(UUID uuid, String kitName, long availableAt) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT_COOLDOWN)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, kitName.toLowerCase(Locale.ROOT));
            statement.setLong(3, availableAt);
            statement.executeUpdate();
        }
    }

    @Override
    public void close() {
        database.close();
    }
}
