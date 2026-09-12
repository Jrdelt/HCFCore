package me.vertex.core.coinflip;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;
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
import java.util.Optional;
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
                items LONGBLOB NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_COINFLIPS_SQLITE = """
            CREATE TABLE IF NOT EXISTS coinflips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                host_uuid CHAR(36) NOT NULL,
                target_uuid CHAR(36) NULL,
                type VARCHAR(16) NOT NULL,
                amount DOUBLE NOT NULL,
                items LONGBLOB NULL,
                created_at BIGINT NOT NULL
            )""";

    private static final String CREATE_CLAIMS_MYSQL = """
            CREATE TABLE IF NOT EXISTS coinflip_claims (
                id INT AUTO_INCREMENT PRIMARY KEY,
                winner_uuid CHAR(36) NOT NULL,
                items LONGBLOB NOT NULL,
                won_at BIGINT NOT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'READY',
                reservation_token CHAR(36) NULL,
                reserved_at BIGINT NULL
            )""";
    private static final String CREATE_CLAIMS_SQLITE = """
            CREATE TABLE IF NOT EXISTS coinflip_claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                winner_uuid CHAR(36) NOT NULL,
                items BLOB NOT NULL,
                won_at BIGINT NOT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'READY',
                reservation_token CHAR(36) NULL,
                reserved_at BIGINT NULL
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

    private static final String CREATE_PENDING_PAYOUTS = """
            CREATE TABLE IF NOT EXISTS coinflip_pending_payouts (
                payout_key VARCHAR(64) NOT NULL PRIMARY KEY,
                owner_uuid CHAR(36) NOT NULL,
                currency VARCHAR(16) NOT NULL,
                amount DOUBLE NOT NULL,
                created_at BIGINT NOT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'READY',
                delivery_started_at BIGINT NULL
            )""";
    private static final String CREATE_CREATION_INTENTS = """
            CREATE TABLE IF NOT EXISTS coinflip_creation_intents (
                intent_key CHAR(36) NOT NULL PRIMARY KEY,
                host_uuid CHAR(36) NOT NULL,
                target_uuid CHAR(36) NULL,
                type VARCHAR(16) NOT NULL,
                amount DOUBLE NOT NULL,
                items LONGBLOB NULL,
                created_at BIGINT NOT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'PREPARED'
            )""";
    private static final String CREATE_CREATION_INTENT_AUDIT = """
            CREATE TABLE IF NOT EXISTS coinflip_creation_intent_audit (
                intent_key CHAR(36) NOT NULL PRIMARY KEY,
                host_uuid CHAR(36) NOT NULL,
                target_uuid CHAR(36) NULL,
                type VARCHAR(16) NOT NULL,
                amount DOUBLE NOT NULL,
                decision VARCHAR(16) NOT NULL,
                actor_uuid CHAR(36) NOT NULL,
                actor_name VARCHAR(64) NOT NULL,
                resolved_at BIGINT NOT NULL
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
                items LONGBLOB NOT NULL,
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
            SqlSchema.ensureColumn(connection, "coinflip_claims", "state",
                    "VARCHAR(16) NOT NULL DEFAULT 'READY'");
            SqlSchema.ensureColumn(connection, "coinflip_claims", "reservation_token", "CHAR(36) NULL");
            SqlSchema.ensureColumn(connection, "coinflip_claims", "reserved_at", "BIGINT NULL");
            SqlSchema.ensureIndex(connection, "coinflip_claims", "idx_coinflip_claims_winner", false,
                    "winner_uuid");
            statement.executeUpdate(CREATE_BANS);
            statement.executeUpdate(CREATE_PENDING_EXP);
            statement.executeUpdate(CREATE_PENDING_PAYOUTS);
            SqlSchema.ensureColumn(connection, "coinflip_pending_payouts", "state",
                    "VARCHAR(16) NOT NULL DEFAULT 'READY'");
            SqlSchema.ensureColumn(connection, "coinflip_pending_payouts", "delivery_started_at", "BIGINT NULL");
            statement.executeUpdate(CREATE_CREATION_INTENTS);
            statement.executeUpdate(CREATE_CREATION_INTENT_AUDIT);
            SqlSchema.ensureColumn(connection,"coinflip_creation_intents","state",
                    "VARCHAR(16) NOT NULL DEFAULT 'ESCROWED'");
            statement.executeUpdate(createResultNotifications);
            SqlSchema.ensureIndex(connection, "coinflip_result_notifications",
                    "idx_coinflip_result_notifications_recipient", false, "recipient_uuid");
            statement.executeUpdate(createLog);
            SqlSchema.ensureIndex(connection, "coinflip_log", "idx_coinflip_log_resolved_at", false,
                    "resolved_at DESC");
            statement.executeUpdate(createPendingMatches);
            SqlSchema.ensureLongBlob(connection, database.dialect(), "coinflips", "items", true);
            SqlSchema.ensureLongBlob(connection, database.dialect(), "coinflip_claims", "items", false);
            SqlSchema.ensureLongBlob(connection, database.dialect(), "coinflip_creation_intents", "items", true);
            SqlSchema.ensureLongBlob(connection, database.dialect(), "coinflip_pending_matches", "items", false);
            migrateLegacyPendingExp(connection);
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

    public record CreationIntent(String key, UUID hostUuid, UUID targetUuid, CoinflipType type, double amount,
            ItemStack[] items, long createdAt,String state) { }

    public CreationIntent insertCreationIntent(UUID hostUuid, UUID targetUuid, CoinflipType type, double amount,
            ItemStack[] items, long createdAt) throws SQLException {
        return insertCreationIntent(hostUuid,targetUuid,type,amount,items,createdAt,"PREPARED");
    }

    public CreationIntent insertCreationIntent(UUID hostUuid, UUID targetUuid, CoinflipType type, double amount,
            ItemStack[] items, long createdAt,String state) throws SQLException {
        CreationIntent intent = new CreationIntent(UUID.randomUUID().toString(), hostUuid, targetUuid, type, amount,
                items == null ? new ItemStack[0] : items.clone(), createdAt,state);
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO coinflip_creation_intents (intent_key, host_uuid, target_uuid, type, amount, items, "
                        + "created_at,state) VALUES (?, ?, ?, ?, ?, ?, ?,?)")) {
            statement.setString(1, intent.key());
            statement.setString(2, hostUuid.toString());
            setNullableUuid(statement, 3, targetUuid);
            statement.setString(4, type.name());
            statement.setDouble(5, amount);
            if (items == null || items.length == 0) statement.setNull(6, Types.BLOB);
            else statement.setBytes(6, ItemStack.serializeItemsAsBytes(items));
            statement.setLong(7, createdAt);
            statement.setString(8,state);
            statement.executeUpdate();
        }
        return intent;
    }

    public List<CreationIntent> loadCreationIntents() throws SQLException {
        List<CreationIntent> intents = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT intent_key, host_uuid, target_uuid, type, amount, items, created_at,state "
                        + "FROM coinflip_creation_intents ORDER BY created_at"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) intents.add(readCreationIntent(rows));
        }
        return intents;
    }

    public Optional<CreationIntent> loadCreationIntent(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT intent_key,host_uuid,target_uuid,type,amount,items,created_at,state "
                        + "FROM coinflip_creation_intents WHERE intent_key=?")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readCreationIntent(row)) : Optional.empty();
            }
        }
    }

    private static CreationIntent readCreationIntent(ResultSet row) throws SQLException {
        String target = row.getString(3);
        byte[] items = row.getBytes(6);
        return new CreationIntent(row.getString(1), UUID.fromString(row.getString(2)),
                target == null ? null : UUID.fromString(target), CoinflipType.valueOf(row.getString(4)),
                row.getDouble(5), items == null ? new ItemStack[0] : ItemStack.deserializeItemsFromBytes(items),
                row.getLong(7), row.getString(8));
    }

    public boolean setCreationIntentState(String key,String expected,String next)throws SQLException{
        try(Connection connection=database.getConnection();PreparedStatement update=connection.prepareStatement(
                "UPDATE coinflip_creation_intents SET state=? WHERE intent_key=? AND state=?")){
            update.setString(1,next);update.setString(2,key);update.setString(3,expected);return update.executeUpdate()==1;
        }
    }

    public boolean deleteCreationIntent(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM coinflip_creation_intents WHERE intent_key = ?")) {
            statement.setString(1, key);
            return statement.executeUpdate() == 1;
        }
    }

    public int activateCreationIntent(CreationIntent intent) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO coinflips (host_uuid, target_uuid, type, amount, items, created_at) "
                                + "SELECT host_uuid, target_uuid, type, amount, items, created_at "
                                + "FROM coinflip_creation_intents WHERE intent_key = ? AND state='ESCROWED'",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, intent.key());
                    if (insert.executeUpdate() != 1) { connection.rollback(); return -1; }
                    try (ResultSet keys = insert.getGeneratedKeys()) { id = keys.next() ? keys.getInt(1) : -1; }
                }
                if (id < 0) { connection.rollback(); return -1; }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM coinflip_creation_intents WHERE intent_key = ?")) {
                    delete.setString(1, intent.key());
                    if (delete.executeUpdate() != 1) { connection.rollback(); return -1; }
                }
                connection.commit();
                return id;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally { connection.setAutoCommit(previous); }
        }
    }

    public enum IntentResolutionStatus {
        ACTIVATED, DISCARDED, ALREADY_RESOLVED, CONFLICT, INVALID_STATE, MISSING, STORAGE_ERROR
    }

    public record IntentResolution(IntentResolutionStatus status, CreationIntent intent, int coinflipId,
            String priorDecision) { }

    public record IntentAudit(String key, UUID hostUuid, UUID targetUuid, CoinflipType type, double amount,
            String decision, String actorUuid, String actorName, long resolvedAt) { }

    /** Atomically applies and permanently audits a staff decision about an interrupted wager debit. */
    public IntentResolution resolveCreationIntent(String key, boolean debited, UUID actorUuid,
            String actorName, long resolvedAt) throws SQLException {
        String decision = debited ? "DEBITED" : "NOT_DEBITED";
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                CreationIntent intent = lockCreationIntent(connection, key);
                if (intent == null) {
                    IntentAudit old = loadIntentAudit(connection, key);
                    connection.rollback();
                    if (old == null) return new IntentResolution(IntentResolutionStatus.MISSING, null, -1, null);
                    IntentResolutionStatus status = decision.equals(old.decision())
                            ? IntentResolutionStatus.ALREADY_RESOLVED : IntentResolutionStatus.CONFLICT;
                    return new IntentResolution(status, null, -1, old.decision());
                }
                if (!"DEBITING".equals(intent.state()) || intent.type() == CoinflipType.ITEMS) {
                    connection.rollback();
                    return new IntentResolution(IntentResolutionStatus.INVALID_STATE, intent, -1, null);
                }

                int coinflipId = -1;
                if (debited) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO coinflips(host_uuid,target_uuid,type,amount,items,created_at) "
                                    + "VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                        insert.setString(1, intent.hostUuid().toString());
                        setNullableUuid(insert, 2, intent.targetUuid());
                        insert.setString(3, intent.type().name());
                        insert.setDouble(4, intent.amount());
                        if (intent.items().length == 0) insert.setNull(5, Types.BLOB);
                        else insert.setBytes(5, ItemStack.serializeItemsAsBytes(intent.items()));
                        insert.setLong(6, intent.createdAt());
                        if (insert.executeUpdate() != 1) throw new SQLException("Could not activate intent " + key);
                        try (ResultSet generated = insert.getGeneratedKeys()) {
                            if (!generated.next()) throw new SQLException("No coinflip id for intent " + key);
                            coinflipId = generated.getInt(1);
                        }
                    }
                }
                insertIntentAudit(connection, intent, decision, actorUuid, actorName, resolvedAt);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM coinflip_creation_intents WHERE intent_key=? AND state='DEBITING'")) {
                    delete.setString(1, key);
                    if (delete.executeUpdate() != 1) throw new SQLException("Intent changed during resolution " + key);
                }
                connection.commit();
                return new IntentResolution(debited ? IntentResolutionStatus.ACTIVATED
                        : IntentResolutionStatus.DISCARDED, intent, coinflipId, null);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    public Optional<IntentAudit> loadIntentAudit(String key) throws SQLException {
        try (Connection connection = database.getConnection()) {
            return Optional.ofNullable(loadIntentAudit(connection, key));
        }
    }

    private CreationIntent lockCreationIntent(Connection connection, String key) throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT intent_key,host_uuid,target_uuid,type,amount,items,created_at,state "
                        + "FROM coinflip_creation_intents WHERE intent_key=?" + suffix)) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? readCreationIntent(row) : null;
            }
        }
    }

    private static IntentAudit loadIntentAudit(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT intent_key,host_uuid,target_uuid,type,amount,decision,actor_uuid,actor_name,resolved_at "
                        + "FROM coinflip_creation_intent_audit WHERE intent_key=?")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                String target = row.getString(3);
                return new IntentAudit(row.getString(1), UUID.fromString(row.getString(2)),
                        target == null ? null : UUID.fromString(target), CoinflipType.valueOf(row.getString(4)),
                        row.getDouble(5), row.getString(6), row.getString(7), row.getString(8), row.getLong(9));
            }
        }
    }

    private static void insertIntentAudit(Connection connection, CreationIntent intent, String decision,
            UUID actorUuid, String actorName, long resolvedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO coinflip_creation_intent_audit(intent_key,host_uuid,target_uuid,type,amount,decision,"
                        + "actor_uuid,actor_name,resolved_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, intent.key());
            statement.setString(2, intent.hostUuid().toString());
            setNullableUuid(statement, 3, intent.targetUuid());
            statement.setString(4, intent.type().name());
            statement.setDouble(5, intent.amount());
            statement.setString(6, decision);
            statement.setString(7, actorUuid.toString());
            statement.setString(8, actorName == null || actorName.isBlank() ? actorUuid.toString() : actorName);
            statement.setLong(9, resolvedAt);
            statement.executeUpdate();
        }
    }

    /**
     * Commits the durable result record, removes the now-unplayable listing,
     * and queues both result messages as one database transaction. A restart
     * during the client animation can therefore never reroll a winner or
     * silently lose the disconnected participant's result.
     */
    public boolean resolveCoinflip(Coinflip coinflip, UUID opponentUuid, UUID winnerUuid, String summary,
            long resolvedAt, ItemStack[] payoutItems, double payoutAmount, Integer pendingMatchId) throws SQLException {
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
                if (delete.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }

                if (coinflip.type() == CoinflipType.GC) {
                    me.vertex.core.gc.GcStorage.debitForSettlement(connection, opponentUuid,
                            me.vertex.core.gc.GcAction.COINFLIP_WAGER, (long) coinflip.amount(),
                            "coinflip:join:" + coinflip.id(), resolvedAt);
                }
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
                if (payoutItems != null && payoutItems.length > 0) {
                    insertClaim(connection, winnerUuid, payoutItems, resolvedAt);
                } else {
                    insertPendingPayout(connection, "resolve:" + coinflip.id(), winnerUuid, coinflip.type(),
                            payoutAmount, resolvedAt);
                }
                if (pendingMatchId != null && pendingMatchId >= 0) {
                    try (PreparedStatement pending = connection.prepareStatement(
                            "DELETE FROM coinflip_pending_matches WHERE id = ?")) {
                        pending.setInt(1, pendingMatchId);
                        pending.executeUpdate();
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    /** Cancels a wager and creates its durable refund in the same transaction. */
    public boolean cancelCoinflip(Coinflip coinflip, UUID cancelledBy, String summary, long cancelledAt)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM coinflips WHERE id = ?")) {
                    delete.setInt(1, coinflip.id());
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement log = connection.prepareStatement("""
                        INSERT INTO coinflip_log (host_uuid, opponent_uuid, type, summary, winner_uuid,
                            resolved_at, status, cancelled_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    log.setString(1, coinflip.hostUuid().toString());
                    log.setNull(2, Types.CHAR);
                    log.setString(3, coinflip.type().name());
                    log.setString(4, summary);
                    log.setNull(5, Types.CHAR);
                    log.setLong(6, cancelledAt);
                    log.setString(7, CoinflipLogEntry.Status.CANCELLED.name());
                    setNullableUuid(log, 8, cancelledBy);
                    log.executeUpdate();
                }
                if (coinflip.type() == CoinflipType.ITEMS) {
                    insertClaim(connection, coinflip.hostUuid(), coinflip.items(), cancelledAt);
                } else {
                    insertPendingPayout(connection, "cancel:" + coinflip.id(), coinflip.hostUuid(),
                            coinflip.type(), coinflip.amount(), cancelledAt);
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /** Returns a rejected/expired item match and deletes its escrow row atomically. */
    public boolean refundPendingMatch(CoinflipPendingMatch match, long refundedAt) throws SQLException {
        if (match.id() < 0) return false;
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM coinflip_pending_matches WHERE id = ? AND opponent_uuid = ?")) {
                delete.setInt(1, match.id());
                delete.setString(2, match.opponentUuid().toString());
                if (delete.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
                insertClaim(connection, match.opponentUuid(), match.items(), refundedAt);
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public record PendingPayout(String key, UUID ownerUuid, CoinflipType currency, double amount, long createdAt,
            String state, Long deliveryStartedAt) { }

    public List<PendingPayout> loadPendingPayouts() throws SQLException {
        List<PendingPayout> payouts = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT payout_key, owner_uuid, currency, amount, created_at, state, delivery_started_at "
                        + "FROM coinflip_pending_payouts WHERE state = 'READY'");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) payouts.add(new PendingPayout(results.getString(1),
                    UUID.fromString(results.getString(2)), CoinflipType.valueOf(results.getString(3)),
                    results.getDouble(4), results.getLong(5), results.getString(6), nullableLong(results, 7)));
        }
        return payouts;
    }

    public List<PendingPayout> loadUncertainPayouts() throws SQLException {
        List<PendingPayout> payouts = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT payout_key, owner_uuid, currency, amount, created_at, state, delivery_started_at "
                        + "FROM coinflip_pending_payouts WHERE state = 'DELIVERING' ORDER BY created_at");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) payouts.add(new PendingPayout(results.getString(1),
                    UUID.fromString(results.getString(2)), CoinflipType.valueOf(results.getString(3)),
                    results.getDouble(4), results.getLong(5), results.getString(6), nullableLong(results, 7)));
        }
        return payouts;
    }

    public boolean reservePendingPayout(String key, long now) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE coinflip_pending_payouts SET state = 'DELIVERING', delivery_started_at = ? "
                        + "WHERE payout_key = ? AND state = 'READY'")) {
            statement.setLong(1, now);
            statement.setString(2, key);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean releasePendingPayout(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE coinflip_pending_payouts SET state = 'READY', delivery_started_at = NULL "
                        + "WHERE payout_key = ? AND state = 'DELIVERING'")) {
            statement.setString(1, key);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean acknowledgeUncertainPayout(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM coinflip_pending_payouts WHERE payout_key = ? AND state = 'DELIVERING'")) {
            statement.setString(1, key);
            return statement.executeUpdate() == 1;
        }
    }

    public void deletePendingPayout(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM coinflip_pending_payouts WHERE payout_key = ?")) {
            statement.setString(1, key);
            statement.executeUpdate();
        }
    }

    private static Long nullableLong(ResultSet results, int index) throws SQLException {
        long value = results.getLong(index);
        return results.wasNull() ? null : value;
    }

    private static void insertPendingPayout(Connection connection, String key, UUID owner, CoinflipType currency,
            double amount, long createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO coinflip_pending_payouts (payout_key, owner_uuid, currency, amount, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, owner.toString());
            statement.setString(3, currency.name());
            statement.setDouble(4, amount);
            statement.setLong(5, createdAt);
            statement.executeUpdate();
        }
    }

    private static void insertClaim(Connection connection, UUID owner, ItemStack[] items, long createdAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO coinflip_claims (winner_uuid, items, won_at) VALUES (?, ?, ?)")) {
            statement.setString(1, owner.toString());
            statement.setBytes(2, ItemStack.serializeItemsAsBytes(items));
            statement.setLong(3, createdAt);
            statement.executeUpdate();
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

    // ---- Pending item claims ----

    public List<CoinflipClaim> loadClaims(UUID winnerUuid) throws SQLException {
        List<CoinflipClaim> claims = new ArrayList<>();
        String sql = "SELECT id, winner_uuid, items, won_at FROM coinflip_claims "
                + "WHERE winner_uuid = ? AND state = 'READY'";
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
        String sql = "SELECT 1 FROM coinflip_claims WHERE winner_uuid = ? AND state = 'READY' LIMIT 1";
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

    public record ClaimReservation(String token, UUID winnerUuid, List<CoinflipClaim> claims) { }

    public ClaimReservation reserveClaims(UUID winnerUuid) throws SQLException {
        String token = UUID.randomUUID().toString();
        try (Connection connection = database.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<CoinflipClaim> candidates = loadClaims(connection, winnerUuid, "READY");
                try (PreparedStatement reserve = connection.prepareStatement(
                        "UPDATE coinflip_claims SET state = 'DELIVERING', reservation_token = ?, reserved_at = ? "
                                + "WHERE id = ? AND winner_uuid = ? AND state = 'READY'")) {
                    for (CoinflipClaim candidate : candidates) {
                        reserve.setString(1, token);
                        reserve.setLong(2, System.currentTimeMillis());
                        reserve.setInt(3, candidate.id());
                        reserve.setString(4, winnerUuid.toString());
                        if (reserve.executeUpdate() != 1) {
                            connection.rollback();
                            return new ClaimReservation(token, winnerUuid, List.of());
                        }
                    }
                }
                connection.commit();
                return new ClaimReservation(token, winnerUuid, List.copyOf(candidates));
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private static List<CoinflipClaim> loadClaims(Connection connection, UUID winnerUuid, String state)
            throws SQLException {
        List<CoinflipClaim> claims = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, winner_uuid, items, won_at FROM coinflip_claims "
                        + "WHERE winner_uuid = ? AND state = ? ORDER BY id")) {
            statement.setString(1, winnerUuid.toString());
            statement.setString(2, state);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    claims.add(new CoinflipClaim(results.getInt("id"), winnerUuid,
                            ItemStack.deserializeItemsFromBytes(results.getBytes("items")),
                            results.getLong("won_at")));
                }
            }
        }
        return claims;
    }

    public List<ClaimReservation> loadDeliveringClaims(UUID winnerUuid) throws SQLException {
        Map<String, List<CoinflipClaim>> grouped = new java.util.LinkedHashMap<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, items, won_at, reservation_token FROM coinflip_claims "
                        + "WHERE winner_uuid = ? AND state = 'DELIVERING' ORDER BY id")) {
            statement.setString(1, winnerUuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    String token = results.getString("reservation_token");
                    if (token == null || token.isBlank()) continue;
                    grouped.computeIfAbsent(token, ignored -> new ArrayList<>()).add(new CoinflipClaim(
                            results.getInt("id"), winnerUuid,
                            ItemStack.deserializeItemsFromBytes(results.getBytes("items")),
                            results.getLong("won_at")));
                }
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new ClaimReservation(entry.getKey(), winnerUuid, List.copyOf(entry.getValue())))
                .toList();
    }

    public int completeReservation(UUID winnerUuid, String token) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM coinflip_claims WHERE winner_uuid = ? AND state = 'DELIVERING' "
                        + "AND reservation_token = ?")) {
            statement.setString(1, winnerUuid.toString());
            statement.setString(2, token);
            return statement.executeUpdate();
        }
    }

    public int releaseReservation(UUID winnerUuid, String token) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE coinflip_claims SET state = 'READY', reservation_token = NULL, reserved_at = NULL "
                        + "WHERE winner_uuid = ? AND state = 'DELIVERING' AND reservation_token = ?")) {
            statement.setString(1, winnerUuid.toString());
            statement.setString(2, token);
            return statement.executeUpdate();
        }
    }

    /** Compatibility helper for tests/non-Bukkit callers. */
    public List<CoinflipClaim> takeClaims(UUID winnerUuid) throws SQLException {
        ClaimReservation reservation = reserveClaims(winnerUuid);
        if (!reservation.claims().isEmpty()) completeReservation(winnerUuid, reservation.token());
        return reservation.claims();
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

    // ---- Legacy pending experience migration ----

    /** Migrates old EXP refunds into the idempotent payout outbox atomically. */
    private void migrateLegacyPendingExp(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            List<Map.Entry<UUID, Integer>> legacy = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT uuid, levels FROM coinflip_pending_exp");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    String rawUuid = rows.getString(1);
                    int levels = rows.getInt(2);
                    try {
                        UUID uuid = UUID.fromString(rawUuid);
                        if (levels <= 0) {
                            throw new SQLException("Invalid legacy Coinflip EXP amount for " + rawUuid + ": " + levels);
                        }
                        legacy.add(Map.entry(uuid, levels));
                    } catch (IllegalArgumentException invalidUuid) {
                        throw new SQLException("Invalid UUID in coinflip_pending_exp: " + rawUuid, invalidUuid);
                    }
                }
            }

            long migratedAt = System.currentTimeMillis();
            for (Map.Entry<UUID, Integer> entry : legacy) {
                String key = "legacy-exp:" + entry.getKey();
                boolean present = false;
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT owner_uuid, currency, amount FROM coinflip_pending_payouts WHERE payout_key=?")) {
                    existing.setString(1, key);
                    try (ResultSet row = existing.executeQuery()) {
                        if (row.next()) {
                            present = true;
                            if (!entry.getKey().toString().equals(row.getString(1))
                                    || !CoinflipType.EXP.name().equals(row.getString(2))
                                    || Double.compare(entry.getValue(), row.getDouble(3)) != 0) {
                                throw new SQLException("Conflicting Coinflip legacy payout " + key);
                            }
                        }
                    }
                }
                if (!present) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO coinflip_pending_payouts "
                                    + "(payout_key,owner_uuid,currency,amount,created_at,state,delivery_started_at) "
                                    + "VALUES(?,?,?,?,?,'READY',NULL)")) {
                        insert.setString(1, key);
                        insert.setString(2, entry.getKey().toString());
                        insert.setString(3, CoinflipType.EXP.name());
                        insert.setDouble(4, entry.getValue());
                        insert.setLong(5, migratedAt);
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM coinflip_pending_exp WHERE uuid=?")) {
                    delete.setString(1, entry.getKey().toString());
                    if (delete.executeUpdate() != 1) {
                        throw new SQLException("Legacy Coinflip EXP row changed during migration for " + entry.getKey());
                    }
                }
            }
            connection.commit();
        } catch (SQLException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
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
