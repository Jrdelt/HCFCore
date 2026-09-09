package me.vertex.core.preferences;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.UUID;

/** Durable sparse storage: omitted categories are enabled by default. */
public final class AnnouncementPreferenceStorage {
    private final Database database;

    public AnnouncementPreferenceStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS announcement_preferences ("
                    + "uuid CHAR(36) NOT NULL, category VARCHAR(32) NOT NULL, enabled BOOLEAN NOT NULL, "
                    + "PRIMARY KEY(uuid, category))");
        }
    }

    public EnumSet<AnnouncementCategory> loadDisabled(UUID uuid) throws SQLException {
        EnumSet<AnnouncementCategory> disabled = EnumSet.noneOf(AnnouncementCategory.class);
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT category FROM announcement_preferences WHERE uuid = ? AND enabled = ?")) {
            statement.setString(1, uuid.toString());
            statement.setBoolean(2, false);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    try {
                        disabled.add(AnnouncementCategory.valueOf(result.getString(1)));
                    } catch (IllegalArgumentException ignored) {
                        // A removed category must not prevent the rest of a player's settings loading.
                    }
                }
            }
        }
        return disabled;
    }

    public void save(UUID uuid, AnnouncementCategory category, boolean enabled) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO announcement_preferences (uuid, category, enabled) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid, category) DO UPDATE SET enabled = excluded.enabled")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, category.name());
            statement.setBoolean(3, enabled);
            statement.executeUpdate();
        } catch (SQLException unsupportedUpsert) {
            try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO announcement_preferences (uuid, category, enabled) VALUES (?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE enabled = VALUES(enabled)")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, category.name());
                statement.setBoolean(3, enabled);
                statement.executeUpdate();
            }
        }
    }
}
