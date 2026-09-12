package me.vertex.core.trade;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL storage for short-lived escrow and immutable audit rows. */
public final class TradeStorage {
    private static final String CREATE_PENDING_PAYOUTS = """
            CREATE TABLE IF NOT EXISTS trade_pending_payouts (
              payout_key VARCHAR(160) PRIMARY KEY,
              owner_uuid CHAR(36) NOT NULL,
              currency VARCHAR(8) NOT NULL,
              amount DOUBLE NOT NULL,
              created_at BIGINT NOT NULL,
              state VARCHAR(16) NOT NULL DEFAULT 'READY',
              delivery_started_at BIGINT NULL
            )""";
    private final Database database;
    private final String historyTable;
    private final String escrowTable;
    private final TradeOwnership ownership;
    private final boolean sharedNetwork;

    public TradeStorage(Database database) {
        this(database, "standalone", false);
    }

    public TradeStorage(Database database, String shardId, boolean sharedNetwork) {
        this.database = database;
        String shard = shardId == null ? "" : shardId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!shard.matches("[a-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid trade shard identity");
        this.ownership = new TradeOwnership(database, shard);
        this.sharedNetwork = sharedNetwork;
        boolean mysql = database.dialect() == Database.Dialect.MYSQL;
        historyTable = mysql ? """
                CREATE TABLE IF NOT EXISTS trade_history (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, requester_uuid CHAR(36) NOT NULL, target_uuid CHAR(36) NOT NULL,
                  requester_name VARCHAR(64) NOT NULL, target_name VARCHAR(64) NOT NULL, requester_items LONGBLOB,
                  target_items LONGBLOB, requester_money DOUBLE NOT NULL, target_money DOUBLE NOT NULL,
                  requester_exp INT NOT NULL, target_exp INT NOT NULL, created_at BIGINT NOT NULL, status VARCHAR(16) NOT NULL)"""
                : """
                CREATE TABLE IF NOT EXISTS trade_history (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, requester_uuid CHAR(36) NOT NULL, target_uuid CHAR(36) NOT NULL,
                  requester_name VARCHAR(64) NOT NULL, target_name VARCHAR(64) NOT NULL, requester_items BLOB,
                  target_items BLOB, requester_money DOUBLE NOT NULL, target_money DOUBLE NOT NULL,
                  requester_exp INT NOT NULL, target_exp INT NOT NULL, created_at BIGINT NOT NULL, status VARCHAR(16) NOT NULL)""";
        escrowTable = mysql ? """
                CREATE TABLE IF NOT EXISTS trade_escrow (
                  session_id CHAR(36) NOT NULL, owner_uuid CHAR(36) NOT NULL, items LONGBLOB,
                  money DOUBLE NOT NULL, experience INT NOT NULL, PRIMARY KEY(session_id, owner_uuid))"""
                : """
                CREATE TABLE IF NOT EXISTS trade_escrow (
                  session_id CHAR(36) NOT NULL, owner_uuid CHAR(36) NOT NULL, items BLOB,
                  money DOUBLE NOT NULL, experience INT NOT NULL, PRIMARY KEY(session_id, owner_uuid))""";
    }

