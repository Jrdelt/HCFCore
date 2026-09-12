package me.vertex.core.auction;

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
 * Durable storage for the Auction House: active listings, items waiting
 * to be claimed back, and the permanent staff audit log. Items are
 * serialized via {@link ItemStack#serializeAsBytes}, the same NBT-based
 * approach Backpacks and Coinflip already rely on.
 */
public final class AuctionStorage {

    private static final String CREATE_LISTINGS_MYSQL = """
            CREATE TABLE IF NOT EXISTS auction_listings (
                id INT AUTO_INCREMENT PRIMARY KEY,
                seller_uuid CHAR(36) NOT NULL,
                item LONGBLOB NOT NULL,
                price DOUBLE NOT NULL,
                currency VARCHAR(8) NOT NULL DEFAULT 'MONEY',
                listed_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL
            )""";
    private static final String CREATE_LISTINGS_SQLITE = """
            CREATE TABLE IF NOT EXISTS auction_listings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_uuid CHAR(36) NOT NULL,
                item LONGBLOB NOT NULL,
                price DOUBLE NOT NULL,
                currency VARCHAR(8) NOT NULL DEFAULT 'MONEY',
                listed_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL
            )""";

    private static final String CREATE_CLAIMS_MYSQL = """
            CREATE TABLE IF NOT EXISTS auction_claims (
                id INT AUTO_INCREMENT PRIMARY KEY,
                owner_uuid CHAR(36) NOT NULL,
                item LONGBLOB NOT NULL,
                created_at BIGINT NOT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'READY',
                reservation_token CHAR(36) NULL,
                reserved_at BIGINT NULL
            )""";
    private static final String CREATE_CLAIMS_SQLITE = """
            CREATE TABLE IF NOT EXISTS auction_claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid CHAR(36) NOT NULL,
                item LONGBLOB NOT NULL,
                created_at BIGINT NOT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'READY',
                reservation_token CHAR(36) NULL,
                reserved_at BIGINT NULL
            )""";

    private static final String CREATE_LOG_MYSQL = """
            CREATE TABLE IF NOT EXISTS auction_log (
                id INT AUTO_INCREMENT PRIMARY KEY,
                seller_uuid CHAR(36) NOT NULL,
                buyer_uuid CHAR(36) NULL,
                item_summary VARCHAR(256) NOT NULL,
                price DOUBLE NOT NULL,
                listed_at BIGINT NOT NULL,
                resolved_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL,
                cancelled_by CHAR(36) NULL
            )""";
    private static final String CREATE_LOG_SQLITE = """
            CREATE TABLE IF NOT EXISTS auction_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_uuid CHAR(36) NOT NULL,
                buyer_uuid CHAR(36) NULL,
                item_summary VARCHAR(256) NOT NULL,
                price DOUBLE NOT NULL,
                listed_at BIGINT NOT NULL,
                resolved_at BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL,
                cancelled_by CHAR(36) NULL
            )""";
    private static final String CREATE_LOG_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_auction_log_resolved_at ON auction_log (resolved_at DESC)";
    private static final String CREATE_CLAIMS_OWNER_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_auction_claims_owner ON auction_claims (owner_uuid)";

    private static final String CREATE_WATCHLIST_MYSQL = """
            CREATE TABLE IF NOT EXISTS auction_watchlist (
                id INT AUTO_INCREMENT PRIMARY KEY,
                owner_uuid CHAR(36) NOT NULL,
                listing_id INT NOT NULL
            )""";
    private static final String CREATE_WATCHLIST_SQLITE = """
            CREATE TABLE IF NOT EXISTS auction_watchlist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid CHAR(36) NOT NULL,
                listing_id INTEGER NOT NULL
            )""";
    private static final String CREATE_WATCHLIST_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_auction_watchlist_owner ON auction_watchlist (owner_uuid)";
    private static final String CREATE_PAYOUTS = """
            CREATE TABLE IF NOT EXISTS auction_pending_payouts (
                payout_key VARCHAR(64) NOT NULL PRIMARY KEY,
                owner_uuid CHAR(36) NOT NULL,
                currency VARCHAR(8) NOT NULL,
                amount DOUBLE NOT NULL,
                created_at BIGINT NOT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'READY',
                delivery_started_at BIGINT NULL
            )""";
    private static final String CREATE_CREATION_INTENTS = """
            CREATE TABLE IF NOT EXISTS auction_creation_intents (
                intent_key CHAR(36) NOT NULL PRIMARY KEY,
                seller_uuid CHAR(36) NOT NULL,
                item LONGBLOB NOT NULL,
                price DOUBLE NOT NULL,
                currency VARCHAR(8) NOT NULL,
                listed_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL,
                state VARCHAR(16) NOT NULL DEFAULT 'PREPARED'
            )""";
    private static final String CREATE_CREATION_INTENT_AUDIT = """
            CREATE TABLE IF NOT EXISTS auction_creation_intent_audit (
                intent_key CHAR(36) NOT NULL PRIMARY KEY,
                seller_uuid CHAR(36) NOT NULL,
                item_summary VARCHAR(256) NOT NULL,
                price DOUBLE NOT NULL,
                currency VARCHAR(8) NOT NULL,
                decision VARCHAR(16) NOT NULL,
                actor_uuid CHAR(36) NOT NULL,
                actor_name VARCHAR(64) NOT NULL,
                resolved_at BIGINT NOT NULL
            )""";

    private final Database database;
    private final String createListings;
    private final String createClaims;
    private final String createLog;
    private final String createWatchlist;

    public AuctionStorage(Database database) {
        this.database = database;
        boolean sqlite = database.dialect() == Database.Dialect.SQLITE;
        this.createListings = sqlite ? CREATE_LISTINGS_SQLITE : CREATE_LISTINGS_MYSQL;
        this.createClaims = sqlite ? CREATE_CLAIMS_SQLITE : CREATE_CLAIMS_MYSQL;
        this.createLog = sqlite ? CREATE_LOG_SQLITE : CREATE_LOG_MYSQL;
        this.createWatchlist = sqlite ? CREATE_WATCHLIST_SQLITE : CREATE_WATCHLIST_MYSQL;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(createListings);
            statement.executeUpdate(createClaims);
            SqlSchema.ensureColumn(connection, "auction_claims", "state",
                    "VARCHAR(16) NOT NULL DEFAULT 'READY'");
            SqlSchema.ensureColumn(connection, "auction_claims", "reservation_token", "CHAR(36) NULL");
            SqlSchema.ensureColumn(connection, "auction_claims", "reserved_at", "BIGINT NULL");
            SqlSchema.ensureIndex(connection, "auction_claims", "idx_auction_claims_owner", false,
                    "owner_uuid");
            statement.executeUpdate(createLog);
            SqlSchema.ensureIndex(connection, "auction_log", "idx_auction_log_resolved_at", false,
                    "resolved_at DESC");
            statement.executeUpdate(createWatchlist);
            SqlSchema.ensureIndex(connection, "auction_watchlist", "idx_auction_watchlist_owner", false,
                    "owner_uuid");
            statement.executeUpdate(CREATE_PENDING_EXP);
            statement.executeUpdate(CREATE_PAYOUTS);
            SqlSchema.ensureColumn(connection, "auction_pending_payouts", "state",
                    "VARCHAR(16) NOT NULL DEFAULT 'READY'");
            SqlSchema.ensureColumn(connection, "auction_pending_payouts", "delivery_started_at", "BIGINT NULL");
            statement.executeUpdate(CREATE_CREATION_INTENTS);
            statement.executeUpdate(CREATE_CREATION_INTENT_AUDIT);
            SqlSchema.ensureColumn(connection,"auction_creation_intents","state",
                    "VARCHAR(16) NOT NULL DEFAULT 'ESCROWED'");
            SqlSchema.ensureLongBlob(connection, database.dialect(), "auction_listings", "item", false);
            SqlSchema.ensureLongBlob(connection, database.dialect(), "auction_claims", "item", false);
            SqlSchema.ensureLongBlob(connection, database.dialect(), "auction_creation_intents", "item", false);
            migrateLegacyPendingExp(connection);
        }
    }

    // ---- Listings ----

    public List<AuctionListing> loadAllListings() throws SQLException {
        List<AuctionListing> listings = new ArrayList<>();
        String sql = "SELECT id, seller_uuid, item, price, currency, listed_at, expires_at FROM auction_listings";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                listings.add(new AuctionListing(
                        results.getInt("id"),
                        UUID.fromString(results.getString("seller_uuid")),
                        ItemStack.deserializeBytes(results.getBytes("item")),
                        results.getDouble("price"),
                        AuctionCurrency.valueOf(results.getString("currency")),
                        results.getLong("listed_at"),
                        results.getLong("expires_at")));
            }
        }
        return listings;
    }

    /** @return the generated id for the new row. */
    public int insertListing(UUID sellerUuid, ItemStack item, double price, AuctionCurrency currency,
            long listedAt, long expiresAt) throws SQLException {
        String sql = "INSERT INTO auction_listings (seller_uuid, item, price, currency, listed_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, sellerUuid.toString());
            statement.setBytes(2, item.serializeAsBytes());
            statement.setDouble(3, price);
            statement.setString(4, currency.name());
            statement.setLong(5, listedAt);
            statement.setLong(6, expiresAt);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    public record CreationIntent(String key, UUID sellerUuid, ItemStack item, double price,
            AuctionCurrency currency, long listedAt, long expiresAt,String state) { }

    public CreationIntent insertCreationIntent(UUID sellerUuid, ItemStack item, double price,
            AuctionCurrency currency, long listedAt, long expiresAt) throws SQLException {
        return insertCreationIntent(sellerUuid, item, price, currency, listedAt, expiresAt, "PREPARED");
    }

    public CreationIntent insertCreationIntent(UUID sellerUuid, ItemStack item, double price,
            AuctionCurrency currency, long listedAt, long expiresAt, String state) throws SQLException {
        CreationIntent intent = new CreationIntent(UUID.randomUUID().toString(), sellerUuid, item.clone(), price,
                currency, listedAt, expiresAt, state);
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO auction_creation_intents (intent_key, seller_uuid, item, price, currency, listed_at, "
                        + "expires_at,state) VALUES (?, ?, ?, ?, ?, ?, ?,?)")) {
            statement.setString(1, intent.key());
            statement.setString(2, sellerUuid.toString());
            statement.setBytes(3, item.serializeAsBytes());
            statement.setDouble(4, price);
            statement.setString(5, currency.name());
            statement.setLong(6, listedAt);
            statement.setLong(7, expiresAt);
            statement.setString(8, state);
            statement.executeUpdate();
        }
        return intent;
    }

    public List<CreationIntent> loadCreationIntents() throws SQLException {
        List<CreationIntent> intents = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT intent_key, seller_uuid, item, price, currency, listed_at, expires_at,state "
                        + "FROM auction_creation_intents ORDER BY listed_at"); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) intents.add(readCreationIntent(rows));
        }
        return intents;
    }

    public Optional<CreationIntent> loadCreationIntent(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT intent_key,seller_uuid,item,price,currency,listed_at,expires_at,state "
                        + "FROM auction_creation_intents WHERE intent_key=?")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readCreationIntent(row)) : Optional.empty();
            }
        }
    }

    private static CreationIntent readCreationIntent(ResultSet row) throws SQLException {
        return new CreationIntent(row.getString(1), UUID.fromString(row.getString(2)),
                ItemStack.deserializeBytes(row.getBytes(3)), row.getDouble(4),
                AuctionCurrency.valueOf(row.getString(5)), row.getLong(6), row.getLong(7), row.getString(8));
    }

    public boolean setCreationIntentState(String key,String expected,String next)throws SQLException{
        try(Connection connection=database.getConnection();PreparedStatement update=connection.prepareStatement(
                "UPDATE auction_creation_intents SET state=? WHERE intent_key=? AND state=?")){
            update.setString(1,next);update.setString(2,key);update.setString(3,expected);return update.executeUpdate()==1;
        }
    }

    public boolean deleteCreationIntent(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM auction_creation_intents WHERE intent_key = ?")) {
            statement.setString(1, key);
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Moves an item whose listing could not be created into the seller's
     * durable collection box in the same transaction that removes the intent.
     */
    public boolean refundCreationIntent(String key, long createdAt) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO auction_claims (owner_uuid,item,created_at,state,reservation_token,reserved_at) "
                                + "SELECT seller_uuid,item,?,'READY',NULL,NULL FROM auction_creation_intents "
                                + "WHERE intent_key=?")) {
                    insert.setLong(1, createdAt);
                    insert.setString(2, key);
                    if (insert.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM auction_creation_intents WHERE intent_key=?")) {
                    delete.setString(1, key);
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Atomically promotes durable escrow into a normal active listing. */
    public int activateCreationIntent(CreationIntent intent) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int id;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO auction_listings (seller_uuid, item, price, currency, listed_at, expires_at) "
                                + "SELECT seller_uuid, item, price, currency, listed_at, expires_at "
                                + "FROM auction_creation_intents WHERE intent_key = ? AND state='ESCROWED'",
                        Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, intent.key());
                    if (insert.executeUpdate() != 1) {
                        connection.rollback();
                        return -1;
                    }
                    try (ResultSet keys = insert.getGeneratedKeys()) { id = keys.next() ? keys.getInt(1) : -1; }
                }
                if (id < 0) {
                    connection.rollback();
                    return -1;
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM auction_creation_intents WHERE intent_key = ?")) {
                    delete.setString(1, intent.key());
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return -1;
                    }
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
        ACTIVATED, REFUNDED, DISCARDED, ALREADY_RESOLVED, CONFLICT, INVALID_STATE, MISSING, STORAGE_ERROR
    }

    public enum IntentResolutionDecision {
        DEBITED, NOT_DEBITED, ITEM_NOT_REMOVED
    }

    public record IntentResolution(IntentResolutionStatus status, CreationIntent intent, int listingId,
            String priorDecision) { }

    public record IntentAudit(String key, UUID sellerUuid, String itemSummary, double price,
            AuctionCurrency currency, String decision, String actorUuid, String actorName, long resolvedAt) { }

    /**
     * Resolves one uncertain external debit and writes the permanent audit row
     * in the same transaction that activates or refunds the escrowed item.
     */
    public IntentResolution resolveCreationIntent(String key, boolean debited, UUID actorUuid,
            String actorName, long resolvedAt) throws SQLException {
        return resolveCreationIntent(key, debited ? IntentResolutionDecision.DEBITED
                : IntentResolutionDecision.NOT_DEBITED, actorUuid, actorName, resolvedAt);
    }

    public IntentResolution resolveCreationIntent(String key, IntentResolutionDecision resolutionDecision,
            UUID actorUuid, String actorName, long resolvedAt) throws SQLException {
        String decision = resolutionDecision.name();
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
                if (!"DEBITING".equals(intent.state())) {
                    connection.rollback();
                    return new IntentResolution(IntentResolutionStatus.INVALID_STATE, intent, -1, null);
                }

                int listingId = -1;
                if (resolutionDecision == IntentResolutionDecision.DEBITED) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO auction_listings(seller_uuid,item,price,currency,listed_at,expires_at) "
                                    + "VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                        insert.setString(1, intent.sellerUuid().toString());
                        insert.setBytes(2, intent.item().serializeAsBytes());
                        insert.setDouble(3, intent.price());
                        insert.setString(4, intent.currency().name());
                        insert.setLong(5, intent.listedAt());
                        insert.setLong(6, intent.expiresAt());
                        if (insert.executeUpdate() != 1) throw new SQLException("Could not activate intent " + key);
                        try (ResultSet generated = insert.getGeneratedKeys()) {
                            if (!generated.next()) throw new SQLException("No listing id for intent " + key);
                            listingId = generated.getInt(1);
                        }
                    }
                } else if (resolutionDecision == IntentResolutionDecision.NOT_DEBITED) {
                    try (PreparedStatement claim = connection.prepareStatement(
                            "INSERT INTO auction_claims(owner_uuid,item,created_at,state,reservation_token,reserved_at) "
                                    + "VALUES(?,?,?,'READY',NULL,NULL)")) {
                        claim.setString(1, intent.sellerUuid().toString());
                        claim.setBytes(2, intent.item().serializeAsBytes());
                        claim.setLong(3, resolvedAt);
                        claim.executeUpdate();
                    }
                }
                insertIntentAudit(connection, intent, decision, actorUuid, actorName, resolvedAt);
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM auction_creation_intents WHERE intent_key=? AND state='DEBITING'")) {
                    delete.setString(1, key);
                    if (delete.executeUpdate() != 1) throw new SQLException("Intent changed during resolution " + key);
                }
                connection.commit();
                IntentResolutionStatus status = switch (resolutionDecision) {
                    case DEBITED -> IntentResolutionStatus.ACTIVATED;
                    case NOT_DEBITED -> IntentResolutionStatus.REFUNDED;
                    case ITEM_NOT_REMOVED -> IntentResolutionStatus.DISCARDED;
                };
                return new IntentResolution(status, intent, listingId, null);
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
                "SELECT intent_key,seller_uuid,item,price,currency,listed_at,expires_at,state "
                        + "FROM auction_creation_intents WHERE intent_key=?" + suffix)) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? readCreationIntent(row) : null;
            }
        }
    }

    private static IntentAudit loadIntentAudit(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT intent_key,seller_uuid,item_summary,price,currency,decision,actor_uuid,actor_name,resolved_at "
                        + "FROM auction_creation_intent_audit WHERE intent_key=?")) {
            statement.setString(1, key);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new IntentAudit(row.getString(1), UUID.fromString(row.getString(2)),
                        row.getString(3), row.getDouble(4), AuctionCurrency.valueOf(row.getString(5)),
                        row.getString(6), row.getString(7), row.getString(8), row.getLong(9)) : null;
            }
        }
    }

    private static void insertIntentAudit(Connection connection, CreationIntent intent, String decision,
            UUID actorUuid, String actorName, long resolvedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO auction_creation_intent_audit(intent_key,seller_uuid,item_summary,price,currency,"
                        + "decision,actor_uuid,actor_name,resolved_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, intent.key());
            statement.setString(2, intent.sellerUuid().toString());
            statement.setString(3, intent.item().getAmount() + "x " + intent.item().getType().name());
            statement.setDouble(4, intent.price());
            statement.setString(5, intent.currency().name());
            statement.setString(6, decision);
            statement.setString(7, actorUuid.toString());
            statement.setString(8, actorName == null || actorName.isBlank() ? actorUuid.toString() : actorName);
            statement.setLong(9, resolvedAt);
            statement.executeUpdate();
        }
    }

    /**
     * Removes a listing and records how it ended, in one transaction.
     *
     * <p>This is the durable settlement record. It is committed *before* money
     * or items move, so a crash can never leave a delivered item behind a
     * listing that still exists in the database and comes back on restart.
     * Doing the delete and the log separately allowed exactly that: the
     * listing row could survive while the item had already been handed over.
     *
     * @return false when the listing was already settled by someone else, so
     *         the caller knows not to deliver anything
     */
    public boolean settleListing(int id, UUID sellerUuid, UUID buyerUuid, String itemSummary, double price,
            long listedAt, long resolvedAt, AuctionLogEntry.Status status, UUID cancelledByUuid) throws SQLException {
        return settleListing(id, sellerUuid, buyerUuid, itemSummary, price, listedAt, resolvedAt, status,
                cancelledByUuid, null, null);
    }

    /** Atomically settles a purchase and creates both sides' durable delivery records. */
    public boolean settleSale(AuctionListing listing, UUID buyerUuid, double sellerProceeds, long resolvedAt)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM auction_listings WHERE id = ?")) {
                    delete.setInt(1, listing.id());
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                if (listing.currency() == AuctionCurrency.GC) {
                    me.vertex.core.gc.GcStorage.debitForSettlement(connection, buyerUuid,
                            me.vertex.core.gc.GcAction.AUCTION_PURCHASE, (long) Math.ceil(listing.price()),
                            "auction:purchase:" + listing.id(), resolvedAt);
                }
                try (PreparedStatement log = connection.prepareStatement("""
                        INSERT INTO auction_log (seller_uuid, buyer_uuid, item_summary, price, listed_at,
                            resolved_at, status, cancelled_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    log.setString(1, listing.sellerUuid().toString());
                    log.setString(2, buyerUuid.toString());
                    log.setString(3, listing.item().getAmount() + "x " + listing.item().getType());
                    log.setDouble(4, listing.price());
                    log.setLong(5, listing.listedAtMillis());
                    log.setLong(6, resolvedAt);
                    log.setString(7, AuctionLogEntry.Status.SOLD.name());
                    log.setNull(8, Types.CHAR);
                    log.executeUpdate();
                }
                try (PreparedStatement claim = connection.prepareStatement(
                        "INSERT INTO auction_claims (owner_uuid, item, created_at) VALUES (?, ?, ?)")) {
                    claim.setString(1, buyerUuid.toString());
                    claim.setBytes(2, listing.item().serializeAsBytes());
                    claim.setLong(3, resolvedAt);
                    claim.executeUpdate();
                }
                try (PreparedStatement payout = connection.prepareStatement(
                        "INSERT INTO auction_pending_payouts (payout_key, owner_uuid, currency, amount, created_at) "
                                + "VALUES (?, ?, ?, ?, ?)")) {
                    payout.setString(1, "sale:" + listing.id());
                    payout.setString(2, listing.sellerUuid().toString());
                    payout.setString(3, listing.currency().name());
                    payout.setDouble(4, sellerProceeds);
                    payout.setLong(5, resolvedAt);
                    payout.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public record PendingPayout(String key, UUID ownerUuid, AuctionCurrency currency, double amount, String state,
            Long deliveryStartedAt) { }

    public List<PendingPayout> loadPendingPayouts() throws SQLException {
        List<PendingPayout> result = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT payout_key, owner_uuid, currency, amount, state, delivery_started_at "
                        + "FROM auction_pending_payouts WHERE state = 'READY'");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new PendingPayout(rows.getString(1), UUID.fromString(rows.getString(2)),
                    AuctionCurrency.valueOf(rows.getString(3)), rows.getDouble(4), rows.getString(5),
                    nullableLong(rows, 6)));
        }
        return result;
    }

    public List<PendingPayout> loadUncertainPayouts() throws SQLException {
        List<PendingPayout> result = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT payout_key, owner_uuid, currency, amount, state, delivery_started_at "
                        + "FROM auction_pending_payouts WHERE state = 'DELIVERING' ORDER BY created_at");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new PendingPayout(rows.getString(1), UUID.fromString(rows.getString(2)),
                    AuctionCurrency.valueOf(rows.getString(3)), rows.getDouble(4), rows.getString(5),
                    nullableLong(rows, 6)));
        }
        return result;
    }

    public boolean reservePendingPayout(String key, long now) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE auction_pending_payouts SET state = 'DELIVERING', delivery_started_at = ? "
                        + "WHERE payout_key = ? AND state = 'READY'")) {
            statement.setLong(1, now);
            statement.setString(2, key);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean releasePendingPayout(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE auction_pending_payouts SET state = 'READY', delivery_started_at = NULL "
                        + "WHERE payout_key = ? AND state = 'DELIVERING'")) {
            statement.setString(1, key);
            return statement.executeUpdate() == 1;
        }
    }

    public boolean acknowledgeUncertainPayout(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM auction_pending_payouts WHERE payout_key = ? AND state = 'DELIVERING'")) {
            statement.setString(1, key);
            return statement.executeUpdate() == 1;
        }
    }

    public void deletePendingPayout(String key) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM auction_pending_payouts WHERE payout_key = ?")) {
            statement.setString(1, key);
            statement.executeUpdate();
        }
    }

    private static Long nullableLong(ResultSet rows, int index) throws SQLException {
        long value = rows.getLong(index);
        return rows.wasNull() ? null : value;
    }

    /**
     * Settles a listing and, when it is being returned, creates its collection
     * claim in the exact same transaction. A resolved listing must never be
     * able to outlive the only durable copy of its returned item.
     */
    public boolean settleListing(int id, UUID sellerUuid, UUID buyerUuid, String itemSummary, double price,
            long listedAt, long resolvedAt, AuctionLogEntry.Status status, UUID cancelledByUuid,
            UUID returnClaimOwner, ItemStack returnClaimItem) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int deleted;
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM auction_listings WHERE id = ?")) {
                    delete.setInt(1, id);
                    deleted = delete.executeUpdate();
                }
                if (deleted == 0) {
                    // Someone else settled it first. Roll back rather than
                    // logging a sale that did not happen.
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement log = connection.prepareStatement("""
                        INSERT INTO auction_log (seller_uuid, buyer_uuid, item_summary, price, listed_at,
                            resolved_at, status, cancelled_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    log.setString(1, sellerUuid.toString());
                    setNullableUuid(log, 2, buyerUuid);
                    log.setString(3, itemSummary);
                    log.setDouble(4, price);
                    log.setLong(5, listedAt);
                    log.setLong(6, resolvedAt);
                    log.setString(7, status.name());
                    setNullableUuid(log, 8, cancelledByUuid);
                    log.executeUpdate();
                }
                if (returnClaimOwner != null && returnClaimItem != null && !returnClaimItem.isEmpty()) {
                    try (PreparedStatement claim = connection.prepareStatement(
                            "INSERT INTO auction_claims (owner_uuid, item, created_at) VALUES (?, ?, ?)")) {
                        claim.setString(1, returnClaimOwner.toString());
                        claim.setBytes(2, returnClaimItem.serializeAsBytes());
                        claim.setLong(3, resolvedAt);
                        claim.executeUpdate();
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    // ---- Claims ----

    public List<AuctionClaim> loadClaims(UUID ownerUuid) throws SQLException {
        List<AuctionClaim> claims = new ArrayList<>();
        String sql = "SELECT id, item, created_at FROM auction_claims WHERE owner_uuid = ? AND state = 'READY'";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    claims.add(new AuctionClaim(
                            results.getInt("id"),
                            ownerUuid,
                            ItemStack.deserializeBytes(results.getBytes("item")),
                            results.getLong("created_at")));
                }
            }
        }
        return claims;
    }

    public boolean hasClaims(UUID ownerUuid) throws SQLException {
        String sql = "SELECT 1 FROM auction_claims WHERE owner_uuid = ? AND state = 'READY' LIMIT 1";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    public void insertClaim(UUID ownerUuid, ItemStack item, long createdAt) throws SQLException {
        String sql = "INSERT INTO auction_claims (owner_uuid, item, created_at) VALUES (?, ?, ?)";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid.toString());
            statement.setBytes(2, item.serializeAsBytes());
            statement.setLong(3, createdAt);
            statement.executeUpdate();
        }
    }

    public record ClaimReservation(String token, UUID ownerUuid, List<AuctionClaim> claims) { }

    /** Reserves READY rows without deleting the database's authoritative copy. */
    public ClaimReservation reserveClaims(UUID ownerUuid) throws SQLException {
        String token = UUID.randomUUID().toString();
        try (Connection connection = database.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<AuctionClaim> claims = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id, item, created_at FROM auction_claims "
                                + "WHERE owner_uuid = ? AND state = 'READY'")) {
                    select.setString(1, ownerUuid.toString());
                    try (ResultSet results = select.executeQuery()) {
                        while (results.next()) {
                            claims.add(new AuctionClaim(results.getInt("id"), ownerUuid,
                                    ItemStack.deserializeBytes(results.getBytes("item")),
                                    results.getLong("created_at")));
                        }
                    }
                }
                if (!claims.isEmpty()) {
                    try (PreparedStatement reserve = connection.prepareStatement(
                            "UPDATE auction_claims SET state = 'DELIVERING', reservation_token = ?, reserved_at = ? "
                                    + "WHERE id = ? AND owner_uuid = ? AND state = 'READY'")) {
                        for (AuctionClaim claim : claims) {
                            reserve.setString(1, token);
                            reserve.setLong(2, System.currentTimeMillis());
                            reserve.setInt(3, claim.id());
                            reserve.setString(4, ownerUuid.toString());
                            reserve.addBatch();
                        }
                        int[] changed = reserve.executeBatch();
                        for (int result : changed) {
                            if (result == 0) {
                                connection.rollback();
                                return new ClaimReservation(token, ownerUuid, List.of());
                            }
                        }
                    }
                }
                connection.commit();
                return new ClaimReservation(token, ownerUuid, List.copyOf(claims));
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public List<ClaimReservation> loadDeliveringClaims(UUID ownerUuid) throws SQLException {
        Map<String, List<AuctionClaim>> grouped = new java.util.LinkedHashMap<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, item, created_at, reservation_token FROM auction_claims "
                        + "WHERE owner_uuid = ? AND state = 'DELIVERING' ORDER BY id")) {
            statement.setString(1, ownerUuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    String token = results.getString("reservation_token");
                    if (token == null || token.isBlank()) continue;
                    grouped.computeIfAbsent(token, ignored -> new ArrayList<>()).add(new AuctionClaim(
                            results.getInt("id"), ownerUuid,
                            ItemStack.deserializeBytes(results.getBytes("item")), results.getLong("created_at")));
                }
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new ClaimReservation(entry.getKey(), ownerUuid, List.copyOf(entry.getValue())))
                .toList();
    }

    /** Acknowledges only rows owned by this exact delivery reservation. */
    public int completeReservation(UUID ownerUuid, String token) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM auction_claims WHERE owner_uuid = ? AND state = 'DELIVERING' "
                        + "AND reservation_token = ?")) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, token);
            return statement.executeUpdate();
        }
    }

    /** Releases the original rows intact; no replacement claim is inserted. */
    public int releaseReservation(UUID ownerUuid, String token) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE auction_claims SET state = 'READY', reservation_token = NULL, reserved_at = NULL "
                        + "WHERE owner_uuid = ? AND state = 'DELIVERING' AND reservation_token = ?")) {
            statement.setString(1, ownerUuid.toString());
            statement.setString(2, token);
            return statement.executeUpdate();
        }
    }

    /** Compatibility helper for storage callers that do not hand items to Bukkit. */
    public List<AuctionClaim> takeClaims(UUID ownerUuid) throws SQLException {
        ClaimReservation reservation = reserveClaims(ownerUuid);
        if (!reservation.claims().isEmpty()) completeReservation(ownerUuid, reservation.token());
        return reservation.claims();
    }

    // ---- Legacy pending experience migration ----

    private static final String CREATE_PENDING_EXP = """
            CREATE TABLE IF NOT EXISTS auction_pending_exp (
                uuid CHAR(36) NOT NULL PRIMARY KEY,
                levels INT NOT NULL
            )""";

    /**
     * Moves pre-outbox EXP credits into the restart-safe payout table exactly
     * once. The deterministic key makes the migration idempotent, while the
     * insert/check and legacy delete share one transaction.
     */
    private void migrateLegacyPendingExp(Connection connection) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            List<Map.Entry<UUID, Integer>> legacy = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT uuid, levels FROM auction_pending_exp");
                 ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    String rawUuid = rows.getString(1);
                    int levels = rows.getInt(2);
                    try {
                        UUID uuid = UUID.fromString(rawUuid);
                        if (levels <= 0) {
                            throw new SQLException("Invalid legacy Auction EXP amount for " + rawUuid + ": " + levels);
                        }
                        legacy.add(Map.entry(uuid, levels));
                    } catch (IllegalArgumentException invalidUuid) {
                        throw new SQLException("Invalid UUID in auction_pending_exp: " + rawUuid, invalidUuid);
                    }
                }
            }

            long migratedAt = System.currentTimeMillis();
            for (Map.Entry<UUID, Integer> entry : legacy) {
                String key = "legacy-exp:" + entry.getKey();
                boolean present = false;
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT owner_uuid, currency, amount FROM auction_pending_payouts WHERE payout_key=?")) {
                    existing.setString(1, key);
                    try (ResultSet row = existing.executeQuery()) {
                        if (row.next()) {
                            present = true;
                            if (!entry.getKey().toString().equals(row.getString(1))
                                    || !AuctionCurrency.EXP.name().equals(row.getString(2))
                                    || Double.compare(entry.getValue(), row.getDouble(3)) != 0) {
                                throw new SQLException("Conflicting Auction legacy payout " + key);
                            }
                        }
                    }
                }
                if (!present) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO auction_pending_payouts "
                                    + "(payout_key,owner_uuid,currency,amount,created_at,state,delivery_started_at) "
                                    + "VALUES(?,?,?,?,?,'READY',NULL)")) {
                        insert.setString(1, key);
                        insert.setString(2, entry.getKey().toString());
                        insert.setString(3, AuctionCurrency.EXP.name());
                        insert.setDouble(4, entry.getValue());
                        insert.setLong(5, migratedAt);
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM auction_pending_exp WHERE uuid=?")) {
                    delete.setString(1, entry.getKey().toString());
                    if (delete.executeUpdate() != 1) {
                        throw new SQLException("Legacy Auction EXP row changed during migration for " + entry.getKey());
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

    // ---- Watchlist ----

    /** Every watch, across every owner, loaded once at startup. */
    public Map<UUID, List<Integer>> loadAllWatches() throws SQLException {
        Map<UUID, List<Integer>> watches = new java.util.HashMap<>();
        String sql = "SELECT owner_uuid, listing_id FROM auction_watchlist";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                try {
                    UUID owner = UUID.fromString(results.getString("owner_uuid"));
                    watches.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(results.getInt("listing_id"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return watches;
    }

    public List<Integer> loadWatchlist(UUID ownerUuid) throws SQLException {
        List<Integer> listingIds = new ArrayList<>();
        String sql = "SELECT listing_id FROM auction_watchlist WHERE owner_uuid = ?";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid.toString());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    listingIds.add(results.getInt("listing_id"));
                }
            }
        }
        return listingIds;
    }

    public void insertWatch(UUID ownerUuid, int listingId) throws SQLException {
        String sql = "INSERT INTO auction_watchlist (owner_uuid, listing_id) VALUES (?, ?)";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid.toString());
            statement.setInt(2, listingId);
            statement.executeUpdate();
        }
    }

    public void deleteWatch(UUID ownerUuid, int listingId) throws SQLException {
        String sql = "DELETE FROM auction_watchlist WHERE owner_uuid = ? AND listing_id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUuid.toString());
            statement.setInt(2, listingId);
            statement.executeUpdate();
        }
    }

    /** Called when a listing resolves (sold/expired/cancelled) so it doesn't linger on anyone's watchlist. */
    public void deleteWatchesForListing(int listingId) throws SQLException {
        String sql = "DELETE FROM auction_watchlist WHERE listing_id = ?";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, listingId);
            statement.executeUpdate();
        }
    }

    // ---- Audit log ----

    public void insertLogEntry(UUID sellerUuid, UUID buyerUuid, String itemSummary, double price, long listedAt,
            long resolvedAt, AuctionLogEntry.Status status, UUID cancelledByUuid) throws SQLException {
        String sql = """
                INSERT INTO auction_log (seller_uuid, buyer_uuid, item_summary, price, listed_at, resolved_at, status, cancelled_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sellerUuid.toString());
            setNullableUuid(statement, 2, buyerUuid);
            statement.setString(3, itemSummary);
            statement.setDouble(4, price);
            statement.setLong(5, listedAt);
            statement.setLong(6, resolvedAt);
            statement.setString(7, status.name());
            setNullableUuid(statement, 8, cancelledByUuid);
            statement.executeUpdate();
        }
    }

    public List<AuctionLogEntry> loadLog(UUID playerUuid, int limit, int offset) throws SQLException {
        List<AuctionLogEntry> entries = new ArrayList<>();
        String sql = playerUuid == null
                ? "SELECT * FROM auction_log ORDER BY resolved_at DESC LIMIT ? OFFSET ?"
                : "SELECT * FROM auction_log WHERE seller_uuid = ? OR buyer_uuid = ? ORDER BY resolved_at DESC LIMIT ? OFFSET ?";
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
             PreparedStatement statement = connection.prepareStatement("DELETE FROM auction_log WHERE resolved_at < ?")) {
            statement.setLong(1, cutoffMillis);
            statement.executeUpdate();
        }
    }

    private AuctionLogEntry readLogEntry(ResultSet results) throws SQLException {
        return new AuctionLogEntry(
                results.getInt("id"),
                UUID.fromString(results.getString("seller_uuid")),
                nullableUuid(results.getString("buyer_uuid")),
                results.getString("item_summary"),
                results.getDouble("price"),
                results.getLong("listed_at"),
                results.getLong("resolved_at"),
                AuctionLogEntry.Status.valueOf(results.getString("status")),
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
