package me.vertex.core.shield;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Durable storage for current Shield activations, cooldown/eligibility
 * deadlines, staff overrides, and audit history. The legacy schedule table
 * is retained only so upgrades do not destroy older server data.
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

    private static final String CREATE_ACTIVATIONS = """
            CREATE TABLE IF NOT EXISTS faction_shield_activations (
                faction_id INT NOT NULL PRIMARY KEY,
                active_until BIGINT NOT NULL,
                cooldown_until BIGINT NOT NULL,
                eligible_at BIGINT NOT NULL,
                activated_at BIGINT NOT NULL,
                activated_by_uuid VARCHAR(36) NULL
            )""";

    private static final String CREATE_WEEKLY = """
            CREATE TABLE IF NOT EXISTS faction_shield_weekly (
                faction_id INT NOT NULL PRIMARY KEY,
                current_schedule VARCHAR(256) NOT NULL,
                current_pvp BOOLEAN NOT NULL,
                pending_schedule VARCHAR(256) NULL,
                pending_pvp BOOLEAN NULL,
                pending_activates_at BIGINT NOT NULL,
                edit_locked_until BIGINT NOT NULL,
                updated_by_uuid VARCHAR(36) NULL
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
             PreparedStatement activations = connection.prepareStatement(CREATE_ACTIVATIONS);
             PreparedStatement weekly = connection.prepareStatement(CREATE_WEEKLY);
             PreparedStatement log = connection.prepareStatement(
                     database.dialect() == Database.Dialect.SQLITE ? CREATE_LOG_SQLITE : CREATE_LOG_MYSQL)) {
            shields.executeUpdate();
            overrides.executeUpdate();
            activations.executeUpdate();
            weekly.executeUpdate();
            log.executeUpdate();
        }
    }

    public record ActivationRow(int factionId, long activeUntil, long cooldownUntil, long eligibleAt,
            long activatedAt, String activatedByUuid) { }

    public List<ActivationRow> loadAllActivations() throws SQLException {
        List<ActivationRow> rows = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT faction_id, active_until, cooldown_until, eligible_at, activated_at, activated_by_uuid "
                             + "FROM faction_shield_activations");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new ActivationRow(results.getInt(1), results.getLong(2), results.getLong(3),
                        results.getLong(4), results.getLong(5), results.getString(6)));
            }
        }
        return rows;
    }

    public void upsertActivation(ActivationRow row) throws SQLException {
        withFactionLock(row.factionId(), connection -> saveActivation(connection, row));
    }

    /**
     * Authoritatively starts a manual Shield while holding the faction and
     * activation rows. This prevents two shards from both accepting the same
     * stale ready/cooldown state and also revalidates Leader/Co-Leader access.
     */
    public ActivationMutation activate(int factionId, long now, long durationMillis,
            long cooldownMillis, String actorUuid) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!SqlSchema.lockFactionIfPresent(connection, database.dialect(), factionId)) {
                    connection.rollback();
                    return new ActivationMutation(ActivationMutationStatus.NOT_FOUND, null);
                }
                if (!isShieldEditor(connection, factionId, actorUuid)) {
                    connection.rollback();
                    return new ActivationMutation(ActivationMutationStatus.NOT_AUTHORIZED,
                            lockActivation(connection, factionId));
                }
                ActivationRow old = lockActivation(connection, factionId);
                if (old != null && now < old.eligibleAt()) {
                    connection.rollback();
                    return new ActivationMutation(ActivationMutationStatus.NOT_ELIGIBLE, old);
                }
                if (old != null && now < old.activeUntil()) {
                    connection.rollback();
                    return new ActivationMutation(ActivationMutationStatus.ALREADY_ACTIVE, old);
                }
                if (old != null && now < old.cooldownUntil()) {
                    connection.rollback();
                    return new ActivationMutation(ActivationMutationStatus.COOLDOWN, old);
                }
                long activeUntil = safeAdd(now, Math.max(0L, durationMillis));
                long cooldownUntil = safeAdd(activeUntil, Math.max(0L, cooldownMillis));
                ActivationRow next = new ActivationRow(factionId, activeUntil, cooldownUntil,
                        old == null ? now : old.eligibleAt(), now, actorUuid);
                saveActivation(connection, next);
                connection.commit();
                return new ActivationMutation(ActivationMutationStatus.OK, next);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    private ActivationRow lockActivation(Connection connection, int factionId) throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT faction_id,active_until,cooldown_until,eligible_at,activated_at,activated_by_uuid "
                        + "FROM faction_shield_activations WHERE faction_id=?" + suffix)) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new ActivationRow(row.getInt(1), row.getLong(2), row.getLong(3),
                        row.getLong(4), row.getLong(5), row.getString(6)) : null;
            }
        }
    }

    private void saveActivation(Connection connection, ActivationRow row) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO faction_shield_activations (faction_id, active_until, cooldown_until, eligible_at, activated_at, activated_by_uuid) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(faction_id) DO UPDATE SET active_until=excluded.active_until, "
                + "cooldown_until=excluded.cooldown_until, eligible_at=excluded.eligible_at, activated_at=excluded.activated_at, "
                + "activated_by_uuid=excluded.activated_by_uuid"
                : "INSERT INTO faction_shield_activations (faction_id, active_until, cooldown_until, eligible_at, activated_at, activated_by_uuid) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE active_until=VALUES(active_until), "
                + "cooldown_until=VALUES(cooldown_until), eligible_at=VALUES(eligible_at), activated_at=VALUES(activated_at), "
                + "activated_by_uuid=VALUES(activated_by_uuid)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, row.factionId());
            statement.setLong(2, row.activeUntil());
            statement.setLong(3, row.cooldownUntil());
            statement.setLong(4, row.eligibleAt());
            statement.setLong(5, row.activatedAt());
            statement.setString(6, row.activatedByUuid());
            statement.executeUpdate();
        }
    }

    public void deleteActivation(int factionId) throws SQLException {
        withFactionLock(factionId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM faction_shield_activations WHERE faction_id = ?")) {
                statement.setInt(1, factionId);
                statement.executeUpdate();
            }
        });
    }

    public record WeeklyRow(int factionId,String currentSchedule,boolean currentPvp,String pendingSchedule,
            Boolean pendingPvp,long pendingActivatesAt,long editLockedUntil,String updatedByUuid){}

    public List<WeeklyRow> loadWeekly()throws SQLException{
        List<WeeklyRow> rows=new ArrayList<>();
        try(Connection connection=database.getConnection();PreparedStatement statement=connection.prepareStatement(
                "SELECT faction_id,current_schedule,current_pvp,pending_schedule,pending_pvp,pending_activates_at,edit_locked_until,updated_by_uuid FROM faction_shield_weekly");ResultSet results=statement.executeQuery()){
            while(results.next()){boolean pending=results.getBoolean(5);Boolean pendingPvp=results.wasNull()?null:pending;rows.add(new WeeklyRow(results.getInt(1),results.getString(2),results.getBoolean(3),results.getString(4),pendingPvp,results.getLong(6),results.getLong(7),results.getString(8)));}
        }
        return rows;
    }

    public void saveWeekly(WeeklyRow row)throws SQLException{
        withFactionLock(row.factionId(), connection -> saveWeekly(connection,row));
    }

    /** Network-wide schedule edit lock and delayed activation in one commit. */
    public WeeklyMutation replaceWeekly(int factionId, String changedSchedule, boolean changedPvp,
            long now, long activationDelayMillis, long editLockMillis, String updatedBy,
            boolean adminBypass) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!SqlSchema.lockFactionIfPresent(connection, database.dialect(), factionId)) {
                    throw new SQLException("Faction no longer exists");
                }
                ensureWeekly(connection, factionId);
                WeeklyRow old = lockWeekly(connection, factionId);
                if (old == null) throw new SQLException("Shield weekly row disappeared while locked.");
                if (!adminBypass && !isShieldEditor(connection, factionId, updatedBy)) {
                    connection.rollback();
                    return new WeeklyMutation(WeeklyMutationStatus.NOT_AUTHORIZED, old);
                }
                if (!adminBypass && now < old.editLockedUntil()) {
                    connection.rollback();
                    return new WeeklyMutation(WeeklyMutationStatus.LOCKED, old);
                }
                long activatesAt = adminBypass ? now : safeAdd(now, activationDelayMillis);
                long lockedUntil = adminBypass ? old.editLockedUntil() : safeAdd(now, editLockMillis);
                WeeklyRow next = activatesAt <= now
                        ? new WeeklyRow(factionId, changedSchedule, changedPvp, null, null,
                                0L, lockedUntil, updatedBy)
                        : new WeeklyRow(factionId, old.currentSchedule(), old.currentPvp(),
                                changedSchedule, changedPvp, activatesAt, lockedUntil, updatedBy);
                saveWeekly(connection, next);
                connection.commit();
                return new WeeklyMutation(WeeklyMutationStatus.OK, next);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Promotes only the exact pending revision observed by this shard. */
    public boolean promoteWeekly(int factionId, String expectedPending, long expectedActivatesAt)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!SqlSchema.lockFactionIfPresent(connection, database.dialect(), factionId)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE faction_shield_weekly SET "
                                + "current_schedule=pending_schedule,current_pvp=pending_pvp,"
                                + "pending_schedule=NULL,pending_pvp=NULL,pending_activates_at=0 "
                                + "WHERE faction_id=? AND pending_schedule=? AND pending_activates_at=?")) {
                    statement.setInt(1, factionId);
                    statement.setString(2, expectedPending);
                    statement.setLong(3, expectedActivatesAt);
                    boolean changed = statement.executeUpdate() == 1;
                    connection.commit();
                    return changed;
                }
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    private void ensureWeekly(Connection connection, int factionId) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO faction_shield_weekly(faction_id,current_schedule,current_pvp,pending_schedule,pending_pvp,pending_activates_at,edit_locked_until,updated_by_uuid) VALUES(?,?,FALSE,NULL,NULL,0,0,NULL) ON CONFLICT(faction_id) DO NOTHING"
                : "INSERT IGNORE INTO faction_shield_weekly(faction_id,current_schedule,current_pvp,pending_schedule,pending_pvp,pending_activates_at,edit_locked_until,updated_by_uuid) VALUES(?,?,FALSE,NULL,NULL,0,0,NULL)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, factionId);
            statement.setString(2, ShieldSchedule.empty().encode());
            statement.executeUpdate();
        }
    }

    private WeeklyRow lockWeekly(Connection connection, int factionId) throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT faction_id,current_schedule,current_pvp,pending_schedule,pending_pvp,"
                        + "pending_activates_at,edit_locked_until,updated_by_uuid "
                        + "FROM faction_shield_weekly WHERE faction_id=?" + suffix)) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                boolean rawPending = row.getBoolean(5);
                Boolean pendingPvp = row.wasNull() ? null : rawPending;
                return new WeeklyRow(row.getInt(1), row.getString(2), row.getBoolean(3),
                        row.getString(4), pendingPvp, row.getLong(6), row.getLong(7), row.getString(8));
            }
        }
    }

    private boolean isShieldEditor(Connection connection, int factionId, String actorUuid)
            throws SQLException {
        // Isolated Shield-storage tests and pre-native-faction migrations may
        // initialize this table before the faction schema exists. Runtime
        // authorization is enforced once that authoritative table is present.
        if (!SqlSchema.tableExists(connection, "vertex_faction_members")) return true;
        if (actorUuid == null || actorUuid.isBlank()) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM vertex_faction_members WHERE player_uuid=? AND faction_id=?")) {
            statement.setString(1, actorUuid);
            statement.setInt(2, factionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return false;
                String role = row.getString(1);
                return "LEADER".equalsIgnoreCase(role) || "COLEADER".equalsIgnoreCase(role);
            }
        }
    }

    private void saveWeekly(Connection connection, WeeklyRow row) throws SQLException {
        String sql=database.dialect()==Database.Dialect.SQLITE
                ?"INSERT INTO faction_shield_weekly(faction_id,current_schedule,current_pvp,pending_schedule,pending_pvp,pending_activates_at,edit_locked_until,updated_by_uuid) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(faction_id) DO UPDATE SET current_schedule=excluded.current_schedule,current_pvp=excluded.current_pvp,pending_schedule=excluded.pending_schedule,pending_pvp=excluded.pending_pvp,pending_activates_at=excluded.pending_activates_at,edit_locked_until=excluded.edit_locked_until,updated_by_uuid=excluded.updated_by_uuid"
                :"INSERT INTO faction_shield_weekly(faction_id,current_schedule,current_pvp,pending_schedule,pending_pvp,pending_activates_at,edit_locked_until,updated_by_uuid) VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE current_schedule=VALUES(current_schedule),current_pvp=VALUES(current_pvp),pending_schedule=VALUES(pending_schedule),pending_pvp=VALUES(pending_pvp),pending_activates_at=VALUES(pending_activates_at),edit_locked_until=VALUES(edit_locked_until),updated_by_uuid=VALUES(updated_by_uuid)";
        try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setInt(1,row.factionId());statement.setString(2,row.currentSchedule());statement.setBoolean(3,row.currentPvp());statement.setString(4,row.pendingSchedule());if(row.pendingPvp()==null)statement.setNull(5,java.sql.Types.BOOLEAN);else statement.setBoolean(5,row.pendingPvp());statement.setLong(6,row.pendingActivatesAt());statement.setLong(7,row.editLockedUntil());statement.setString(8,row.updatedByUuid());statement.executeUpdate();}
    }

    private static long safeAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    public enum WeeklyMutationStatus { OK, LOCKED, NOT_AUTHORIZED }
    public record WeeklyMutation(WeeklyMutationStatus status, WeeklyRow row) { }
    public enum ActivationMutationStatus {
        OK, ALREADY_ACTIVE, COOLDOWN, NOT_ELIGIBLE, NOT_AUTHORIZED, NOT_FOUND
    }
    public record ActivationMutation(ActivationMutationStatus status, ActivationRow row) { }

    /** Removes every live/legacy Shield row for one faction atomically. */
    public void deleteFactionState(int factionId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("faction_shield_activations", "faction_shield_overrides",
                        "faction_shields", "faction_shield_weekly")) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM " + table + " WHERE faction_id = ?")) {
                        statement.setInt(1, factionId);
                        statement.executeUpdate();
                    }
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
        withFactionLock(row.factionId(), connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
        });
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
        withFactionLock(row.factionId(), connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, row.factionId());
                statement.setString(2, row.forcedState());
                setNullableLong(statement, 3, row.frozenRemainingMillis());
                statement.setString(4, row.setByUuid());
                statement.setLong(5, row.setAt());
                statement.executeUpdate();
            }
        });
    }

    public void deleteOverride(int factionId) throws SQLException {
        withFactionLock(factionId, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM faction_shield_overrides WHERE faction_id = ?")) {
                statement.setInt(1, factionId);
                statement.executeUpdate();
            }
        });
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

    private void withFactionLock(int factionId, SqlWrite write) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!SqlSchema.lockFactionIfPresent(connection, database.dialect(), factionId)) {
                    throw new SQLException("Faction no longer exists");
                }
                write.run(connection);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWrite { void run(Connection connection) throws SQLException; }
}
