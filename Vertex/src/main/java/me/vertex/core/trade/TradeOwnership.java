package me.vertex.core.trade;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Fences escrow writers from a previous JVM, including after a long pause. */
final class TradeOwnership {
    private static final long LEASE_SECONDS = 120;
    final String shard;
    final String boot = UUID.randomUUID().toString();
    private final Database database;
    private volatile long safeUntilNanos;

    TradeOwnership(Database database, String shard) {
        this.database = database;
        this.shard = shard;
    }

    void init(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS trade_owners ("
                    + "shard_id VARCHAR(64) PRIMARY KEY, boot_id CHAR(36) NOT NULL, expires_at BIGINT NOT NULL)");
        }
    }

    void acquire() throws SQLException {
        long started = System.nanoTime();
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String insert = database.dialect() == Database.Dialect.MYSQL
                        ? "INSERT IGNORE INTO trade_owners (shard_id,boot_id,expires_at) VALUES (?,?,0)"
                        : "INSERT INTO trade_owners (shard_id,boot_id,expires_at) VALUES (?,?,0) ON CONFLICT(shard_id) DO NOTHING";
                try (PreparedStatement statement = connection.prepareStatement(insert)) {
                    statement.setString(1, shard);
                    statement.setString(2, boot);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT boot_id,expires_at FROM trade_owners WHERE shard_id=?" + lockSuffix())) {
                    statement.setString(1, shard);
                    try (ResultSet row = statement.executeQuery()) {
                        if (!row.next()) throw new SQLException("Missing trade ownership row: " + shard);
                        if (!boot.equals(row.getString(1)) && row.getLong(2) > now(connection)) {
                            throw new SQLException("Trade owner '" + shard + "' is still leased by another process. "
                                    + "Use unique shard IDs; after a crash wait up to 120 seconds before retrying startup.");
                        }
                    }
                }
                writeLease(connection, now(connection) + LEASE_SECONDS);
                connection.commit();
                markHealthy(started);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    void renew() throws SQLException {
        long started = System.nanoTime();
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                require(connection);
                writeLease(connection, now(connection) + LEASE_SECONDS);
                connection.commit();
                markHealthy(started);
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                safeUntilNanos = 0;
                throw error;
            }
        } catch (SQLException | RuntimeException error) {
            safeUntilNanos = 0;
            throw error;
        }
    }

    /** Caller holds this row lock through its entire escrow transaction. */
    void require(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT boot_id,expires_at FROM trade_owners WHERE shard_id=?" + lockSuffix())) {
            statement.setString(1, shard);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !boot.equals(row.getString(1)) || row.getLong(2) <= now(connection)) {
                    safeUntilNanos = 0;
                    throw new SQLException("Trade ownership expired or was fenced for shard '" + shard + "'; restart required.");
                }
            }
        }
    }

    void release() throws SQLException {
        safeUntilNanos = 0;
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE trade_owners SET expires_at=0 WHERE shard_id=? AND boot_id=?")) {
            statement.setString(1, shard);
            statement.setString(2, boot);
            statement.executeUpdate();
        }
    }

    boolean healthy() { return safeUntilNanos != 0 && System.nanoTime() < safeUntilNanos; }

    private void markHealthy(long started) {
        // Conservative local deadline: excludes SQL round trip and clock-second truncation.
        safeUntilNanos = started + TimeUnit.SECONDS.toNanos(LEASE_SECONDS - 5);
    }

    private void writeLease(Connection connection, long until) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE trade_owners SET boot_id=?,expires_at=? WHERE shard_id=?")) {
            statement.setString(1, boot);
            statement.setLong(2, until);
            statement.setString(3, shard);
            if (statement.executeUpdate() != 1) throw new SQLException("Could not renew trade ownership");
        }
    }

    private long now(Connection connection) throws SQLException {
        String sql = database.dialect() == Database.Dialect.MYSQL
                ? "SELECT UNIX_TIMESTAMP()" : "SELECT CAST(strftime('%s','now') AS INTEGER)";
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) throw new SQLException("Could not read database time");
            return row.getLong(1);
        }
    }

    private String lockSuffix() { return database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : ""; }
}
