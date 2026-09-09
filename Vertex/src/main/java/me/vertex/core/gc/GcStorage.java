package me.vertex.core.gc;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable storage for the GC ledger: every player's balance, the permanent
 * audit log of every change, and staff-issued redeem codes. Follows
 * {@code CoinflipStorage}'s exact shape -- dialect-specific
 * {@code CREATE TABLE IF NOT EXISTS} pairs chosen in the constructor, an
 * {@link #init()} method, and {@code setAutoCommit(false)} plus commit/
 * rollback for the multi-statement writes that must never partially apply.
 *
 * <p>Every credit/debit is written as a <em>relative</em> SQL delta
 * ({@code balance = balance + ?}), never as an absolute value computed in
 * Java from a possibly-stale in-memory snapshot. That is what lets
 * {@link GcManager} apply a balance change to its in-memory cache the
 * instant a command runs, in parallel with the async database write it
 * queues behind it, without an ordering hazard: two relative deltas commute
 * no matter which one physically reaches the database first, and the
 * per-player write chain in {@link GcManager} still applies them to the
 * database in the same order they were queued. Staff {@code SET}/{@code
 * ZERO} are the one deliberate exception -- an admin overwriting a balance
 * to an exact value is supposed to be absolute, not additive.
 */
public final class GcStorage {

    private static final String CREATE_BALANCES = """
            CREATE TABLE IF NOT EXISTS gc_balances (
                uuid CHAR(36) NOT NULL PRIMARY KEY,
                balance BIGINT NOT NULL DEFAULT 0,
                updated_at BIGINT NOT NULL
            )""";

    private static final String CREATE_LOG_MYSQL = """
            CREATE TABLE IF NOT EXISTS gc_log (
                id INT AUTO_INCREMENT PRIMARY KEY,
                actor_uuid CHAR(36) NULL,
                target_uuid CHAR(36) NOT NULL,
                action VARCHAR(32) NOT NULL,
                amount BIGINT NOT NULL,
                balance_after BIGINT NOT NULL,
                note VARCHAR(512) NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_LOG_SQLITE = """
            CREATE TABLE IF NOT EXISTS gc_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                actor_uuid CHAR(36) NULL,
                target_uuid CHAR(36) NOT NULL,
                action VARCHAR(32) NOT NULL,
                amount BIGINT NOT NULL,
                balance_after BIGINT NOT NULL,
                note VARCHAR(512) NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_LOG_TARGET_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_gc_log_target ON gc_log (target_uuid, created_at DESC)";
    private static final String CREATE_LOG_ACTOR_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_gc_log_actor ON gc_log (actor_uuid, created_at DESC)";

    private static final String CREATE_REDEEM_CODES = """
            CREATE TABLE IF NOT EXISTS gc_redeem_codes (
                code VARCHAR(32) NOT NULL PRIMARY KEY,
                amount BIGINT NOT NULL,
                uses_remaining INT NOT NULL,
                created_by_uuid CHAR(36) NOT NULL,
                created_at BIGINT NOT NULL,
                expires_at BIGINT NULL,
                status VARCHAR(16) NOT NULL
            )""";

    private final Database database;
    private final String createLog;
    private final String upsertDeltaSql;
    private final String upsertAbsoluteSql;

    public GcStorage(Database database) {
        this.database = database;
        boolean sqlite = database.dialect() == Database.Dialect.SQLITE;
        this.createLog = sqlite ? CREATE_LOG_SQLITE : CREATE_LOG_MYSQL;
        this.upsertDeltaSql = sqlite
                ? "INSERT INTO gc_balances (uuid, balance, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET balance = balance + excluded.balance, updated_at = excluded.updated_at"
                : "INSERT INTO gc_balances (uuid, balance, updated_at) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE balance = balance + VALUES(balance), updated_at = VALUES(updated_at)";
        this.upsertAbsoluteSql = sqlite
                ? "INSERT INTO gc_balances (uuid, balance, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance, updated_at = excluded.updated_at"
                : "INSERT INTO gc_balances (uuid, balance, updated_at) VALUES (?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE balance = VALUES(balance), updated_at = VALUES(updated_at)";
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(CREATE_BALANCES);
            statement.executeUpdate(createLog);
            statement.executeUpdate(CREATE_LOG_TARGET_INDEX);
            statement.executeUpdate(CREATE_LOG_ACTOR_INDEX);
            statement.executeUpdate(CREATE_REDEEM_CODES);
        }
    }

    /** Every balance, loaded once at startup -- the in-memory cache's initial state. */
    public Map<UUID, Long> loadAllBalances() throws SQLException {
        Map<UUID, Long> balances = new HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT uuid, balance FROM gc_balances");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    balances.put(UUID.fromString(results.getString("uuid")), results.getLong("balance"));
                } catch (IllegalArgumentException ignored) {
                    // Skip a malformed row rather than failing every other balance.
                }
            }
        }
        return balances;
    }

    /**
     * Applies a relative balance change and writes its audit row in one
     * transaction. {@code delta} may be negative (a debit); the caller is
     * responsible for having already verified sufficiency against its own
     * in-memory cache -- this never rejects a delta that would go negative,
     * since the whole point of a relative SQL update is that it always
     * commits regardless of write ordering.
     *
     * @return the resulting balance, for an accurate audit row.
     */
    public long applyDelta(UUID targetUuid, UUID actorUuid, GcAction action, long delta, String note, long now)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement upsert = connection.prepareStatement(upsertDeltaSql)) {
                    upsert.setString(1, targetUuid.toString());
                    upsert.setLong(2, delta);
                    upsert.setLong(3, now);
                    upsert.executeUpdate();
                }
                long resultingBalance = readBalance(connection, targetUuid);
                insertLogRow(connection, actorUuid, targetUuid, action, Math.abs(delta), resultingBalance, note, now);
                connection.commit();
                return resultingBalance;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    /** Overwrites a balance to an exact value (staff set/zero) and writes its audit row in one transaction. */
    public void setAbsolute(UUID targetUuid, UUID actorUuid, GcAction action, long newBalance, String note, long now)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement upsert = connection.prepareStatement(upsertAbsoluteSql)) {
                    upsert.setString(1, targetUuid.toString());
                    upsert.setLong(2, newBalance);
                    upsert.setLong(3, now);
                    upsert.executeUpdate();
                }
                insertLogRow(connection, actorUuid, targetUuid, action, newBalance, newBalance, note, now);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private long readBalance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT balance FROM gc_balances WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet results = select.executeQuery()) {
                return results.next() ? results.getLong("balance") : 0L;
            }
        }
    }

    private void insertLogRow(Connection connection, UUID actorUuid, UUID targetUuid, GcAction action, long amount,
            long balanceAfter, String note, long now) throws SQLException {
        String sql = "INSERT INTO gc_log (actor_uuid, target_uuid, action, amount, balance_after, note, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (actorUuid == null) {
                statement.setNull(1, Types.CHAR);
            } else {
                statement.setString(1, actorUuid.toString());
            }
            statement.setString(2, targetUuid.toString());
            statement.setString(3, action.name());
            statement.setLong(4, amount);
            statement.setLong(5, balanceAfter);
            if (note == null) {
                statement.setNull(6, Types.VARCHAR);
            } else {
                statement.setString(6, note);
            }
            statement.setLong(7, now);
            statement.executeUpdate();
        }
    }

    /** Newest first. A non-null {@code playerFilter} shows only that player's own balance history. */
    public List<GcLogEntry> loadLog(UUID playerFilter, int limit, int offset) throws SQLException {
        List<GcLogEntry> entries = new ArrayList<>();
        // id DESC breaks ties between rows written in the same millisecond, so ordering
        // matches insertion order rather than depending on clock resolution.
        String sql = playerFilter == null
                ? "SELECT * FROM gc_log ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM gc_log WHERE target_uuid = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (playerFilter != null) {
                statement.setString(index++, playerFilter.toString());
            }
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    entries.add(readLogEntry(results));
                }
            }
        }
        return entries;
    }

    private GcLogEntry readLogEntry(ResultSet results) throws SQLException {
        String actor = results.getString("actor_uuid");
        String note = results.getString("note");
        return new GcLogEntry(
                results.getLong("id"),
                actor == null ? null : UUID.fromString(actor),
                UUID.fromString(results.getString("target_uuid")),
                GcAction.valueOf(results.getString("action")),
                results.getLong("amount"),
                results.getLong("balance_after"),
                note,
                results.getLong("created_at"));
    }

    // ---- Redeem codes ----

    public void insertRedeemCode(String code, long amount, int uses, UUID createdByUuid, long createdAt, Long expiresAt)
            throws SQLException {
        String sql = "INSERT INTO gc_redeem_codes (code, amount, uses_remaining, created_by_uuid, created_at, "
                + "expires_at, status) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            statement.setLong(2, amount);
            statement.setInt(3, uses);
            statement.setString(4, createdByUuid.toString());
            statement.setLong(5, createdAt);
            if (expiresAt == null) {
                statement.setNull(6, Types.BIGINT);
            } else {
                statement.setLong(6, expiresAt);
            }
            statement.executeUpdate();
        }
    }

    public boolean codeExists(String code) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM gc_redeem_codes WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    /**
     * Atomically consumes one use of a redeem code and credits its amount,
     * all in a single transaction -- the conditional
     * {@code uses_remaining > 0} update is what guards against the same
     * code being redeemed twice at once from two different connections.
     */
    public RedeemAttempt consumeRedeemCode(String code, UUID playerUuid, long now) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                long amount;
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT amount, uses_remaining, expires_at, status FROM gc_redeem_codes WHERE code = ?")) {
                    select.setString(1, code);
                    try (ResultSet results = select.executeQuery()) {
                        if (!results.next()) {
                            connection.rollback();
                            return RedeemAttempt.failure(GcManager.RedeemResult.NOT_FOUND);
                        }
                        amount = results.getLong("amount");
                        int usesRemaining = results.getInt("uses_remaining");
                        long expiresAt = results.getLong("expires_at");
                        boolean hasExpiry = !results.wasNull();
                        String status = results.getString("status");
                        if (!"ACTIVE".equals(status) || usesRemaining <= 0) {
                            connection.rollback();
                            return RedeemAttempt.failure(GcManager.RedeemResult.EXHAUSTED);
                        }
                        if (hasExpiry && expiresAt < now) {
                            connection.rollback();
                            return RedeemAttempt.failure(GcManager.RedeemResult.EXPIRED);
                        }
                    }
                }

                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE gc_redeem_codes SET uses_remaining = uses_remaining - 1 "
                                + "WHERE code = ? AND status = 'ACTIVE' AND uses_remaining > 0")) {
                    update.setString(1, code);
                    if (update.executeUpdate() == 0) {
                        // Raced with another redemption between the read above and here.
                        connection.rollback();
                        return RedeemAttempt.failure(GcManager.RedeemResult.EXHAUSTED);
                    }
                }
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT uses_remaining FROM gc_redeem_codes WHERE code = ?")) {
                    select.setString(1, code);
                    try (ResultSet results = select.executeQuery()) {
                        results.next();
                        if (results.getInt("uses_remaining") <= 0) {
                            try (PreparedStatement close = connection.prepareStatement(
                                    "UPDATE gc_redeem_codes SET status = 'EXHAUSTED' WHERE code = ?")) {
                                close.setString(1, code);
                                close.executeUpdate();
                            }
                        }
                    }
                }

                try (PreparedStatement upsert = connection.prepareStatement(upsertDeltaSql)) {
                    upsert.setString(1, playerUuid.toString());
                    upsert.setLong(2, amount);
                    upsert.setLong(3, now);
                    upsert.executeUpdate();
                }
                long resultingBalance = readBalance(connection, playerUuid);
                insertLogRow(connection, playerUuid, playerUuid, GcAction.REDEEM, amount, resultingBalance, code, now);

                connection.commit();
                return RedeemAttempt.success(amount);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public record RedeemAttempt(GcManager.RedeemResult result, long amount) {
        static RedeemAttempt failure(GcManager.RedeemResult result) {
            return new RedeemAttempt(result, 0L);
        }

        static RedeemAttempt success(long amount) {
            return new RedeemAttempt(GcManager.RedeemResult.OK, amount);
        }
    }
}
