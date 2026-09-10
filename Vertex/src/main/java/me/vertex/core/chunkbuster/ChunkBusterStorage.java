package me.vertex.core.chunkbuster;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable storage for Chunk Busters: the restart-safe "operation in
 * progress" lock and the write-only use log (kept for a future
 * {@code /f logs} reader, same as
 * {@code ShieldStorage.insertLog} -- see that class's doc for why this
 * phase only writes rows rather than building a reader command).
 *
 * <p>Mirrors {@code ClaimStorage}/{@code ShieldStorage}'s shape: plain
 * dialect-agnostic CREATE TABLE statements, dialect-specific SQL only where
 * an autoincrement id is needed.
 *
 * <p><b>Why {@code chunk_buster_operations} exists at all.</b> Batched
 * removal runs over many ticks via a {@code BukkitTask}, which does not
 * survive a restart. If the server goes down mid-operation, the in-memory
 * queue and area lock are simply gone on the next boot -- but nothing
 * would otherwise record that an operation had been left half-finished.
 * A row is inserted here, synchronously, *before* any block is touched;
 * {@code ChunkBusterManager.recoverAbandonedOperations()} runs once at
 * startup, finds any leftover row (a completed operation always deletes
 * its own row first), logs it, and deletes it -- the operation is never
 * resumed, and already-removed blocks simply stay removed. The row's
 * existence *is* the persisted lock; deleting it *is* "clearing the lock
 * on startup."
 */
public final class ChunkBusterStorage {

    /** Non-terminal status a leftover row is always found in -- nothing else is ever written here. */
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";

    private static final String CREATE_OPERATIONS_MYSQL = """
            CREATE TABLE IF NOT EXISTS chunk_buster_operations (
                id INT AUTO_INCREMENT PRIMARY KEY,
                world VARCHAR(64) NOT NULL,
                chunk_x INT NOT NULL,
                chunk_z INT NOT NULL,
                type VARCHAR(32) NOT NULL,
                started_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL
            )""";
    private static final String CREATE_OPERATIONS_SQLITE = """
            CREATE TABLE IF NOT EXISTS chunk_buster_operations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                world VARCHAR(64) NOT NULL,
                chunk_x INT NOT NULL,
                chunk_z INT NOT NULL,
                type VARCHAR(32) NOT NULL,
                started_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL
            )""";

    private static final String CREATE_ROLE_PERMISSIONS = """
            CREATE TABLE IF NOT EXISTS chunk_buster_role_permissions (
                faction_id INT NOT NULL,
                role VARCHAR(16) NOT NULL,
                allowed BOOLEAN NOT NULL,
                PRIMARY KEY (faction_id, role)
            )""";

    private static final String CREATE_LOG_MYSQL = """
            CREATE TABLE IF NOT EXISTS chunk_buster_log (
                id INT AUTO_INCREMENT PRIMARY KEY,
                player_uuid VARCHAR(36) NOT NULL,
                type VARCHAR(32) NOT NULL,
                world VARCHAR(64) NOT NULL,
                x INT NOT NULL,
                y INT NOT NULL,
                z INT NOT NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_LOG_SQLITE = """
            CREATE TABLE IF NOT EXISTS chunk_buster_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid VARCHAR(36) NOT NULL,
                type VARCHAR(32) NOT NULL,
                world VARCHAR(64) NOT NULL,
                x INT NOT NULL,
                y INT NOT NULL,
                z INT NOT NULL,
                created_at BIGINT NOT NULL
            )""";

    private final Database database;

    public ChunkBusterStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        boolean sqlite = database.dialect() == Database.Dialect.SQLITE;
        try (Connection connection = database.getConnection();
                PreparedStatement operations = connection
                        .prepareStatement(sqlite ? CREATE_OPERATIONS_SQLITE : CREATE_OPERATIONS_MYSQL);
                PreparedStatement roles = connection.prepareStatement(CREATE_ROLE_PERMISSIONS);
                PreparedStatement log = connection.prepareStatement(sqlite ? CREATE_LOG_SQLITE : CREATE_LOG_MYSQL)) {
            operations.executeUpdate();
            roles.executeUpdate();
            log.executeUpdate();
        }
    }

    // ---- Operations (the persisted, restart-safe lock) ----

    public record OperationRow(long id, String world, int chunkX, int chunkZ, String type, long startedAt,
            String status) {
    }

    /** Inserts a new in-progress row and returns its generated id. */
    public long insertOperation(String world, int chunkX, int chunkZ, String type, long startedAt, String status)
            throws SQLException {
        try (Connection connection = database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chunk_buster_operations (world, chunk_x, chunk_z, type, started_at, status) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            statement.setString(4, type);
            statement.setLong(5, startedAt);
            statement.setString(6, status);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Insert into chunk_buster_operations returned no generated id.");
    }

    public List<OperationRow> loadOperations() throws SQLException {
        List<OperationRow> rows = new ArrayList<>();
        try (Connection connection = database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, world, chunk_x, chunk_z, type, started_at, status FROM chunk_buster_operations");
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new OperationRow(results.getLong(1), results.getString(2), results.getInt(3),
                        results.getInt(4), results.getString(5), results.getLong(6), results.getString(7)));
            }
        }
        return rows;
    }

    public void deleteOperation(long id) throws SQLException {
        try (Connection connection = database.getConnection();
                PreparedStatement statement = connection
                        .prepareStatement("DELETE FROM chunk_buster_operations WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    // ---- Legacy per-faction role rows ----

    // Kept readable for existing databases and the standalone-manager
    // compatibility constructor. Production authorization uses the shared
    // /f permissions GUI through RallyManager instead.

    public record RolePermissionRow(int factionId, String role, boolean allowed) {
    }

    public List<RolePermissionRow> loadAllRolePermissions() throws SQLException {
        List<RolePermissionRow> rows = new ArrayList<>();
        try (Connection connection = database.getConnection();
                PreparedStatement statement = connection
                        .prepareStatement("SELECT faction_id, role, allowed FROM chunk_buster_role_permissions");
                ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new RolePermissionRow(results.getInt(1), results.getString(2), results.getBoolean(3)));
            }
        }
        return rows;
    }

    public void upsertRolePermission(int factionId, String role, boolean allowed) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO chunk_buster_role_permissions (faction_id, role, allowed) VALUES (?, ?, ?) "
                        + "ON CONFLICT(faction_id, role) DO UPDATE SET allowed = excluded.allowed"
                : "INSERT INTO chunk_buster_role_permissions (faction_id, role, allowed) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE allowed = VALUES(allowed)";
        try (Connection connection = database.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, factionId);
            statement.setString(2, role);
            statement.setBoolean(3, allowed);
            statement.executeUpdate();
        }
    }

    // ---- Use log (write-only from this phase; a future /f logs reads it -- never a block count, per spec) ----

    public void insertLog(String playerUuid, String type, String world, int x, int y, int z, long createdAt)
            throws SQLException {
        try (Connection connection = database.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chunk_buster_log (player_uuid, type, world, x, y, z, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, playerUuid);
            statement.setString(2, type);
            statement.setString(3, world);
            statement.setInt(4, x);
            statement.setInt(5, y);
            statement.setInt(6, z);
            statement.setLong(7, createdAt);
            statement.executeUpdate();
        }
    }
}
