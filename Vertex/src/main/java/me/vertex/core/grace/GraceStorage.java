package me.vertex.core.grace;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Durable singleton state and audit history for global faction Grace. */
public final class GraceStorage {
    private static final String CREATE_STATE = """
            CREATE TABLE IF NOT EXISTS faction_grace (
                singleton_key INT NOT NULL PRIMARY KEY,
                active_until BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                updated_by_uuid VARCHAR(36) NULL
            )""";
    private static final String CREATE_LOG_SQLITE = """
            CREATE TABLE IF NOT EXISTS faction_grace_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                action VARCHAR(16) NOT NULL,
                actor_uuid VARCHAR(36) NULL,
                details VARCHAR(255) NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_LOG_MYSQL = CREATE_LOG_SQLITE.replace(
            "id INTEGER PRIMARY KEY AUTOINCREMENT", "id INT AUTO_INCREMENT PRIMARY KEY");

    private final Database database;

    public GraceStorage(Database database) { this.database = database; }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement state = connection.prepareStatement(CREATE_STATE);
             PreparedStatement log = connection.prepareStatement(
                     database.dialect() == Database.Dialect.SQLITE ? CREATE_LOG_SQLITE : CREATE_LOG_MYSQL)) {
            state.executeUpdate();
            log.executeUpdate();
        }
    }

    public record State(long activeUntil, long updatedAt, String updatedByUuid) { }

    public Optional<State> load() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT active_until, updated_at, updated_by_uuid FROM faction_grace WHERE singleton_key=1");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? Optional.of(new State(result.getLong(1), result.getLong(2), result.getString(3)))
                    : Optional.empty();
        }
    }

    public void save(State state) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO faction_grace (singleton_key, active_until, updated_at, updated_by_uuid) VALUES (1, ?, ?, ?) "
                + "ON CONFLICT(singleton_key) DO UPDATE SET active_until=excluded.active_until, updated_at=excluded.updated_at, updated_by_uuid=excluded.updated_by_uuid"
                : "INSERT INTO faction_grace (singleton_key, active_until, updated_at, updated_by_uuid) VALUES (1, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE active_until=VALUES(active_until), updated_at=VALUES(updated_at), updated_by_uuid=VALUES(updated_by_uuid)";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, state.activeUntil());
            statement.setLong(2, state.updatedAt());
            statement.setString(3, state.updatedByUuid());
            statement.executeUpdate();
        }
    }

    public void log(String action, String actorUuid, String details, long createdAt) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO faction_grace_log (action, actor_uuid, details, created_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, action);
            statement.setString(2, actorUuid);
            statement.setString(3, details);
            statement.setLong(4, createdAt);
            statement.executeUpdate();
        }
    }

    /** Commits the state and matching audit row together. */
    public void saveAndLog(State state, String action, String details) throws SQLException {
        String upsert = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO faction_grace (singleton_key, active_until, updated_at, updated_by_uuid) VALUES (1, ?, ?, ?) "
                + "ON CONFLICT(singleton_key) DO UPDATE SET active_until=excluded.active_until, updated_at=excluded.updated_at, updated_by_uuid=excluded.updated_by_uuid"
                : "INSERT INTO faction_grace (singleton_key, active_until, updated_at, updated_by_uuid) VALUES (1, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE active_until=VALUES(active_until), updated_at=VALUES(updated_at), updated_by_uuid=VALUES(updated_by_uuid)";
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement save = connection.prepareStatement(upsert);
                 PreparedStatement log = connection.prepareStatement(
                         "INSERT INTO faction_grace_log (action, actor_uuid, details, created_at) VALUES (?, ?, ?, ?)")) {
                save.setLong(1, state.activeUntil()); save.setLong(2, state.updatedAt()); save.setString(3, state.updatedByUuid());
                save.executeUpdate();
                log.setString(1, action); log.setString(2, state.updatedByUuid()); log.setString(3, details); log.setLong(4, state.updatedAt());
                log.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally { connection.setAutoCommit(autoCommit); }
        }
    }
}
