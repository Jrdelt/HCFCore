package me.vertex.core.trade;

import me.vertex.core.storage.Database;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL storage for preferences, short-lived escrow and immutable audit rows. */
public final class TradeStorage {
    private final Database database;
    private final String historyTable;
    private final String escrowTable;

    public TradeStorage(Database database) {
        this.database = database;
        boolean mysql = database.dialect() == Database.Dialect.MYSQL;
        historyTable = mysql ? """
                CREATE TABLE IF NOT EXISTS trade_history (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, requester_uuid CHAR(36) NOT NULL, target_uuid CHAR(36) NOT NULL,
                  requester_name VARCHAR(64) NOT NULL, target_name VARCHAR(64) NOT NULL, requester_items MEDIUMBLOB,
                  target_items MEDIUMBLOB, requester_money DOUBLE NOT NULL, target_money DOUBLE NOT NULL,
                  requester_exp INT NOT NULL, target_exp INT NOT NULL, created_at BIGINT NOT NULL, status VARCHAR(16) NOT NULL)"""
                : """
                CREATE TABLE IF NOT EXISTS trade_history (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, requester_uuid CHAR(36) NOT NULL, target_uuid CHAR(36) NOT NULL,
                  requester_name VARCHAR(64) NOT NULL, target_name VARCHAR(64) NOT NULL, requester_items BLOB,
                  target_items BLOB, requester_money DOUBLE NOT NULL, target_money DOUBLE NOT NULL,
                  requester_exp INT NOT NULL, target_exp INT NOT NULL, created_at BIGINT NOT NULL, status VARCHAR(16) NOT NULL)""";
        escrowTable = mysql ? """
                CREATE TABLE IF NOT EXISTS trade_escrow (
                  session_id CHAR(36) NOT NULL, owner_uuid CHAR(36) NOT NULL, items MEDIUMBLOB,
                  money DOUBLE NOT NULL, experience INT NOT NULL, PRIMARY KEY(session_id, owner_uuid))"""
                : """
                CREATE TABLE IF NOT EXISTS trade_escrow (
                  session_id CHAR(36) NOT NULL, owner_uuid CHAR(36) NOT NULL, items BLOB,
                  money DOUBLE NOT NULL, experience INT NOT NULL, PRIMARY KEY(session_id, owner_uuid))""";
    }

    public void init() throws SQLException {
        try (Connection c = database.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_preferences (uuid CHAR(36) PRIMARY KEY, accepting BOOLEAN NOT NULL)");
            s.executeUpdate(database.dialect() == Database.Dialect.MYSQL
                    ? "CREATE TABLE IF NOT EXISTS trade_claims (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_uuid CHAR(36) NOT NULL, item MEDIUMBLOB NOT NULL)"
                    : "CREATE TABLE IF NOT EXISTS trade_claims (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid CHAR(36) NOT NULL, item BLOB NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_pending_exp (uuid CHAR(36) PRIMARY KEY, levels INT NOT NULL)");
            s.executeUpdate(escrowTable);
            s.executeUpdate(historyTable);
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trade_history_requester ON trade_history (requester_uuid, created_at DESC)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trade_history_target ON trade_history (target_uuid, created_at DESC)");
            s.executeUpdate("CREATE INDEX IF NOT EXISTS idx_trade_history_status_time ON trade_history (status, created_at DESC)");
        }
    }

