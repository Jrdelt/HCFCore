package me.vertex.core.enchant;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Durable per-player rune preference settings: the global rune sound
 * volume, and any per-rune override of a message/sound/particle toggle.
 * Mirrors {@code me.vertex.core.preferences.AnnouncementPreferenceStorage}'s
 * shape exactly -- a small dedicated Database-backed store, not folded into
 * the shared {@code Storage} interface, since this data is specific to one
 * feature.
 *
 * <p>{@code runeId} uses {@code ""} (never SQL {@code NULL}, which MySQL
 * disallows inside a primary key column) as the "global setting" sentinel,
 * e.g. {@code (uuid, "", "volume")} for the global slider versus {@code
 * (uuid, "dasher", "sound")} for one rune's override.
 */
public final class RunePreferenceStorage {
    private final Database database;

    public RunePreferenceStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS rune_preferences ("
                    + "uuid CHAR(36) NOT NULL, rune_id VARCHAR(64) NOT NULL DEFAULT '', "
                    + "setting_key VARCHAR(32) NOT NULL, setting_value VARCHAR(64) NOT NULL, "
                    + "PRIMARY KEY(uuid, rune_id, setting_key))");
        }
    }

    public record Setting(String runeId, String settingKey, String value) {
    }

    public List<Setting> loadAll(UUID uuid) throws SQLException {
        List<Setting> settings = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT rune_id, setting_key, setting_value FROM rune_preferences WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    settings.add(new Setting(result.getString(1), result.getString(2), result.getString(3)));
                }
            }
        }
        return settings;
    }

    public void save(UUID uuid, String runeId, String settingKey, String value) throws SQLException {
        String upsert = database.dialect() == Database.Dialect.SQLITE
                ? "ON CONFLICT(uuid, rune_id, setting_key) DO UPDATE SET setting_value = excluded.setting_value"
                : "ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO rune_preferences (uuid, rune_id, setting_key, setting_value) VALUES (?, ?, ?, ?) " + upsert)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, runeId);
            statement.setString(3, settingKey);
            statement.setString(4, value);
            statement.executeUpdate();
        }
    }

    public void delete(UUID uuid, String runeId, String settingKey) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM rune_preferences WHERE uuid = ? AND rune_id = ? AND setting_key = ?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, runeId);
            statement.setString(3, settingKey);
            statement.executeUpdate();
        }
    }
}