    public void init() throws SQLException {
        try (Connection c = database.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(database.dialect() == Database.Dialect.MYSQL
                    ? "CREATE TABLE IF NOT EXISTS trade_claims (id BIGINT AUTO_INCREMENT PRIMARY KEY, owner_uuid CHAR(36) NOT NULL, item LONGBLOB NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'READY', reservation_token CHAR(36) NULL, reserved_at BIGINT NULL)"
                    : "CREATE TABLE IF NOT EXISTS trade_claims (id INTEGER PRIMARY KEY AUTOINCREMENT, owner_uuid CHAR(36) NOT NULL, item BLOB NOT NULL, state VARCHAR(16) NOT NULL DEFAULT 'READY', reservation_token CHAR(36) NULL, reserved_at BIGINT NULL)");
            SqlSchema.ensureColumn(c, "trade_claims", "state", "VARCHAR(16) NOT NULL DEFAULT 'READY'");
            SqlSchema.ensureColumn(c, "trade_claims", "reservation_token", "CHAR(36) NULL");
            SqlSchema.ensureColumn(c, "trade_claims", "reserved_at", "BIGINT NULL");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_pending_exp (uuid CHAR(36) PRIMARY KEY, levels INT NOT NULL)");
            // Only used to return legacy pre-item-only-trade escrow safely.
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_pending_money (uuid CHAR(36) PRIMARY KEY, amount DOUBLE NOT NULL)");
            s.executeUpdate(CREATE_PENDING_PAYOUTS);
            s.executeUpdate(escrowTable);
            s.executeUpdate(historyTable);
            ownership.init(c);
            s.executeUpdate("CREATE TABLE IF NOT EXISTS trade_sessions (session_id CHAR(36) PRIMARY KEY, "
                    + "owner_shard VARCHAR(64) NOT NULL, boot_id CHAR(36) NOT NULL, state VARCHAR(16) NOT NULL)");
            SqlSchema.ensureIndex(c, "trade_sessions", "idx_trade_session_owner", false, "owner_shard", "state");
            SqlSchema.ensureIndex(c, "trade_history", "idx_trade_history_requester", false,
                    "requester_uuid", "created_at DESC");
            SqlSchema.ensureIndex(c, "trade_history", "idx_trade_history_target", false,
                    "target_uuid", "created_at DESC");
            SqlSchema.ensureIndex(c, "trade_history", "idx_trade_history_status_time", false,
                    "status", "created_at DESC");
            SqlSchema.ensureLongBlob(c, database.dialect(), "trade_history", "requester_items", true);
            SqlSchema.ensureLongBlob(c, database.dialect(), "trade_history", "target_items", true);
            SqlSchema.ensureLongBlob(c, database.dialect(), "trade_escrow", "items", true);
            SqlSchema.ensureLongBlob(c, database.dialect(), "trade_claims", "item", false);
            migrateLegacyPendingCredits(c);
        }
    }