    public boolean loadAccepting(UUID uuid) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT accepting FROM trade_preferences WHERE uuid = ?")) {
            s.setString(1, uuid.toString());
            try (ResultSet r = s.executeQuery()) { return !r.next() || r.getBoolean(1); }
        }
    }
    public void saveAccepting(UUID uuid, boolean accepting) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO trade_preferences (uuid, accepting) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET accepting = excluded.accepting")) {
            s.setString(1, uuid.toString()); s.setBoolean(2, accepting); s.executeUpdate();
        } catch (SQLException unsupportedUpsert) {
            // MySQL's equivalent syntax; SQLite never reaches this branch.
            try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                    "INSERT INTO trade_preferences (uuid, accepting) VALUES (?, ?) ON DUPLICATE KEY UPDATE accepting = VALUES(accepting)")) {
                s.setString(1, uuid.toString()); s.setBoolean(2, accepting); s.executeUpdate();
            }
        }
    }
    public void replaceEscrow(TradeSnapshot snapshot) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM trade_escrow WHERE session_id = ?")) {
                delete.setString(1, snapshot.sessionId().toString()); delete.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement("INSERT INTO trade_escrow (session_id, owner_uuid, items, money, experience) VALUES (?, ?, ?, ?, ?)")) {
                writeEscrow(insert, snapshot.sessionId(), snapshot.requester(), snapshot.requesterItems(), snapshot.requesterHeldMoney(), snapshot.requesterHeldExperience());
                writeEscrow(insert, snapshot.sessionId(), snapshot.target(), snapshot.targetItems(), snapshot.targetHeldMoney(), snapshot.targetHeldExperience());
            }
            c.commit();
        }
    }
    private static void writeEscrow(PreparedStatement s, UUID session, UUID owner, ItemStack[] items, double money, int exp) throws SQLException {
        s.setString(1, session.toString()); s.setString(2, owner.toString());
        s.setBytes(3, ItemStack.serializeItemsAsBytes(items)); s.setDouble(4, money); s.setInt(5, exp); s.executeUpdate();
    }
    public List<TradeEscrow> loadEscrow() throws SQLException {
        List<TradeEscrow> out = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement("SELECT session_id, owner_uuid, items, money, experience FROM trade_escrow"); ResultSet r = s.executeQuery()) {
            while (r.next()) out.add(new TradeEscrow(UUID.fromString(r.getString(1)), UUID.fromString(r.getString(2)),
                    ItemStack.deserializeItemsFromBytes(r.getBytes(3)), r.getDouble(4), r.getInt(5)));
        }
        return out;
    }
    public void deleteEscrow(UUID session) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM trade_escrow WHERE session_id = ?")) {
            s.setString(1, session.toString()); s.executeUpdate();
        }
    }
    public void insertClaim(UUID owner, ItemStack item) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement("INSERT INTO trade_claims (owner_uuid, item) VALUES (?, ?)")) {
            s.setString(1, owner.toString()); s.setBytes(2, item.serializeAsBytes()); s.executeUpdate();
        }
    }
    public List<ItemStack> loadClaims(UUID owner) throws SQLException {
        List<ItemStack> out = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement("SELECT item FROM trade_claims WHERE owner_uuid = ?")) {
            s.setString(1, owner.toString()); try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(ItemStack.deserializeBytes(r.getBytes(1))); }
        }
        return out;
    }
    public void deleteClaims(UUID owner) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM trade_claims WHERE owner_uuid = ?")) { s.setString(1, owner.toString()); s.executeUpdate(); }
    }
    public void addPendingExperience(UUID owner, int levels) throws SQLException {
        if (levels <= 0) return;
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO trade_pending_exp (uuid, levels) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET levels = levels + excluded.levels")) {
            s.setString(1, owner.toString()); s.setInt(2, levels); s.executeUpdate();
        } catch (SQLException unsupportedUpsert) {
            try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                    "INSERT INTO trade_pending_exp (uuid, levels) VALUES (?, ?) ON DUPLICATE KEY UPDATE levels = levels + VALUES(levels)")) {
                s.setString(1, owner.toString()); s.setInt(2, levels); s.executeUpdate();
            }
        }
    }
    public int takePendingExperience(UUID owner) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false); int levels = 0;
            try (PreparedStatement read = c.prepareStatement("SELECT levels FROM trade_pending_exp WHERE uuid = ?")) { read.setString(1, owner.toString()); try (ResultSet r = read.executeQuery()) { if (r.next()) levels = r.getInt(1); } }
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM trade_pending_exp WHERE uuid = ?")) { delete.setString(1, owner.toString()); delete.executeUpdate(); }
            c.commit(); return levels;
        }
    }
    public void insertHistory(TradeSnapshot snapshot, String requesterName, String targetName, String status) throws SQLException {
        String sql = "INSERT INTO trade_history (requester_uuid, target_uuid, requester_name, target_name, requester_items, target_items, requester_money, target_money, requester_exp, target_exp, created_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, snapshot.requester().toString()); s.setString(2, snapshot.target().toString()); s.setString(3, requesterName); s.setString(4, targetName);
            s.setBytes(5, ItemStack.serializeItemsAsBytes(snapshot.requesterItems())); s.setBytes(6, ItemStack.serializeItemsAsBytes(snapshot.targetItems()));
            s.setDouble(7, snapshot.requesterMoney()); s.setDouble(8, snapshot.targetMoney()); s.setInt(9, snapshot.requesterExperience()); s.setInt(10, snapshot.targetExperience()); s.setLong(11, System.currentTimeMillis()); s.setString(12, status); s.executeUpdate();
        }
    }
    public List<TradeLogEntry> loadHistory(UUID player, int limit, int offset) throws SQLException {
        String where = player == null ? "" : " WHERE requester_uuid = ? OR target_uuid = ?";
        String sql = "SELECT id, requester_uuid, target_uuid, requester_name, target_name, requester_items, target_items, requester_money, target_money, requester_exp, target_exp, created_at, status FROM trade_history" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<TradeLogEntry> entries = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i=1; if (player != null) { s.setString(i++, player.toString()); s.setString(i++, player.toString()); } s.setInt(i++, limit); s.setInt(i, offset);
            try (ResultSet r = s.executeQuery()) { while (r.next()) entries.add(new TradeLogEntry(r.getLong(1), UUID.fromString(r.getString(2)), UUID.fromString(r.getString(3)), r.getString(4), r.getString(5), ItemStack.deserializeItemsFromBytes(r.getBytes(6)), ItemStack.deserializeItemsFromBytes(r.getBytes(7)), r.getDouble(8), r.getDouble(9), r.getInt(10), r.getInt(11), r.getLong(12), r.getString(13))); }
        }
        return entries;
    }
}
