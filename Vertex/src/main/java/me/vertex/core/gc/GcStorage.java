package me.vertex.core.gc;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;

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
 * <p>The database is authoritative. Every delta checks the locked wallet's
 * current balance and integer bounds in the same transaction as its audit
 * record. A cached balance can never authorize an overdraft. Settlement
 * callers can debit on their existing connection so rewards and payment
 * commit together. Staff SET/ZERO are intentionally absolute.
 */
public final class GcStorage {

    public static final long MAX_BALANCE = Long.MAX_VALUE;

    /** An expected insufficient-funds/overflow rejection, not a failed SQL write. */
    public static final class BalanceRejectedException extends SQLException {
        private final long balance;
        public BalanceRejectedException(long balance) {
            super("GC balance change exceeds the wallet's available funds or integer limit");
            this.balance = balance;
        }
        public long balance() { return balance; }
    }

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
                operation_key VARCHAR(96) NULL,
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
                operation_key VARCHAR(96) NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_LOG_TARGET_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_gc_log_target ON gc_log (target_uuid, created_at DESC)";
    private static final String CREATE_LOG_ACTOR_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_gc_log_actor ON gc_log (actor_uuid, created_at DESC)";
    private static final String CREATE_LOG_OPERATION_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_gc_log_operation ON gc_log (operation_key)";

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
    private final String ensureWalletSql;
    private final String upsertAbsoluteSql;

