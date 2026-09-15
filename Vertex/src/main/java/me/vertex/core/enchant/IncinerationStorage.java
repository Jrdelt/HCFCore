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
 * The Incineration transaction journal: one row per rune destroyed, tracked
 * through {@code PENDING -> ITEM_REMOVED -> COMPLETE} so a crash between
 * removing an item and crediting its reward can be reconciled on the next
 * startup instead of silently eating the rune or double-paying it. Same
 * small dedicated-Database-backed shape as every other feature-specific
 * store in this package (e.g. {@link RunePreferenceStorage}).
 */
public final class IncinerationStorage {
    private final Database database;

    public IncinerationStorage(Database database) {
        this.database = database;
    }

    public enum State {
        PENDING, ITEM_REMOVED, COMPLETE
    }

    public record Transaction(String transactionId, String batchId, UUID playerUuid, String runeId, int runeLevel,
            String originTier, String currency, double baseValue, double rolledPercentage, double rewardAmount, State state) {
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS incineration_transactions ("
                    + "transaction_id CHAR(36) NOT NULL PRIMARY KEY, batch_id CHAR(36) NULL, "
                    + "player_uuid CHAR(36) NOT NULL, rune_id VARCHAR(64) NOT NULL, rune_level INT NOT NULL, "
                    + "origin_tier VARCHAR(16) NOT NULL, currency VARCHAR(16) NOT NULL, base_value DOUBLE NOT NULL, "
                    + "rolled_percentage DOUBLE NOT NULL, reward_amount DOUBLE NOT NULL, state VARCHAR(16) NOT NULL, "
                    + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
        }
    }

    public void insert(Transaction transaction) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO incineration_transactions (transaction_id, batch_id, player_uuid, rune_id, rune_level, "
                        + "origin_tier, currency, base_value, rolled_percentage, reward_amount, state, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, transaction.transactionId());
            statement.setString(2, transaction.batchId());
            statement.setString(3, transaction.playerUuid().toString());
            statement.setString(4, transaction.runeId());
            statement.setInt(5, transaction.runeLevel());
            statement.setString(6, transaction.originTier());
            statement.setString(7, transaction.currency());
            statement.setDouble(8, transaction.baseValue());
            statement.setDouble(9, transaction.rolledPercentage());
            statement.setDouble(10, transaction.rewardAmount());
            statement.setString(11, transaction.state().name());
            statement.setLong(12, now);
            statement.setLong(13, now);
            statement.executeUpdate();
        }
    }

    public void updateState(String transactionId, State state) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE incineration_transactions SET state = ?, updated_at = ? WHERE transaction_id = ?")) {
            statement.setString(1, state.name());
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, transactionId);
            statement.executeUpdate();
        }
    }

    public List<Transaction> loadIncomplete() throws SQLException {
        List<Transaction> transactions = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT transaction_id, batch_id, player_uuid, rune_id, rune_level, origin_tier, currency, "
                        + "base_value, rolled_percentage, reward_amount, state FROM incineration_transactions "
                        + "WHERE state != ?")) {
            statement.setString(1, State.COMPLETE.name());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    transactions.add(new Transaction(result.getString(1), result.getString(2),
                            UUID.fromString(result.getString(3)), result.getString(4), result.getInt(5),
                            result.getString(6), result.getString(7), result.getDouble(8), result.getDouble(9),
                            result.getDouble(10), State.valueOf(result.getString(11))));
                }
            }
        }
        return transactions;
    }
}
