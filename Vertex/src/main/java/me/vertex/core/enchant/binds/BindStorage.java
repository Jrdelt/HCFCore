package me.vertex.core.enchant.binds;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable {@code /binds} storage: the live 1-9 bind layout, saved preset
 * snapshots, and which preset (if any) the live layout mirrors. Same small
 * dedicated-Database-backed shape as {@code
 * me.vertex.core.enchant.RunePreferenceStorage} / {@code
 * AnnouncementPreferenceStorage} -- not folded into the shared {@code
 * Storage} interface, since this data is specific to one feature.
 *
 * <p>A bind or preset is always replaced wholesale (delete every row for
 * that index, then insert the current slots) rather than patched slot by
 * slot, so a stale leftover row can never survive a reorder/removal.
 */
public final class BindStorage {
    private final Database database;

    public BindStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bind_slots ("
                    + "uuid CHAR(36) NOT NULL, bind_index TINYINT NOT NULL, slot_index TINYINT NOT NULL, "
                    + "rune_id VARCHAR(64) NOT NULL, PRIMARY KEY(uuid, bind_index, slot_index))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bind_presets ("
                    + "uuid CHAR(36) NOT NULL, preset_index TINYINT NOT NULL, bind_index TINYINT NOT NULL, "
                    + "slot_index TINYINT NOT NULL, rune_id VARCHAR(64) NOT NULL, "
                    + "PRIMARY KEY(uuid, preset_index, bind_index, slot_index))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS bind_active_preset ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY, preset_index TINYINT NOT NULL DEFAULT 0, "
                    + "dirty BOOLEAN NOT NULL DEFAULT FALSE)");
        }
    }

    /** @return bind index (1-9) -> ordered rune ids (up to 3), for every non-empty bind. */
    public Map<Integer, List<String>> loadSlots(UUID uuid) throws SQLException {
        return loadGrouped(uuid, "SELECT bind_index, slot_index, rune_id FROM bind_slots WHERE uuid = ? ORDER BY bind_index, slot_index");
    }

    public void saveBind(UUID uuid, int bindIndex, List<String> runeIds) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM bind_slots WHERE uuid = ? AND bind_index = ?")) {
                    delete.setString(1, uuid.toString());
                    delete.setInt(2, bindIndex);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO bind_slots (uuid, bind_index, slot_index, rune_id) VALUES (?, ?, ?, ?)")) {
                    for (int slot = 0; slot < runeIds.size(); slot++) {
                        insert.setString(1, uuid.toString());
                        insert.setInt(2, bindIndex);
                        insert.setInt(3, slot);
                        insert.setString(4, runeIds.get(slot));
                        insert.addBatch();
                    }
                    if (!runeIds.isEmpty()) {
                        insert.executeBatch();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /** @return preset index (1-9) -> bind index -> ordered rune ids, for every preset the player has saved. */
    public Map<Integer, Map<Integer, List<String>>> loadAllPresets(UUID uuid) throws SQLException {
        Map<Integer, Map<Integer, List<String>>> presets = new LinkedHashMap<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT preset_index, bind_index, slot_index, rune_id FROM bind_presets WHERE uuid = ? "
                        + "ORDER BY preset_index, bind_index, slot_index")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    int presetIndex = result.getInt(1);
                    int bindIndex = result.getInt(2);
                    presets.computeIfAbsent(presetIndex, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(bindIndex, ignored -> new ArrayList<>())
                            .add(result.getString(4));
                }
            }
        }
        return presets;
    }

    public void savePreset(UUID uuid, int presetIndex, Map<Integer, List<String>> binds) throws SQLException {
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM bind_presets WHERE uuid = ? AND preset_index = ?")) {
                    delete.setString(1, uuid.toString());
                    delete.setInt(2, presetIndex);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO bind_presets (uuid, preset_index, bind_index, slot_index, rune_id) VALUES (?, ?, ?, ?, ?)")) {
                    boolean any = false;
                    for (Map.Entry<Integer, List<String>> bind : binds.entrySet()) {
                        List<String> runeIds = bind.getValue();
                        for (int slot = 0; slot < runeIds.size(); slot++) {
                            insert.setString(1, uuid.toString());
                            insert.setInt(2, presetIndex);
                            insert.setInt(3, bind.getKey());
                            insert.setInt(4, slot);
                            insert.setString(5, runeIds.get(slot));
                            insert.addBatch();
                            any = true;
                        }
                    }
                    if (any) {
                        insert.executeBatch();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void deletePreset(UUID uuid, int presetIndex) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM bind_presets WHERE uuid = ? AND preset_index = ?")) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, presetIndex);
            statement.executeUpdate();
        }
    }

    /** Used to enforce a lowered rank entitlement: deletes every preset above the player's current cap, no confirmation. */
    public void deletePresetsAbove(UUID uuid, int maxPresetIndex) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM bind_presets WHERE uuid = ? AND preset_index > ?")) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, maxPresetIndex);
            statement.executeUpdate();
        }
    }

    public record ActivePreset(Integer presetIndex, boolean dirty) {
    }

    public ActivePreset loadActivePreset(UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT preset_index, dirty FROM bind_active_preset WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                int presetIndex = result.getInt(1);
                return new ActivePreset(presetIndex == 0 ? null : presetIndex, result.getBoolean(2));
            }
        }
    }

    public void saveActivePreset(UUID uuid, Integer presetIndex, boolean dirty) throws SQLException {
        String upsert = database.dialect() == Database.Dialect.SQLITE
                ? "ON CONFLICT(uuid) DO UPDATE SET preset_index = excluded.preset_index, dirty = excluded.dirty"
                : "ON DUPLICATE KEY UPDATE preset_index = VALUES(preset_index), dirty = VALUES(dirty)";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bind_active_preset (uuid, preset_index, dirty) VALUES (?, ?, ?) " + upsert)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, presetIndex == null ? 0 : presetIndex);
            statement.setBoolean(3, dirty);
            statement.executeUpdate();
        }
    }

    /** Removes every reference to {@code runeId}, for every player, from both the live layout and every saved preset. */
    public void pruneRuneEverywhere(String runeId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bind_slots WHERE rune_id = ?")) {
                statement.setString(1, runeId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bind_presets WHERE rune_id = ?")) {
                statement.setString(1, runeId);
                statement.executeUpdate();
            }
        }
    }

    private Map<Integer, List<String>> loadGrouped(UUID uuid, String sql) throws SQLException {
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.computeIfAbsent(rows.getInt(1), ignored -> new ArrayList<>()).add(rows.getString(3));
                }
            }
        }
        return result;
    }
}
