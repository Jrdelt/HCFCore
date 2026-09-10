package me.vertex.core.shield;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Durable storage for Faction Shield schedules, pending-schedule activation
 * state, the new-faction eligibility timer, staff overrides, and the
 * write-only Shield event log (kept for a future {@code /f logs} reader --
 * see {@code ShieldManager}'s class doc).
 *
 * <p>Mirrors {@code ClaimStorage}'s shape: plain dialect-agnostic CREATE
 * TABLE statements, dialect-specific SQL only where an autoincrement id or
 * an upsert needs it.
 */
public final class ShieldStorage {

    private static final String CREATE_SHIELDS = """
            CREATE TABLE IF NOT EXISTS faction_shields (
                faction_id INT NOT NULL PRIMARY KEY,
                schedule_start_minute INT NULL,
                schedule_duration_minutes INT NULL,
                pending_start_minute INT NULL,
                pending_duration_minutes INT NULL,
                pending_activates_at BIGINT NULL,
                frozen_resume_until BIGINT NULL,
                new_faction_eligible_at BIGINT NOT NULL
            )""";

    private static final String CREATE_OVERRIDES = """
            CREATE TABLE IF NOT EXISTS faction_shield_overrides (
                faction_id INT NOT NULL PRIMARY KEY,
                forced_state VARCHAR(16) NOT NULL,
                frozen_remaining_millis BIGINT NULL,
                set_by_uuid VARCHAR(36) NULL,
                set_at BIGINT NOT NULL
            )""";

    private static final String CREATE_LOG_MYSQL = """
            CREATE TABLE IF NOT EXISTS faction_shield_log (
                id INT AUTO_INCREMENT PRIMARY KEY,
                faction_id INT NOT NULL,
                action VARCHAR(64) NOT NULL,
                actor_uuid VARCHAR(36) NULL,
                details VARCHAR(512) NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_LOG_SQLITE = """
            CREATE TABLE IF NOT EXISTS faction_shield_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                faction_id INT NOT NULL,
                action VARCHAR(64) NOT NULL,
                actor_uuid VARCHAR(36) NULL,
                details VARCHAR(512) NULL,
                created_at BIGINT NOT NULL
            )""";

    private final Database database;

    public ShieldStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement shields = connection.prepareStatement(CREATE_SHIELDS);
             PreparedStatement overrides = connection.prepareStatement(CREATE_OVERRIDES);
             PreparedStatement log = connection.prepareStatement(
                     database.dialect() == Database.Dialect.SQLITE ? CREATE_LOG_SQLITE : CREATE_LOG_MYSQL)) {
            shields.executeUpdate();
            overrides.executeUpdate();
            log.executeUpdate();
        }
    }

    // ---- Shield rows ----

    public record ShieldRow(int factionId, Integer scheduleStartMinute, Integer scheduleDurationMinutes,
            Integer pendingStartMinute, Integer pendingDurationMinutes, Long pendingActivatesAt,
            Long frozenResumeUntil, long newFactionEligibleAt) { }

    public List<ShieldRow> loadAll() throws SQLException {
        List<ShieldRow> rows = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT faction_id, schedule_start_minute, schedule_duration_minutes, pending_start_minute, "
                             + "pending_duration_minutes, pending_activates_at, frozen_resume_until, new_faction_eligible_at "
                             + "FROM faction_shields");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(readRow(results));
            }
        }
        return rows;
    }

    private ShieldRow readRow(ResultSet results) throws SQLException {
        return new ShieldRow(
                results.getInt("faction_id"),
                nullableInt(results, "schedule_start_minute"),
                nullableInt(results, "schedule_duration_minutes"),
                nullableInt(results, "pending_start_minute"),
                nullableInt(results, "pending_duration_minutes"),
                nullableLong(results, "pending_activates_at"),
                nullableLong(results, "frozen_resume_until"),
                results.getLong("new_faction_eligible_at"));
    }

    private static Integer nullableInt(ResultSet results, String column) throws SQLException {
        int value = results.getInt(column);
        return results.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet results, String column) throws SQLException {
        long value = results.getLong(column);
        return results.wasNull() ? null : value;
    }

    public void upsertRow(ShieldRow row) throws SQLException {
        boolean sqlite = database.dialect() == Database.Dialect.SQLITE;
        String sql = sqlite
                ? "INSERT INTO faction_shields (faction_id, schedule_start_minute, schedule_duration_minutes, "
                + "pending_start_minute, pending_duration_minutes, pending_activates_at, frozen_resume_until, new_faction_eligible_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(faction_id) DO UPDATE SET "
                + "schedule_start_minute = excluded.schedule_start_minute, "
                + "schedule_duration_minutes = excluded.schedule_duration_minutes, "
                + "pending_start_minute = excluded.pending_start_minute, "
                + "pending_duration_minutes = excluded.pending_duration_minutes, "
                + "pending_activates_at = excluded.pending_activates_at, "
                + "frozen_resume_until = excluded.frozen_resume_until, "
                + "new_faction_eligible_at = excluded.new_faction_eligible_at"
                : "INSERT INTO faction_shields (faction_id, schedule_start_minute, schedule_duration_minutes, "
                + "pending_start_minute, pending_duration_minutes, pending_activates_at, frozen_resume_until, new_faction_eligible_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                + "schedule_start_minute = VALUES(schedule_start_minute), "
                + "schedule_duration_minutes = VALUES(schedule_duration_minutes), "
                + "pending_start_minute = VALUES(pending_start_minute), "
                + "pending_duration_minutes = VALUES(pending_duration_minutes), "
                + "pending_activates_at = VALUES(pending_activates_at), "
                + "frozen_resume_until = VALUES(frozen_resume_until), "
                + "new_faction_eligible_at = VALUES(new_faction_eligible_at)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, row.factionId());
            setNullableInt(statement, 2, row.scheduleStartMinute());
            setNullableInt(statement, 3, row.scheduleDurationMinutes());
            setNullableInt(statement, 4, row.pendingStartMinute());
            setNullableInt(statement, 5, row.pendingDurationMinutes());
            setNullableLong(statement, 6, row.pendingActivatesAt());
            setNullableLong(statement, 7, row.frozenResumeUntil());
            statement.setLong(8, row.newFactionEligibleAt());
            statement.executeUpdate();
        }
    }

    public void deleteRow(int factionId) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM faction_shields WHERE faction_id = ?")) {
            statement.setInt(1, factionId);
            statement.executeUpdate();
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    // ---- Overrides ----

    public record OverrideRow(int factionId, String forcedState, Long frozenRemainingMillis, String setByUuid, long setAt) { }

    public Optional<OverrideRow> loadOverride(int factionId) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT faction_id, forced_state, frozen_remaining_millis, set_by_uuid, set_at "
                             + "FROM faction_shield_overrides WHERE faction_id = ?")) {
            statement.setInt(1, factionId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return Optional.empty();
                }
                return Optional.of(new OverrideRow(results.getInt(1), results.getString(2),
                        nullableLong(results, "frozen_remaining_millis"), results.getString(4), results.getLong(5)));
            }
        }
    }

    public List<OverrideRow> loadAllOverrides() throws SQLException {
        List<OverrideRow> rows = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT faction_id, forced_state, frozen_remaining_millis, set_by_uuid, set_at FROM faction_shield_overrides");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new OverrideRow(results.getInt(1), results.getString(2),
                        nullableLong(results, "frozen_remaining_millis"), results.getString(4), results.getLong(5)));
            }
        }
        return rows;
    }

    public void upsertOverride(OverrideRow row) throws SQLException {
        boolean sqlite = database.dialect() == Database.Dialect.SQLITE;
        String sql = sqlite
                ? "INSERT INTO faction_shield_overrides (faction_id, forced_state, frozen_remaining_millis, set_by_uuid, set_at) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT(faction_id) DO UPDATE SET forced_state = excluded.forced_state, "
                + "frozen_remaining_millis = excluded.frozen_remaining_millis, set_by_uuid = excluded.set_by_uuid, set_at = excluded.set_at"
                : "INSERT INTO faction_shield_overrides (faction_id, forced_state, frozen_remaining_millis, set_by_uuid, set_at) "
                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE forced_state = VALUES(forced_state), "
                + "frozen_remaining_millis = VALUES(frozen_remaining_millis), set_by_uuid = VALUES(set_by_uuid), set_at = VALUES(set_at)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, row.factionId());
            statement.setString(2, row.forcedState());
            setNullableLong(statement, 3, row.frozenRemainingMillis());
            statement.setString(4, row.setByUuid());
            statement.setLong(5, row.setAt());
            statement.executeUpdate();
        }
    }

    public void deleteOverride(int factionId) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM faction_shield_overrides WHERE faction_id = ?")) {
            statement.setInt(1, factionId);
            statement.executeUpdate();
        }
    }

    // ---- Log (write-only from this phase; a future /f logs reads it) ----

    public void insertLog(int factionId, String action, String actorUuid, String details, long createdAt) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO faction_shield_log (faction_id, action, actor_uuid, details, created_at) "
                             + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setInt(1, factionId);
            statement.setString(2, action);
            statement.setString(3, actorUuid);
            statement.setString(4, details);
            statement.setLong(5, createdAt);
            statement.executeUpdate();
        }
    }
}
