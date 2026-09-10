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
            // Only used to return legacy pre-item-only-trade escrow safely.
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_pending_money (uuid CHAR(36) PRIMARY KEY, amount DOUBLE NOT NULL)");
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

    /**
     * Moves one abandoned escrow row into durable player claims in one SQL
     * transaction. It deletes the source only after the replacement records
     * exist, so a restart cannot pay the same escrow over and over.
     */
    public void recoverEscrow(TradeEscrow escrow) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement item = c.prepareStatement(
                        "INSERT INTO trade_claims (owner_uuid, item) VALUES (?, ?)");
                     PreparedStatement delete = c.prepareStatement(
                        "DELETE FROM trade_escrow WHERE session_id = ? AND owner_uuid = ?")) {
                    for (ItemStack stack : escrow.items()) {
                        if (stack == null || stack.isEmpty()) continue;
                        item.setString(1, escrow.owner().toString()); item.setBytes(2, stack.serializeAsBytes()); item.addBatch();
                    }
                    item.executeBatch();
                    if (escrow.experience() > 0) {
                        addPendingExperience(c, escrow.owner(), escrow.experience());
                    }
                    if (escrow.money() > 0) {
                        addPendingMoney(c, escrow.owner(), escrow.money());
                    }
                    delete.setString(1, escrow.sessionId().toString()); delete.setString(2, escrow.owner().toString()); delete.executeUpdate();
                }
                c.commit();
            } catch (SQLException sqliteUpsertUnsupported) {
                c.rollback();
                throw sqliteUpsertUnsupported;
            }
        }
    }

    private static void addPendingExperience(Connection c, UUID owner, int levels) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("UPDATE trade_pending_exp SET levels = levels + ? WHERE uuid = ?")) {
            update.setInt(1, levels); update.setString(2, owner.toString());
            if (update.executeUpdate() != 0) return;
        }
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO trade_pending_exp (uuid, levels) VALUES (?, ?)")) {
            insert.setString(1, owner.toString()); insert.setInt(2, levels); insert.executeUpdate();
        }
    }

    private static void addPendingMoney(Connection c, UUID owner, double amount) throws SQLException {
        try (PreparedStatement update = c.prepareStatement("UPDATE trade_pending_money SET amount = amount + ? WHERE uuid = ?")) {
            update.setDouble(1, amount); update.setString(2, owner.toString());
            if (update.executeUpdate() != 0) return;
        }
        try (PreparedStatement insert = c.prepareStatement("INSERT INTO trade_pending_money (uuid, amount) VALUES (?, ?)")) {
            insert.setString(1, owner.toString()); insert.setDouble(2, amount); insert.executeUpdate();
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

    /**
     * Removes and returns only the rows this call actually deleted.
     *
     * <p>A claim row is the authority to give the item.  Reading all rows,
     * handing their items out, and deleting by owner later let a second join
     * or another asynchronous delivery path use the same snapshot twice.
     * Selecting row ids then conditionally deleting each one in one
     * transaction makes a competing caller receive an empty result instead.
     */
    public List<ItemStack> takeClaims(UUID owner) throws SQLException {
        try (Connection c = database.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                List<ClaimRow> candidates = new ArrayList<>();
                try (PreparedStatement select = c.prepareStatement(
                        "SELECT id, item FROM trade_claims WHERE owner_uuid = ?")) {
                    select.setString(1, owner.toString());
                    try (ResultSet results = select.executeQuery()) {
                        while (results.next()) {
                            candidates.add(new ClaimRow(results.getLong("id"),
                                    ItemStack.deserializeBytes(results.getBytes("item"))));
                        }
                    }
                }
                List<ItemStack> taken = new ArrayList<>(candidates.size());
                try (PreparedStatement delete = c.prepareStatement(
                        "DELETE FROM trade_claims WHERE id = ? AND owner_uuid = ?")) {
                    for (ClaimRow candidate : candidates) {
                        delete.setLong(1, candidate.id());
                        delete.setString(2, owner.toString());
                        if (delete.executeUpdate() == 1) {
                            taken.add(candidate.item());
                        }
                    }
                }
                c.commit();
                return taken;
            } catch (SQLException error) {
                c.rollback();
                throw error;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private record ClaimRow(long id, ItemStack item) { }

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

    /** Re-queues legacy escrow money if its recipient left before main-thread delivery. */
    public void addPendingMoney(UUID owner, double amount) throws SQLException {
        if (!Double.isFinite(amount) || amount <= 0) {
            return;
        }
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO trade_pending_money (uuid, amount) VALUES (?, ?) ON CONFLICT(uuid) "
                        + "DO UPDATE SET amount = amount + excluded.amount")) {
            s.setString(1, owner.toString());
            s.setDouble(2, amount);
            s.executeUpdate();
        } catch (SQLException unsupportedUpsert) {
            try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                    "INSERT INTO trade_pending_money (uuid, amount) VALUES (?, ?) ON DUPLICATE KEY "
                            + "UPDATE amount = amount + VALUES(amount)")) {
                s.setString(1, owner.toString());
                s.setDouble(2, amount);
                s.executeUpdate();
            }
        }
    }
    public double takePendingMoney(UUID owner) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false); double amount = 0;
            try (PreparedStatement read = c.prepareStatement("SELECT amount FROM trade_pending_money WHERE uuid = ?")) { read.setString(1, owner.toString()); try (ResultSet r = read.executeQuery()) { if (r.next()) amount = r.getDouble(1); } }
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM trade_pending_money WHERE uuid = ?")) { delete.setString(1, owner.toString()); delete.executeUpdate(); }
            c.commit(); return amount;
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
