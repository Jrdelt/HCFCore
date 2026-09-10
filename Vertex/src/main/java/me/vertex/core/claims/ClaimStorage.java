package me.vertex.core.claims;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable storage for Base Claim anchors/slots and Raid Claim per-chunk
 * expiration deadlines. Mirrors {@code FTopStorage}'s shape: plain
 * dialect-agnostic CREATE TABLE statements, dialect-specific upserts only
 * where an ON CONFLICT/ON DUPLICATE KEY clause is needed.
 */
public final class ClaimStorage {

    private static final String CREATE_BASE_CLAIMS = """
            CREATE TABLE IF NOT EXISTS base_claims (
                faction_id INT NOT NULL,
                slot_index INT NOT NULL,
                anchor_world VARCHAR(64) NOT NULL,
                anchor_x INT NOT NULL,
                anchor_z INT NOT NULL,
                created_at BIGINT NOT NULL,
                PRIMARY KEY (faction_id, slot_index)
            )""";

    /**
     * Every chunk ever admitted into a Base Claim's connected region.
     * Deliberately never pruned when a chunk is later unclaimed -- the
     * spec requires the anchor/relationship to survive an unclaim so a
     * later reclaim of the same chunk doesn't have to re-pass the
     * per-region cap or re-run the adjacency BFS.
     */
    private static final String CREATE_REGION_CHUNKS = """
            CREATE TABLE IF NOT EXISTS base_claim_region_chunks (
                faction_id INT NOT NULL,
                slot_index INT NOT NULL,
                world VARCHAR(64) NOT NULL,
                chunk_x INT NOT NULL,
                chunk_z INT NOT NULL,
                PRIMARY KEY (world, chunk_x, chunk_z)
            )""";

    /** Slot #2/#3 purchases. Slot #1 is always free and never rows here. */
    private static final String CREATE_SLOT_PURCHASES = """
            CREATE TABLE IF NOT EXISTS base_claim_slot_purchases (
                faction_id INT NOT NULL,
                slot_index INT NOT NULL,
                purchased_at BIGINT NOT NULL,
                PRIMARY KEY (faction_id, slot_index)
            )""";

    private static final String CREATE_RAID_CLAIMS = """
            CREATE TABLE IF NOT EXISTS raid_claim_expirations (
                world VARCHAR(64) NOT NULL,
                chunk_x INT NOT NULL,
                chunk_z INT NOT NULL,
                faction_id INT NOT NULL,
                expires_at_millis BIGINT NOT NULL,
                PRIMARY KEY (world, chunk_x, chunk_z)
            )""";

    private final Database database;

