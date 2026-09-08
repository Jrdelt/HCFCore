package me.vertex.core.coinflip;

import me.vertex.core.storage.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable storage for everything Coinflip owns: open wagers, pending item
 * claims, self-bans, offline experience refunds, and the permanent staff
 * audit log. Every column that carries items uses
 * {@link ItemStack#serializeItemsAsBytes} -- the same NBT-based, non-Java-
 * serialization approach Backpacks use, verified safe for large amounts
 * and engine-independent across a Minecraft version upgrade.
 */
public final class CoinflipStorage {

    private static final String CREATE_COINFLIPS_MYSQL = """
            CREATE TABLE IF NOT EXISTS coinflips (
                id INT AUTO_INCREMENT PRIMARY KEY,
                host_uuid CHAR(36) NOT NULL,
                target_uuid CHAR(36) NULL,
                type VARCHAR(16) NOT NULL,
                amount DOUBLE NOT NULL,
                items MEDIUMBLOB NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_COINFLIPS_SQLITE = """
            CREATE TABLE IF NOT EXISTS coinflips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                host_uuid CHAR(36) NOT NULL,
                target_uuid CHAR(36) NULL,
                type VARCHAR(16) NOT NULL,
                amount DOUBLE NOT NULL,
                items BLOB NULL,
                created_at BIGINT NOT NULL
            )""";

    private static final String CREATE_CLAIMS_MYSQL = """
            CREATE TABLE IF NOT EXISTS coinflip_claims (
                id INT AUTO_INCREMENT PRIMARY KEY,
                winner_uuid CHAR(36) NOT NULL,
                items MEDIUMBLOB NOT NULL,
                won_at BIGINT NOT NULL
            )""";
    private static final String CREATE_CLAIMS_SQLITE = """
            CREATE TABLE IF NOT EXISTS coinflip_claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                winner_uuid CHAR(36) NOT NULL,
                items BLOB NOT NULL,
                won_at BIGINT NOT NULL
            )""";

    private static final String CREATE_BANS = """
            CREATE TABLE IF NOT EXISTS coinflip_bans (
                uuid CHAR(36) NOT NULL PRIMARY KEY,
                banned_until BIGINT NOT NULL
            )""";

    private static final String CREATE_PENDING_EXP = """
            CREATE TABLE IF NOT EXISTS coinflip_pending_exp (
                uuid CHAR(36) NOT NULL PRIMARY KEY,
                levels INT NOT NULL
            )""";

    private static final String CREATE_RESULT_NOTIFICATIONS_MYSQL = """
            CREATE TABLE IF NOT EXISTS coinflip_result_notifications (
                id INT AUTO_INCREMENT PRIMARY KEY,
                recipient_uuid CHAR(36) NOT NULL,
                won BOOLEAN NOT NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_RESULT_NOTIFICATIONS_SQLITE = """
            CREATE TABLE IF NOT EXISTS coinflip_result_notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recipient_uuid CHAR(36) NOT NULL,
                won BOOLEAN NOT NULL,
                created_at BIGINT NOT NULL
            )""";

    private static final String CREATE_PENDING_MATCHES_MYSQL = """
            CREATE TABLE IF NOT EXISTS coinflip_pending_matches (
                id INT AUTO_INCREMENT PRIMARY KEY,
                coinflip_id INT NOT NULL,
                opponent_uuid CHAR(36) NOT NULL,
                items MEDIUMBLOB NOT NULL,
                requested_at BIGINT NOT NULL
            )""";
    private static final String CREATE_PENDING_MATCHES_SQLITE = """
            CREATE TABLE IF NOT EXISTS coinflip_pending_matches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                coinflip_id INTEGER NOT NULL,
                opponent_uuid CHAR(36) NOT NULL,
                items BLOB NOT NULL,
                requested_at BIGINT NOT NULL
            )""";

    private static final String CREATE_LOG_MYSQL = """
            CREATE TABLE IF NOT EXISTS coinflip_log (
                id INT AUTO_INCREMENT PRIMARY KEY,
                host_uuid CHAR(36) NOT NULL,
                opponent_uuid CHAR(36) NULL,
                type VARCHAR(16) NOT NULL,
                summary VARCHAR(1024) NOT NULL,
                winner_uuid CHAR(36) NULL,
                resolved_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL,
                cancelled_by CHAR(36) NULL
            )""";
    private static final String CREATE_LOG_SQLITE = """
            CREATE TABLE IF NOT EXISTS coinflip_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                host_uuid CHAR(36) NOT NULL,
                opponent_uuid CHAR(36) NULL,
                type VARCHAR(16) NOT NULL,
                summary VARCHAR(1024) NOT NULL,
                winner_uuid CHAR(36) NULL,
                resolved_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL,
                cancelled_by CHAR(36) NULL
            )""";
    private static final String CREATE_LOG_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_coinflip_log_resolved_at ON coinflip_log (resolved_at DESC)";
    private static final String CREATE_CLAIMS_OWNER_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_coinflip_claims_winner ON coinflip_claims (winner_uuid)";
    private static final String CREATE_NOTIFICATION_RECIPIENT_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_coinflip_result_notifications_recipient "
                    + "ON coinflip_result_notifications (recipient_uuid)";

    private final Database database;
    private final String createCoinflips;
    private final String createClaims;
    private final String createLog;
    private final String createPendingMatches;
    private final String createResultNotifications;

    public CoinflipStorage(Database database) {
        this.database = database;
        boolean sqlite = database.dialect() == Database.Dialect.SQLITE;
        this.createCoinflips = sqlite ? CREATE_COINFLIPS_SQLITE : CREATE_COINFLIPS_MYSQL;
        this.createClaims = sqlite ? CREATE_CLAIMS_SQLITE : CREATE_CLAIMS_MYSQL;
        this.createLog = sqlite ? CREATE_LOG_SQLITE : CREATE_LOG_MYSQL;
        this.createPendingMatches = sqlite ? CREATE_PENDING_MATCHES_SQLITE : CREATE_PENDING_MATCHES_MYSQL;
        this.createResultNotifications = sqlite ? CREATE_RESULT_NOTIFICATIONS_SQLITE : CREATE_RESULT_NOTIFICATIONS_MYSQL;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(createCoinflips);
            statement.executeUpdate(createClaims);
            statement.executeUpdate(CREATE_CLAIMS_OWNER_INDEX);
            statement.executeUpdate(CREATE_BANS);
            statement.executeUpdate(CREATE_PENDING_EXP);
            statement.executeUpdate(createResultNotifications);
            statement.executeUpdate(CREATE_NOTIFICATION_RECIPIENT_INDEX);
            statement.executeUpdate(createLog);
            statement.executeUpdate(CREATE_LOG_INDEX);
            statement.executeUpdate(createPendingMatches);
        }
    }

    // ---- Active coinflips ----

    public List<Coinflip> loadAllCoinflips() throws SQLException {
        List<Coinflip> coinflips = new ArrayList<>();
        String sql = "SELECT id, host_uuid, target_uuid, type, amount, items, created_at FROM coinflips";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                coinflips.add(readCoinflip(results));
            }
        }
        return coinflips;
    }

    /** @return the generated id for the new row. */
    public int insertCoinflip(UUID hostUuid, UUID targetUuid, CoinflipType type, double amount,
            ItemStack[] items, long createdAt) throws SQLException {
        String sql = """
                INSERT INTO coinflips (host_uuid, target_uuid, type, amount, items, created_at)
                VALUES (?, ?, ?, ?, ?, ?)""";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, hostUuid.toString());
            if (targetUuid == null) {
                statement.setNull(2, Types.CHAR);
            } else {
                statement.setString(2, targetUuid.toString());
            }
            statement.setString(3, type.name());
            statement.setDouble(4, amount);
            if (items == null || items.length == 0) {
                statement.setNull(5, Types.BLOB);
            } else {
                statement.setBytes(5, ItemStack.serializeItemsAsBytes(items));
            }
            statement.setLong(6, createdAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    public void deleteCoinflip(int id) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM coinflips WHERE id = ?")) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    /**
     * Commits the durable result record, removes the now-unplayable listing,
     * and queues both result messages as one database transaction. A restart
     * during the client animation can therefore never reroll a winner or
     * silently lose the disconnected participant's result.
     */
    public void resolveCoinflip(Coinflip coinflip, UUID opponentUuid, UUID winnerUuid, String summary,
            long resolvedAt) throws SQLException {
        String logSql = """
                INSERT INTO coinflip_log (host_uuid, opponent_uuid, type, summary, winner_uuid, resolved_at, status, cancelled_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
        String notificationSql = "INSERT INTO coinflip_result_notifications (recipient_uuid, won, created_at) "
                + "VALUES (?, ?, ?)";
        try (Connection connection = database.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM coinflips WHERE id = ?");
                 PreparedStatement log = connection.prepareStatement(logSql);
                 PreparedStatement notification = connection.prepareStatement(notificationSql)) {
                delete.setInt(1, coinflip.id());
                delete.executeUpdate();

                log.setString(1, coinflip.hostUuid().toString());
                setNullableUuid(log, 2, opponentUuid);
                log.setString(3, coinflip.type().name());
                log.setString(4, summary);
                setNullableUuid(log, 5, winnerUuid);
                log.setLong(6, resolvedAt);
                log.setString(7, CoinflipLogEntry.Status.RESOLVED.name());
                log.setNull(8, Types.CHAR);
                log.executeUpdate();

                insertResultNotification(notification, coinflip.hostUuid(), winnerUuid.equals(coinflip.hostUuid()), resolvedAt);
                insertResultNotification(notification, opponentUuid, winnerUuid.equals(opponentUuid), resolvedAt);
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private Coinflip readCoinflip(ResultSet results) throws SQLException {
        byte[] itemBytes = results.getBytes("items");
        String targetUuid = results.getString("target_uuid");
        return new Coinflip(
                results.getInt("id"),
                UUID.fromString(results.getString("host_uuid")),
                targetUuid == null ? null : UUID.fromString(targetUuid),
                CoinflipType.valueOf(results.getString("type")),
                results.getDouble("amount"),
                itemBytes == null ? new ItemStack[0] : ItemStack.deserializeItemsFromBytes(itemBytes),
                results.getLong("created_at"));
    }

    // ---- Pending item-match approvals (an opponent's items, awaiting the host's approve/deny) ----

    public List<CoinflipPendingMatch> loadAllPendingMatches() throws SQLException {
        List<CoinflipPendingMatch> matches = new ArrayList<>();
        String sql = "SELECT id, coinflip_id, opponent_uuid, items, requested_at FROM coinflip_pending_matches";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                matches.add(new CoinflipPendingMatch(
                        results.getInt("id"),
                        results.getInt("coinflip_id"),
                        UUID.fromString(results.getString("opponent_uuid")),
                        ItemStack.deserializeItemsFromBytes(results.getBytes("items")),
                        results.getLong("requested_at")));
            }
        }
        return matches;
    }

    /** @return the generated id for the new row. */
    public int insertPendingMatch(int coinflipId, UUID opponentUuid, ItemStack[] items, long requestedAt)
            throws SQLException {
        String sql = "INSERT INTO coinflip_pending_matches (coinflip_id, opponent_uuid, items, requested_at) "
                + "VALUES (?, ?, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, coinflipId);
            statement.setString(2, opponentUuid.toString());
            statement.setBytes(3, ItemStack.serializeItemsAsBytes(items));
            statement.setLong(4, requestedAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    public void deletePendingMatch(int id) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM coinflip_pending_matches WHERE id = ?")) {
            statement.setInt(1, id);
            statement.executeUpdate();
        }
    }

    // ---- Pending item claims ----

    public List<CoinflipClaim> loadClaims(UUID winnerUuid) throws SQLException {
        List<CoinflipClaim> claims = new ArrayList<>();
        String sql = "SELECT id, winner_uuid, items, won_at FROM coinflip_claims WHERE winner_uuid = ?";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, winnerUuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    claims.add(new CoinflipClaim(
                            results.getInt("id"),
                            winnerUuid,
                            ItemStack.deserializeItemsFromBytes(results.getBytes("items")),
                            results.getLong("won_at")));
                }
            }
        }
        return claims;
    }

    public boolean hasClaims(UUID winnerUuid) throws SQLException {
        String sql = "SELECT 1 FROM coinflip_claims WHERE winner_uuid = ? LIMIT 1";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, winnerUuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    public void insertClaim(UUID winnerUuid, ItemStack[] items, long wonAt) throws SQLException {
        String sql = "INSERT INTO coinflip_claims (winner_uuid, items, won_at) VALUES (?, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, winnerUuid.toString());
            statement.setBytes(2, ItemStack.serializeItemsAsBytes(items));
            statement.setLong(3, wonAt);
            statement.executeUpdate();
        }
    }

    public void deleteClaims(UUID winnerUuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM coinflip_claims WHERE winner_uuid = ?")) {
            statement.setString(1, winnerUuid.toString());
            statement.executeUpdate();
        }
    }

    /** Deletes only the rendered claim rows, never claims created after a GUI opened. */
    public void deleteClaimsById(List<Integer> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM coinflip_claims WHERE id IN (" + placeholders + ")")) {
            for (int index = 0; index < ids.size(); index++) {
                statement.setInt(index + 1, ids.get(index));
            }
            statement.executeUpdate();
        }
    }

    // ---- Self-bans ----

    /** @return every self-ban's expiry, keyed by player, loaded once at startup. */
    public Map<UUID, Long> loadBans() throws SQLException {
        Map<UUID, Long> bans = new java.util.HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT uuid, banned_until FROM coinflip_bans");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    bans.put(UUID.fromString(results.getString("uuid")), results.getLong("banned_until"));
                } catch (IllegalArgumentException ignored) {
                    // Skip a malformed row rather than failing every other ban.
                }
            }
        }
        return bans;
    }

    public void saveBan(UUID uuid, long bannedUntil) throws SQLException {
        String sql = switch (database.dialect()) {
            case SQLITE -> "INSERT INTO coinflip_bans (uuid, banned_until) VALUES (?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET banned_until = excluded.banned_until";
            case MYSQL -> "INSERT INTO coinflip_bans (uuid, banned_until) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE banned_until = VALUES(banned_until)";
        };
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, bannedUntil);
            statement.executeUpdate();
        }
    }

    public void deleteBan(UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM coinflip_bans WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    // ---- Pending experience (offline admin-cancel refunds only) ----

    public Map<UUID, Integer> loadPendingExp() throws SQLException {
        Map<UUID, Integer> pending = new java.util.HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT uuid, levels FROM coinflip_pending_exp");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    pending.put(UUID.fromString(results.getString("uuid")), results.getInt("levels"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return pending;
    }

    public void savePendingExp(UUID uuid, int levels) throws SQLException {
        String sql = switch (database.dialect()) {
            case SQLITE -> "INSERT INTO coinflip_pending_exp (uuid, levels) VALUES (?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET levels = excluded.levels";
            case MYSQL -> "INSERT INTO coinflip_pending_exp (uuid, levels) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE levels = VALUES(levels)";
        };
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, levels);
            statement.executeUpdate();
        }
    }

    public void deletePendingExp(UUID uuid) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM coinflip_pending_exp WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    // ---- Deferred result chat (participants who disconnect during the animation) ----

    public void insertResultNotification(UUID recipientUuid, boolean won, long createdAt) throws SQLException {
        String sql = "INSERT INTO coinflip_result_notifications (recipient_uuid, won, created_at) VALUES (?, ?, ?)";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recipientUuid.toString());
            statement.setBoolean(2, won);
            statement.setLong(3, createdAt);
            statement.executeUpdate();
        }
    }

    private static void insertResultNotification(PreparedStatement statement, UUID recipientUuid, boolean won,
            long createdAt) throws SQLException {
        statement.setString(1, recipientUuid.toString());
        statement.setBoolean(2, won);
        statement.setLong(3, createdAt);
        statement.executeUpdate();
    }

    public List<CoinflipResultNotification> loadResultNotifications(UUID recipientUuid) throws SQLException {
        List<CoinflipResultNotification> notifications = new ArrayList<>();
        String sql = "SELECT id, won, created_at FROM coinflip_result_notifications "
                + "WHERE recipient_uuid = ? ORDER BY id ASC";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recipientUuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    notifications.add(new CoinflipResultNotification(
                            results.getInt("id"), recipientUuid, results.getBoolean("won"), results.getLong("created_at")));
                }
            }
        }
        return notifications;
    }

    public void deleteResultNotificationsById(List<Integer> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM coinflip_result_notifications WHERE id IN (" + placeholders + ")")) {
            for (int index = 0; index < ids.size(); index++) {
                statement.setInt(index + 1, ids.get(index));
            }
            statement.executeUpdate();
        }
    }

    /** Removes only the result line that was just sent, never unrelated pending results. */
    public void deleteResultNotifications(UUID recipientUuid, boolean won, long createdAt) throws SQLException {
        String sql = "DELETE FROM coinflip_result_notifications "
                + "WHERE recipient_uuid = ? AND won = ? AND created_at = ?";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recipientUuid.toString());
            statement.setBoolean(2, won);
            statement.setLong(3, createdAt);
            statement.executeUpdate();
        }
    }

    // ---- Audit log ----

    public void insertLogEntry(UUID hostUuid, UUID opponentUuid, CoinflipType type, String summary,
            UUID winnerUuid, long resolvedAt, CoinflipLogEntry.Status status, UUID cancelledByUuid) throws SQLException {
        String sql = """
                INSERT INTO coinflip_log (host_uuid, opponent_uuid, type, summary, winner_uuid, resolved_at, status, cancelled_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hostUuid.toString());
            setNullableUuid(statement, 2, opponentUuid);
            statement.setString(3, type.name());
            statement.setString(4, summary);
            setNullableUuid(statement, 5, winnerUuid);
            statement.setLong(6, resolvedAt);
            statement.setString(7, status.name());
            setNullableUuid(statement, 8, cancelledByUuid);
            statement.executeUpdate();
        }
    }

    /** Newest first. Filters by host or opponent when {@code playerUuid} is non-null. */
    public List<CoinflipLogEntry> loadLog(UUID playerUuid, int limit, int offset) throws SQLException {
        List<CoinflipLogEntry> entries = new ArrayList<>();
        String sql = playerUuid == null
                ? "SELECT * FROM coinflip_log ORDER BY resolved_at DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM coinflip_log WHERE host_uuid = ? OR opponent_uuid = ? ORDER BY resolved_at DESC LIMIT ? OFFSET ?";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (playerUuid != null) {
                statement.setString(index++, playerUuid.toString());
                statement.setString(index++, playerUuid.toString());
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

    public void deleteLogEntriesOlderThan(long cutoffMillis) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement =
                     connection.prepareStatement("DELETE FROM coinflip_log WHERE resolved_at < ?")) {
            statement.setLong(1, cutoffMillis);
            statement.executeUpdate();
        }
    }

    private CoinflipLogEntry readLogEntry(ResultSet results) throws SQLException {
        return new CoinflipLogEntry(
                results.getInt("id"),
                UUID.fromString(results.getString("host_uuid")),
                nullableUuid(results.getString("opponent_uuid")),
                CoinflipType.valueOf(results.getString("type")),
                results.getString("summary"),
                nullableUuid(results.getString("winner_uuid")),
                results.getLong("resolved_at"),
                CoinflipLogEntry.Status.valueOf(results.getString("status")),
                nullableUuid(results.getString("cancelled_by")));
    }

    private static void setNullableUuid(PreparedStatement statement, int index, UUID uuid) throws SQLException {
        if (uuid == null) {
            statement.setNull(index, Types.CHAR);
        } else {
            statement.setString(index, uuid.toString());
        }
    }

    private static UUID nullableUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