    public GcStorage(Database database) {
        this.database = database;
        boolean sqlite = database.dialect() == Database.Dialect.SQLITE;
        this.createLog = sqlite ? CREATE_LOG_SQLITE : CREATE_LOG_MYSQL;
        this.ensureWalletSql = sqlite
                ? "INSERT INTO gc_balances (uuid, balance, updated_at) VALUES (?, 0, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET uuid = excluded.uuid"
                : "INSERT INTO gc_balances (uuid, balance, updated_at) VALUES (?, 0, ?) "
                        + "ON DUPLICATE KEY UPDATE uuid = VALUES(uuid)";
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
            SqlSchema.ensureColumn(connection, "gc_log", "operation_key", "VARCHAR(96) NULL");
            SqlSchema.ensureIndex(connection, "gc_log", "idx_gc_log_target", false,
                    "target_uuid", "created_at DESC");
            SqlSchema.ensureIndex(connection, "gc_log", "idx_gc_log_actor", false,
                    "actor_uuid", "created_at DESC");
            SqlSchema.ensureIndex(connection, "gc_log", "idx_gc_log_operation", true,
                    "operation_key");
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
                    balances.put(UUID.fromString(results.getString("uuid")), exactBalance(results, "balance"));
                } catch (IllegalArgumentException ignored) {
                    // Skip a malformed row rather than failing every other balance.
                }
            }
        }
        return balances;
    }

    /**
     * Applies a relative balance change and writes its audit row in one
     * transaction. Both insufficient funds and integer overflow are rejected
     * atomically, regardless of the caller's cached balance.
     *
     * @return the resulting balance, for an accurate audit row.
     */
    public long applyDelta(UUID targetUuid, UUID actorUuid, GcAction action, long delta, String note, long now)
            throws SQLException {
        return applyDeltaOnce(targetUuid, actorUuid, action, delta, note, now, null).balanceAfter();
    }

    public record DeltaResult(boolean applied, long balanceAfter) { }

    /** Applies a credit/debit once when {@code operationKey} is supplied. */
    public DeltaResult applyDeltaOnce(UUID targetUuid, UUID actorUuid, GcAction action, long delta, String note,
            long now, String operationKey) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureWallet(connection, targetUuid, now);
                if (operationKey != null) {
                    try (PreparedStatement existing = connection.prepareStatement(
                            "SELECT target_uuid, action, amount FROM gc_log WHERE operation_key = ?")) {
                        existing.setString(1, operationKey);
                        try (ResultSet row = existing.executeQuery()) {
                            if (row.next()) {
                                if (!targetUuid.toString().equals(row.getString(1))
                                        || !action.name().equals(row.getString(2))
                                        || delta == Long.MIN_VALUE || Math.abs(delta) != row.getLong(3)) {
                                    throw new SQLException("GC operation key conflicts with a different ledger change");
                                }
                                long current = readBalance(connection, targetUuid);
                                connection.commit();
                                return new DeltaResult(false, current);
                            }
                        }
                    }
                }
                long resultingBalance = adjustBalance(connection, targetUuid, delta, now);
                insertLogRow(connection, actorUuid, targetUuid, action, Math.abs(delta), resultingBalance, note, now,
                        operationKey);
                connection.commit();
                return new DeltaResult(true, resultingBalance);
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
        if (newBalance < 0) throw new SQLException("GC balance must be nonnegative");
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
                insertLogRow(connection, actorUuid, targetUuid, action, newBalance, newBalance, note, now, null);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public long loadBalance(UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection()) { return readBalance(connection, uuid); }
    }

    /** Derived shapes, not a second code authority; codes/history survive season resets. */
    public Map<Integer, String> loadCodeAlphabets() throws SQLException {
        Map<Integer, java.util.Set<Character>> alphabets = new HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT code FROM gc_redeem_codes");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String code = rows.getString(1).toUpperCase(java.util.Locale.ROOT);
                if (code.isEmpty() || code.length() > 32) continue;
                java.util.Set<Character> alphabet = alphabets.computeIfAbsent(code.length(), ignored -> new java.util.TreeSet<>());
                for (char character : code.toCharArray()) alphabet.add(character);
            }
        }
        Map<Integer, String> result = new HashMap<>();
        alphabets.forEach((length, alphabet) -> {
            StringBuilder characters = new StringBuilder();
            alphabet.forEach(characters::append);
            result.put(length, characters.toString());
        });
        return result;
    }

    private static long exactBalance(ResultSet row, String column) throws SQLException {
        try {
            long value = row.getBigDecimal(column).longValueExact();
            if (value < 0) throw new ArithmeticException("negative balance");
            return value;
        } catch (ArithmeticException | NullPointerException error) {
            throw new SQLException("GC wallet contains invalid/non-integral data; refusing to spend it", error);
        }
    }

    private static long readBalance(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT balance FROM gc_balances WHERE uuid = ?")) {
            select.setString(1, uuid.toString());
            try (ResultSet results = select.executeQuery()) {
                return results.next() ? exactBalance(results, "balance") : 0L;
            }
        }
    }

    private void ensureWallet(Connection connection, UUID target, long now) throws SQLException {
        // The no-op upsert also takes the row's write lock before any balance/log read.
        try (PreparedStatement ensure = connection.prepareStatement(ensureWalletSql)) {
            ensure.setString(1, target.toString());
            ensure.setLong(2, now);
            ensure.executeUpdate();
        }
    }

    private static long adjustBalance(Connection connection, UUID target, long delta, long now) throws SQLException {
        String predicate = delta < 0 ? "balance >= ?" : "balance <= ?";
        long bound = delta < 0 ? (delta == Long.MIN_VALUE ? MAX_BALANCE : -delta) : MAX_BALANCE - delta;
        if (delta == Long.MIN_VALUE) throw new BalanceRejectedException(readBalance(connection, target));
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE gc_balances SET balance = balance + ?, updated_at = ? WHERE uuid = ? AND balance >= 0 AND " + predicate)) {
            update.setLong(1, delta);
            update.setLong(2, now);
            update.setString(3, target.toString());
            update.setLong(4, bound);
            if (update.executeUpdate() != 1) throw new BalanceRejectedException(readBalance(connection, target));
        }
        return readBalance(connection, target);
    }

    /** Participates in an existing settlement transaction; never commits independently. */
    public static void debitForSettlement(Connection connection, UUID target, GcAction action, long amount,
            String operationKey, long now) throws SQLException {
        if (connection.getAutoCommit()) throw new SQLException("GC settlement debit requires a transaction");
        if (amount <= 0) throw new SQLException("GC debit amount must be positive");
        long resulting = adjustBalance(connection, target, -amount, now);
        insertLogRow(connection, target, target, action, amount, resulting, operationKey, now, operationKey);
    }

    private static void insertLogRow(Connection connection, UUID actorUuid, UUID targetUuid, GcAction action, long amount,
            long balanceAfter, String note, long now, String operationKey) throws SQLException {
        String sql = "INSERT INTO gc_log (actor_uuid, target_uuid, action, amount, balance_after, note, operation_key, "
                + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
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
            if (operationKey == null) statement.setNull(7, Types.VARCHAR);
            else statement.setString(7, operationKey);
            statement.setLong(8, now);
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
     * Atomically turns a player's existing GC into one single-use code. The
     * balance debit, code row, and audit row commit together, so a code is
     * never printed unless the player's balance was durably reduced.
     */
    public WithdrawCodeAttempt withdrawToCode(UUID ownerUuid, String code, long amount, long now) throws SQLException {
        if (amount <= 0) throw new SQLException("GC withdrawal must be positive");
        try (Connection connection = database.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement debit = connection.prepareStatement(
                        "UPDATE gc_balances SET balance = balance - ?, updated_at = ? "
                                + "WHERE uuid = ? AND balance >= ?")) {
                    debit.setLong(1, amount);
                    debit.setLong(2, now);
                    debit.setString(3, ownerUuid.toString());
                    debit.setLong(4, amount);
                    if (debit.executeUpdate() != 1) {
                        connection.rollback();
                        return WithdrawCodeAttempt.insufficient();
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO gc_redeem_codes (code, amount, uses_remaining, created_by_uuid, created_at, "
                                + "expires_at, status) VALUES (?, ?, 1, ?, ?, NULL, 'ACTIVE')")) {
                    insert.setString(1, code);
                    insert.setLong(2, amount);
                    insert.setString(3, ownerUuid.toString());
                    insert.setLong(4, now);
                    insert.executeUpdate();
                } catch (SQLException duplicateOrFailure) {
                    // A primary-key collision is recoverable; all other SQL
                    // errors are still surfaced to the manager after rollback.
                    connection.rollback();
                    if (isConstraintViolation(duplicateOrFailure)) {
                        return WithdrawCodeAttempt.collision();
                    }
                    throw duplicateOrFailure;
                }
                long balanceAfter = readBalance(connection, ownerUuid);
                insertLogRow(connection, ownerUuid, ownerUuid, GcAction.WITHDRAW_CODE,
                        amount, balanceAfter, code, now, null);
                connection.commit();
                return WithdrawCodeAttempt.success(balanceAfter);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private static boolean isConstraintViolation(SQLException error) {
        String state = error.getSQLState();
        return (state != null && state.startsWith("23")) || error.getErrorCode() == 19;
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
                        if (hasExpiry && expiresAt <= now) {
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

                if (amount <= 0) throw new SQLException("GC code contains a nonpositive amount");
                ensureWallet(connection, playerUuid, now);
                long resultingBalance;
                try { resultingBalance = adjustBalance(connection, playerUuid, amount, now); }
                catch (BalanceRejectedException limit) {
                    connection.rollback();
                    return RedeemAttempt.failure(GcManager.RedeemResult.BALANCE_LIMIT);
                }
                insertLogRow(connection, playerUuid, playerUuid, GcAction.REDEEM, amount, resultingBalance, code, now,
                        null);

                connection.commit();
                return RedeemAttempt.success(amount, resultingBalance);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public record RedeemAttempt(GcManager.RedeemResult result, long amount, long balanceAfter) {
        static RedeemAttempt failure(GcManager.RedeemResult result) {
            return new RedeemAttempt(result, 0L, 0L);
        }

        static RedeemAttempt success(long amount, long balanceAfter) {
            return new RedeemAttempt(GcManager.RedeemResult.OK, amount, balanceAfter);
        }
    }

    public record WithdrawCodeAttempt(WithdrawCodeResult result, long balanceAfter) {
        static WithdrawCodeAttempt success(long balanceAfter) {
            return new WithdrawCodeAttempt(WithdrawCodeResult.OK, balanceAfter);
        }
        static WithdrawCodeAttempt insufficient() {
            return new WithdrawCodeAttempt(WithdrawCodeResult.INSUFFICIENT, 0L);
        }
        static WithdrawCodeAttempt collision() {
            return new WithdrawCodeAttempt(WithdrawCodeResult.COLLISION, 0L);
        }
    }

    public enum WithdrawCodeResult { OK, INSUFFICIENT, COLLISION }
}