    /** Called once at startup, after schema initialization and before opening trades. */
    public void startOwnership() throws SQLException {
        ownership.acquire();
        if (sharedNetwork) return; // Old shared escrow has no trustworthy shard association.
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                ownership.require(c);
                try (PreparedStatement check = c.prepareStatement("SELECT 1 FROM trade_owners WHERE shard_id<>?")) {
                    check.setString(1, ownership.shard);
                    try (ResultSet rows = check.executeQuery()) {
                        if (rows.next()) throw new SQLException("Standalone mode cannot adopt a database with other shard owners. "
                                + "Use network.enabled: true or a dedicated single-server database.");
                    }
                }
                try (PreparedStatement adopt = c.prepareStatement(
                        "INSERT INTO trade_sessions (session_id,owner_shard,boot_id,state) "
                                + "SELECT DISTINCT e.session_id,?,'00000000-0000-0000-0000-000000000000','OPEN' "
                                + "FROM trade_escrow e LEFT JOIN trade_sessions s ON s.session_id=e.session_id "
                                + "WHERE s.session_id IS NULL")) {
                    adopt.setString(1, ownership.shard);
                    adopt.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException error) {
                c.rollback();
                throw error;
            }
        }
    }

    public boolean ownershipHealthy() { return ownership.healthy(); }
    public void renewOwnership() throws SQLException { ownership.renew(); }
    public void releaseOwnership() throws SQLException { ownership.release(); }

    public int unownedEscrowCount() throws SQLException {
        try (Connection c = database.getConnection(); Statement s = c.createStatement(); ResultSet rows = s.executeQuery(
                "SELECT COUNT(DISTINCT e.session_id) FROM trade_escrow e LEFT JOIN trade_sessions t "
                        + "ON t.session_id=e.session_id WHERE t.session_id IS NULL")) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    public void replaceEscrow(TradeSnapshot snapshot) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                ownership.require(c);
                SessionOwner existing = sessionOwner(c, snapshot.sessionId());
                if (existing == null) {
                    // Do not accidentally attach a legacy row to a fresh session with the same ID.
                    try (PreparedStatement legacy = c.prepareStatement("SELECT 1 FROM trade_escrow WHERE session_id=?")) {
                        legacy.setString(1, snapshot.sessionId().toString());
                        try (ResultSet rows = legacy.executeQuery()) {
                            if (rows.next()) throw new SQLException("Unowned legacy trade requires inspection");
                        }
                    }
                    try (PreparedStatement insert = c.prepareStatement(
                            "INSERT INTO trade_sessions (session_id,owner_shard,boot_id,state) VALUES (?,?,?,'OPEN')")) {
                        insert.setString(1, snapshot.sessionId().toString());
                        insert.setString(2, ownership.shard);
                        insert.setString(3, ownership.boot);
                        insert.executeUpdate();
                    }
                } else requireLiveOwner(existing);
                try (PreparedStatement delete = c.prepareStatement("DELETE FROM trade_escrow WHERE session_id = ?")) {
                    delete.setString(1, snapshot.sessionId().toString()); delete.executeUpdate();
                }
                try (PreparedStatement insert = c.prepareStatement("INSERT INTO trade_escrow (session_id, owner_uuid, items, money, experience) VALUES (?, ?, ?, ?, ?)")) {
                    writeEscrow(insert, snapshot.sessionId(), snapshot.requester(), snapshot.requesterItems(), snapshot.requesterHeldMoney(), snapshot.requesterHeldExperience());
                    writeEscrow(insert, snapshot.sessionId(), snapshot.target(), snapshot.targetItems(), snapshot.targetHeldMoney(), snapshot.targetHeldExperience());
                }
                c.commit();
            } catch (SQLException | RuntimeException error) {
                c.rollback();
                throw error;
            }
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

    public List<TradeEscrow> loadRecoverableEscrow() throws SQLException {
        List<TradeEscrow> out = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT e.session_id,e.owner_uuid,e.items,e.money,e.experience FROM trade_escrow e "
                        + "JOIN trade_sessions t ON t.session_id=e.session_id "
                        + "WHERE t.owner_shard=? AND t.boot_id<>? AND t.state='OPEN'")) {
            s.setString(1, ownership.shard);
            s.setString(2, ownership.boot);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) out.add(new TradeEscrow(UUID.fromString(r.getString(1)), UUID.fromString(r.getString(2)),
                        ItemStack.deserializeItemsFromBytes(r.getBytes(3)), r.getDouble(4), r.getInt(5)));
            }
        }
        return out;
    }

    /**
     * Converts a live trade escrow into recipient claims and history in one
     * transaction. A completed trade swaps ownership; a cancelled trade
     * returns each side to its original owner.
     */
    public void settleEscrow(TradeSnapshot snapshot, String requesterName, String targetName,
            String status, boolean completed) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ownership.require(connection);
                SessionOwner owner = sessionOwner(connection, snapshot.sessionId());
                String terminal = completed ? "COMPLETED" : "CANCELLED";
                if (owner != null && owner.shard.equals(ownership.shard) && owner.boot.equals(ownership.boot)
                        && terminal.equals(owner.state)) {
                    connection.rollback();
                    return; // A retry after an uncertain commit may not emit claims/history again.
                }
                requireLiveOwner(owner);
                insertClaims(connection, snapshot.requester(),
                        completed ? snapshot.targetItems() : snapshot.requesterItems());
                insertClaims(connection, snapshot.target(),
                        completed ? snapshot.requesterItems() : snapshot.targetItems());
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM trade_escrow WHERE session_id = ?")) {
                    delete.setString(1, snapshot.sessionId().toString());
                    delete.executeUpdate();
                }
                insertHistory(connection, snapshot, requesterName, targetName, status);
                finishSession(connection, snapshot.sessionId(), terminal);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    private static void insertClaims(Connection connection, UUID owner, ItemStack[] items) throws SQLException {
        try (PreparedStatement claim = connection.prepareStatement(
                "INSERT INTO trade_claims (owner_uuid, item) VALUES (?, ?)")) {
            for (ItemStack item : items) {
                if (item == null || item.isEmpty()) continue;
                claim.setString(1, owner.toString());
                claim.setBytes(2, item.serializeAsBytes());
                claim.addBatch();
            }
            claim.executeBatch();
        }
    }

    /**
     * Recovers an entire abandoned session owned by this shard's previous boot.
     * The supplied row identifies the session only: contents are reread under
     * the same SQL locks as the terminal transition, never trusted from a stale list.
     */
    public void recoverEscrow(TradeEscrow escrow) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                ownership.require(c);
                SessionOwner owner = sessionOwner(c, escrow.sessionId());
                if (owner == null || !ownership.shard.equals(owner.shard) || ownership.boot.equals(owner.boot)
                        || !"OPEN".equals(owner.state)) {
                    c.rollback();
                    return;
                }
                try (PreparedStatement source = c.prepareStatement(
                        "SELECT owner_uuid,items,money,experience FROM trade_escrow WHERE session_id=?")) {
                    source.setString(1, escrow.sessionId().toString());
                    try (ResultSet rows = source.executeQuery()) {
                        while (rows.next()) {
                            UUID recipient = UUID.fromString(rows.getString(1));
                            insertClaims(c, recipient, ItemStack.deserializeItemsFromBytes(rows.getBytes(2)));
                            int experience = rows.getInt(4);
                            double money = rows.getDouble(3);
                            if (experience > 0) insertPendingPayout(c,
                                    "legacy-escrow:" + escrow.sessionId() + ":" + recipient + ":exp",
                                    recipient, PayoutCurrency.EXP, experience, System.currentTimeMillis());
                            if (money > 0) insertPendingPayout(c,
                                    "legacy-escrow:" + escrow.sessionId() + ":" + recipient + ":money",
                                    recipient, PayoutCurrency.MONEY, money, System.currentTimeMillis());
                        }
                    }
                }
                try (PreparedStatement delete = c.prepareStatement("DELETE FROM trade_escrow WHERE session_id=?")) {
                    delete.setString(1, escrow.sessionId().toString());
                    delete.executeUpdate();
                }
                finishSession(c, escrow.sessionId(), "RECOVERED");
                c.commit();
            } catch (SQLException | RuntimeException error) {
                c.rollback();
                throw error;
            }
        }
    }

    private record SessionOwner(String shard, String boot, String state) { }

    private SessionOwner sessionOwner(Connection c, UUID id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT owner_shard,boot_id,state FROM trade_sessions WHERE session_id=?"
                + (database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : ""))) {
            s.setString(1, id.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? new SessionOwner(r.getString(1), r.getString(2), r.getString(3)) : null;
            }
        }
    }

    private void requireLiveOwner(SessionOwner owner) throws SQLException {
        if (owner == null || !ownership.shard.equals(owner.shard) || !ownership.boot.equals(owner.boot)
                || !"OPEN".equals(owner.state)) throw new SQLException("Trade is absent, terminal or owned by another process");
    }

    private void finishSession(Connection c, UUID id, String state) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE trade_sessions SET state=? WHERE session_id=? AND state='OPEN'")) {
            s.setString(1, state);
            s.setString(2, id.toString());
            if (s.executeUpdate() != 1) throw new SQLException("Trade session changed before completion");
        }
    }

    public void insertClaim(UUID owner, ItemStack item) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement("INSERT INTO trade_claims (owner_uuid, item) VALUES (?, ?)")) {
            s.setString(1, owner.toString()); s.setBytes(2, item.serializeAsBytes()); s.executeUpdate();
        }
    }
    public List<ItemStack> loadClaims(UUID owner) throws SQLException {
        List<ItemStack> out = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT item FROM trade_claims WHERE owner_uuid = ? AND state = 'READY'")) {
            s.setString(1, owner.toString()); try (ResultSet r = s.executeQuery()) { while (r.next()) out.add(ItemStack.deserializeBytes(r.getBytes(1))); }
        }
        return out;
    }

    public record ClaimRow(long id, ItemStack item) { }
    public record ClaimReservation(String token, UUID owner, List<ClaimRow> claims) { }

    public ClaimReservation reserveClaims(UUID owner) throws SQLException {
        String token = UUID.randomUUID().toString();
        try (Connection c = database.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                List<ClaimRow> candidates = new ArrayList<>();
                try (PreparedStatement select = c.prepareStatement(
                        "SELECT id, item FROM trade_claims WHERE owner_uuid = ? AND state = 'READY'")) {
                    select.setString(1, owner.toString());
                    try (ResultSet results = select.executeQuery()) {
                        while (results.next()) {
                            candidates.add(new ClaimRow(results.getLong("id"),
                                    ItemStack.deserializeBytes(results.getBytes("item"))));
                        }
                    }
                }
                try (PreparedStatement reserve = c.prepareStatement(
                        "UPDATE trade_claims SET state = 'DELIVERING', reservation_token = ?, reserved_at = ? "
                                + "WHERE id = ? AND owner_uuid = ? AND state = 'READY'")) {
                    for (ClaimRow candidate : candidates) {
                        reserve.setString(1, token);
                        reserve.setLong(2, System.currentTimeMillis());
                        reserve.setLong(3, candidate.id());
                        reserve.setString(4, owner.toString());
                        if (reserve.executeUpdate() != 1) {
                            c.rollback();
                            return new ClaimReservation(token, owner, List.of());
                        }
                    }
                }
                c.commit();
                return new ClaimReservation(token, owner, List.copyOf(candidates));
            } catch (SQLException error) {
                c.rollback();
                throw error;
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public List<ClaimReservation> loadDeliveringClaims(UUID owner) throws SQLException {
        java.util.Map<String, List<ClaimRow>> grouped = new java.util.LinkedHashMap<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT id, item, reservation_token FROM trade_claims "
                        + "WHERE owner_uuid = ? AND state = 'DELIVERING' ORDER BY id")) {
            s.setString(1, owner.toString());
            try (ResultSet results = s.executeQuery()) {
                while (results.next()) {
                    String token = results.getString("reservation_token");
                    if (token == null || token.isBlank()) continue;
                    grouped.computeIfAbsent(token, ignored -> new ArrayList<>()).add(new ClaimRow(
                            results.getLong("id"), ItemStack.deserializeBytes(results.getBytes("item"))));
                }
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new ClaimReservation(entry.getKey(), owner, List.copyOf(entry.getValue())))
                .toList();
    }

    public int completeReservation(UUID owner, String token) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "DELETE FROM trade_claims WHERE owner_uuid = ? AND state = 'DELIVERING' "
                        + "AND reservation_token = ?")) {
            s.setString(1, owner.toString());
            s.setString(2, token);
            return s.executeUpdate();
        }
    }

    public int releaseReservation(UUID owner, String token) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE trade_claims SET state = 'READY', reservation_token = NULL, reserved_at = NULL "
                        + "WHERE owner_uuid = ? AND state = 'DELIVERING' AND reservation_token = ?")) {
            s.setString(1, owner.toString());
            s.setString(2, token);
            return s.executeUpdate();
        }
    }

    /** Compatibility helper for storage tests/non-Bukkit consumers. */
    public List<ItemStack> takeClaims(UUID owner) throws SQLException {
        ClaimReservation reservation = reserveClaims(owner);
        if (!reservation.claims().isEmpty()) completeReservation(owner, reservation.token());
        return reservation.claims().stream().map(ClaimRow::item).toList();
    }

    public void deleteClaims(UUID owner) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM trade_claims WHERE owner_uuid = ?")) { s.setString(1, owner.toString()); s.executeUpdate(); }
    }
    public enum PayoutCurrency { MONEY, EXP }
    public record PendingPayout(String key, UUID owner, PayoutCurrency currency, double amount,
            long createdAt, String state, Long deliveryStartedAt) { }

    public List<PendingPayout> loadPendingPayouts() throws SQLException {
        return loadPayouts("READY");
    }

    public List<PendingPayout> loadUncertainPayouts() throws SQLException {
        return loadPayouts("DELIVERING");
    }

    private List<PendingPayout> loadPayouts(String state) throws SQLException {
        List<PendingPayout> payouts = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement statement = c.prepareStatement(
                "SELECT payout_key,owner_uuid,currency,amount,created_at,state,delivery_started_at "
                        + "FROM trade_pending_payouts WHERE state=? ORDER BY created_at,payout_key")) {
            statement.setString(1, state);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long rawStarted = rows.getLong(7);
                    Long started = rows.wasNull() ? null : rawStarted;
                    payouts.add(new PendingPayout(rows.getString(1), UUID.fromString(rows.getString(2)),
                            PayoutCurrency.valueOf(rows.getString(3)), rows.getDouble(4), rows.getLong(5),
                            rows.getString(6), started));
                }
            }
        }
        return payouts;
    }

    public boolean reservePendingPayout(String key, long now) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement statement = c.prepareStatement(
                "UPDATE trade_pending_payouts SET state='DELIVERING',delivery_started_at=? "
                        + "WHERE payout_key=? AND state='READY'")) {
            statement.setLong(1, now); statement.setString(2, key);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean releasePendingPayout(String key) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement statement = c.prepareStatement(
                "UPDATE trade_pending_payouts SET state='READY',delivery_started_at=NULL "
                        + "WHERE payout_key=? AND state='DELIVERING'")) {
            statement.setString(1, key); return statement.executeUpdate() == 1;
        }
    }

    public boolean acknowledgePendingPayout(String key) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement statement = c.prepareStatement(
                "DELETE FROM trade_pending_payouts WHERE payout_key=? AND state='DELIVERING'")) {
            statement.setString(1, key); return statement.executeUpdate() == 1;
        }
    }

    private static void insertPendingPayout(Connection c, String key, UUID owner, PayoutCurrency currency,
            double amount, long createdAt) throws SQLException {
        if (!Double.isFinite(amount) || amount <= 0) throw new SQLException("Invalid Trade payout " + key);
        try (PreparedStatement existing = c.prepareStatement(
                "SELECT owner_uuid,currency,amount FROM trade_pending_payouts WHERE payout_key=?")) {
            existing.setString(1, key);
            try (ResultSet row = existing.executeQuery()) {
                if (row.next()) {
                    if (!owner.toString().equals(row.getString(1)) || !currency.name().equals(row.getString(2))
                            || Double.compare(amount, row.getDouble(3)) != 0) {
                        throw new SQLException("Conflicting Trade payout " + key);
                    }
                    return;
                }
            }
        }
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO trade_pending_payouts(payout_key,owner_uuid,currency,amount,created_at,state,"
                        + "delivery_started_at) VALUES(?,?,?,?,?,'READY',NULL)")) {
            insert.setString(1, key); insert.setString(2, owner.toString()); insert.setString(3, currency.name());
            insert.setDouble(4, amount); insert.setLong(5, createdAt); insert.executeUpdate();
        }
    }

    /** Atomically imports one-time credits left by pre-outbox builds. */
    private void migrateLegacyPendingCredits(Connection c) throws SQLException {
        boolean previous = c.getAutoCommit(); c.setAutoCommit(false);
        try {
            migrateLegacyTable(c, "trade_pending_exp", "levels", PayoutCurrency.EXP);
            migrateLegacyTable(c, "trade_pending_money", "amount", PayoutCurrency.MONEY);
            c.commit();
        } catch (SQLException error) {
            c.rollback(); throw error;
        } finally {
            c.setAutoCommit(previous);
        }
    }

    private static void migrateLegacyTable(Connection c, String table, String amountColumn,
            PayoutCurrency currency) throws SQLException {
        record Legacy(String rawUuid, UUID owner, double amount) { }
        List<Legacy> rows = new ArrayList<>();
        try (PreparedStatement select = c.prepareStatement("SELECT uuid," + amountColumn + " FROM " + table);
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                String rawUuid = result.getString(1);
                try {
                    double amount = result.getDouble(2);
                    if (!Double.isFinite(amount) || amount <= 0
                            || (currency == PayoutCurrency.EXP && amount != Math.rint(amount))) {
                        throw new SQLException("Invalid legacy Trade " + currency + " amount for " + rawUuid);
                    }
                    rows.add(new Legacy(rawUuid, UUID.fromString(rawUuid), amount));
                } catch (IllegalArgumentException error) {
                    throw new SQLException("Invalid UUID in " + table + ": " + rawUuid, error);
                }
            }
        }
        long now = System.currentTimeMillis();
        for (Legacy row : rows) {
            String key = "legacy-" + currency.name().toLowerCase(java.util.Locale.ROOT) + ":" + row.owner();
            insertPendingPayout(c, key, row.owner(), currency, row.amount(), now);
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM " + table + " WHERE uuid=?")) {
                delete.setString(1, row.rawUuid());
                if (delete.executeUpdate() != 1) throw new SQLException("Legacy Trade row changed for " + row.owner());
            }
        }
    }
    public void insertHistory(TradeSnapshot snapshot, String requesterName, String targetName, String status) throws SQLException {
        try (Connection c = database.getConnection()) {
            insertHistory(c, snapshot, requesterName, targetName, status);
        }
    }

    private static void insertHistory(Connection c, TradeSnapshot snapshot, String requesterName,
            String targetName, String status) throws SQLException {
        String sql = "INSERT INTO trade_history (requester_uuid, target_uuid, requester_name, target_name, requester_items, target_items, requester_money, target_money, requester_exp, target_exp, created_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement s = c.prepareStatement(sql)) {
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
