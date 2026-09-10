package me.vertex.core.auction;

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
                item MEDIUMBLOB NOT NULL,
                price DOUBLE NOT NULL,
                currency VARCHAR(8) NOT NULL DEFAULT 'MONEY',
                listed_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL
            )""";
    private static final String CREATE_LISTINGS_SQLITE = """
            CREATE TABLE IF NOT EXISTS auction_listings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_uuid CHAR(36) NOT NULL,
                item BLOB NOT NULL,
                price DOUBLE NOT NULL,
                currency VARCHAR(8) NOT NULL DEFAULT 'MONEY',
                listed_at BIGINT NOT NULL,
                expires_at BIGINT NOT NULL
            )""";

    private static final String CREATE_CLAIMS_MYSQL = """
            CREATE TABLE IF NOT EXISTS auction_claims (
                id INT AUTO_INCREMENT PRIMARY KEY,
                owner_uuid CHAR(36) NOT NULL,
                item MEDIUMBLOB NOT NULL,
                created_at BIGINT NOT NULL
            )""";
    private static final String CREATE_CLAIMS_SQLITE = """
            CREATE TABLE IF NOT EXISTS auction_claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid CHAR(36) NOT NULL,
                item BLOB NOT NULL,
                created_at BIGINT NOT NULL
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
            statement.executeUpdate(CREATE_CLAIMS_OWNER_INDEX);
            statement.executeUpdate(createLog);
            statement.executeUpdate(CREATE_LOG_INDEX);
            statement.executeUpdate(createWatchlist);
            statement.executeUpdate(CREATE_WATCHLIST_INDEX);
            statement.executeUpdate(CREATE_PENDING_EXP);
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

    public void deleteListing(int id) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM auction_listings WHERE id = ?")) {
            statement.setInt(1, id);
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
        String sql = "SELECT id, item, created_at FROM auction_claims WHERE owner_uuid = ?";
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
        String sql = "SELECT 1 FROM auction_claims WHERE owner_uuid = ? LIMIT 1";
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

    /**
     * Atomically removes this owner's claims and returns exactly the rows that
     * were deleted.
     *
     * <p>The delete is what authorises handing the items over, rather than a
     * snapshot read beforehand. Reading first and deleting after let a
     * repeated click grant the same snapshot twice before the delete landed,
     * and let a claim created in between be deleted without ever being shown.
     * Deleting by row id means only rows this call actually removed are paid
     * out, and anything queued later survives untouched.
     */
    public List<AuctionClaim> takeClaims(UUID ownerUuid) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<AuctionClaim> claims = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT id, item, created_at FROM auction_claims WHERE owner_uuid = ?")) {
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
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM auction_claims WHERE id = ?")) {
                        for (AuctionClaim claim : claims) {
                            delete.setInt(1, claim.id());
                            delete.addBatch();
                        }
                        delete.executeBatch();
                    }
                }
                connection.commit();
                return claims;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    // ---- Pending experience (offline experience-currency sale proceeds) ----

    private static final String CREATE_PENDING_EXP = """
            CREATE TABLE IF NOT EXISTS auction_pending_exp (
                uuid CHAR(36) NOT NULL PRIMARY KEY,
                levels INT NOT NULL
            )""";

    public Map<UUID, Integer> loadPendingExp() throws SQLException {
        Map<UUID, Integer> pending = new java.util.HashMap<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT uuid, levels FROM auction_pending_exp");
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
            case SQLITE -> "INSERT INTO auction_pending_exp (uuid, levels) VALUES (?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET levels = excluded.levels";
            case MYSQL -> "INSERT INTO auction_pending_exp (uuid, levels) VALUES (?, ?) "
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
             PreparedStatement statement = connection.prepareStatement("DELETE FROM auction_pending_exp WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
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