    public ClaimStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement base = connection.prepareStatement(CREATE_BASE_CLAIMS);
             PreparedStatement region = connection.prepareStatement(CREATE_REGION_CHUNKS);
             PreparedStatement slots = connection.prepareStatement(CREATE_SLOT_PURCHASES);
             PreparedStatement raid = connection.prepareStatement(CREATE_RAID_CLAIMS)) {
            base.executeUpdate();
            region.executeUpdate();
            slots.executeUpdate();
            raid.executeUpdate();
        }
    }

    // ---- Slot purchases ----

    public java.util.Set<Integer> loadPurchasedSlots(int factionId) throws SQLException {
        java.util.Set<Integer> slots = new java.util.HashSet<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT slot_index FROM base_claim_slot_purchases WHERE faction_id = ?")) {
            statement.setInt(1, factionId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    slots.add(results.getInt(1));
                }
            }
        }
        return slots;
    }

    public java.util.List<int[]> loadAllPurchasedSlots() throws SQLException {
        java.util.List<int[]> rows = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT faction_id, slot_index FROM base_claim_slot_purchases");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new int[] { results.getInt(1), results.getInt(2) });
            }
        }
        return rows;
    }

    public void insertPurchasedSlot(int factionId, int slotIndex, long purchasedAt) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO base_claim_slot_purchases (faction_id, slot_index, purchased_at) VALUES (?, ?, ?)")) {
            statement.setInt(1, factionId);
            statement.setInt(2, slotIndex);
            statement.setLong(3, purchasedAt);
            statement.executeUpdate();
        }
    }

    // ---- Base claim anchors ----

    public record BaseClaimAnchor(int factionId, int slotIndex, String world, int chunkX, int chunkZ, long createdAt) { }

    public List<BaseClaimAnchor> loadBaseClaims() throws SQLException {
        List<BaseClaimAnchor> anchors = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT faction_id, slot_index, anchor_world, anchor_x, anchor_z, created_at FROM base_claims");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                anchors.add(new BaseClaimAnchor(results.getInt(1), results.getInt(2), results.getString(3),
                        results.getInt(4), results.getInt(5), results.getLong(6)));
            }
        }
        return anchors;
    }

    public void insertBaseClaim(int factionId, int slotIndex, String world, int chunkX, int chunkZ, long createdAt)
            throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO base_claims (faction_id, slot_index, anchor_world, anchor_x, anchor_z, created_at) "
                             + "VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setInt(1, factionId);
            statement.setInt(2, slotIndex);
            statement.setString(3, world);
            statement.setInt(4, chunkX);
            statement.setInt(5, chunkZ);
            statement.setLong(6, createdAt);
            statement.executeUpdate();
        }
    }

    public void deleteBaseClaim(int factionId, int slotIndex) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM base_claims WHERE faction_id = ? AND slot_index = ?")) {
            statement.setInt(1, factionId);
            statement.setInt(2, slotIndex);
            statement.executeUpdate();
        }
    }

    // ---- Base claim connected-region membership ----

    public record RegionChunk(int factionId, int slotIndex, String world, int chunkX, int chunkZ) { }

    public List<RegionChunk> loadRegionChunks() throws SQLException {
        List<RegionChunk> rows = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT faction_id, slot_index, world, chunk_x, chunk_z FROM base_claim_region_chunks");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new RegionChunk(results.getInt(1), results.getInt(2), results.getString(3),
                        results.getInt(4), results.getInt(5)));
            }
        }
        return rows;
    }

    public void insertRegionChunk(int factionId, int slotIndex, String world, int chunkX, int chunkZ)
            throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT OR IGNORE INTO base_claim_region_chunks (faction_id, slot_index, world, chunk_x, chunk_z) "
                + "VALUES (?, ?, ?, ?, ?)"
                : "INSERT IGNORE INTO base_claim_region_chunks (faction_id, slot_index, world, chunk_x, chunk_z) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, factionId);
            statement.setInt(2, slotIndex);
            statement.setString(3, world);
            statement.setInt(4, chunkX);
            statement.setInt(5, chunkZ);
            statement.executeUpdate();
        }
    }

    public void deleteRegionChunks(int factionId, int slotIndex) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM base_claim_region_chunks WHERE faction_id = ? AND slot_index = ?")) {
            statement.setInt(1, factionId);
            statement.setInt(2, slotIndex);
            statement.executeUpdate();
        }
    }

    // ---- Raid claim expirations ----

    public record RaidClaimRow(String world, int chunkX, int chunkZ, int factionId, long expiresAtMillis) { }

    public List<RaidClaimRow> loadRaidClaims() throws SQLException {
        List<RaidClaimRow> rows = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT world, chunk_x, chunk_z, faction_id, expires_at_millis FROM raid_claim_expirations");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                rows.add(new RaidClaimRow(results.getString(1), results.getInt(2), results.getInt(3),
                        results.getInt(4), results.getLong(5)));
            }
        }
        return rows;
    }

    public void upsertRaidClaim(String world, int chunkX, int chunkZ, int factionId, long expiresAtMillis)
            throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO raid_claim_expirations (world, chunk_x, chunk_z, faction_id, expires_at_millis) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT(world, chunk_x, chunk_z) DO UPDATE SET "
                + "faction_id = excluded.faction_id, expires_at_millis = excluded.expires_at_millis"
                : "INSERT INTO raid_claim_expirations (world, chunk_x, chunk_z, faction_id, expires_at_millis) "
                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE faction_id = VALUES(faction_id), "
                + "expires_at_millis = VALUES(expires_at_millis)";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            statement.setInt(4, factionId);
            statement.setLong(5, expiresAtMillis);
            statement.executeUpdate();
        }
    }

    public void deleteRaidClaim(String world, int chunkX, int chunkZ) throws SQLException {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM raid_claim_expirations WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
            statement.setString(1, world);
            statement.setInt(2, chunkX);
            statement.setInt(3, chunkZ);
            statement.executeUpdate();
        }
    }
}
