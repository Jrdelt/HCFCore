package me.vertex.core.claims;

import me.vertex.core.factions.FactionRole;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.UUID;

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
                anchor_world VARCHAR(255) NOT NULL,
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
                world VARCHAR(255) NOT NULL,
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
                world VARCHAR(255) NOT NULL,
                chunk_x INT NOT NULL,
                chunk_z INT NOT NULL,
                faction_id INT NOT NULL,
                expires_at_millis BIGINT NOT NULL,
                PRIMARY KEY (world, chunk_x, chunk_z)
            )""";

    private final Database database;
    /**
     * Null until Shield has loaded. Once configured, Base removal evaluates
     * durable Shield rows while holding the same faction lock used by Shield
     * mutations, closing the cross-shard check/commit race.
     */
    private volatile ZoneId shieldScheduleZone;

    public ClaimStorage(Database database) {
        this.database = database;
    }

    public void enableDurableShieldChecks(ZoneId scheduleZone) {
        this.shieldScheduleZone = scheduleZone == null ? ZoneId.systemDefault() : scheduleZone;
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
            SqlSchema.ensureVarcharSize(connection, database.dialect(),
                    "base_claims", "anchor_world", 255, false);
            SqlSchema.ensureVarcharSize(connection, database.dialect(),
                    "base_claim_region_chunks", "world", 255, false);
            SqlSchema.ensureVarcharSize(connection, database.dialect(),
                    "raid_claim_expirations", "world", 255, false);
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

    /** Creates an anchor and its mandatory first region member as one durable operation. */
    public void insertBaseClaimWithAnchorChunk(int factionId, int slotIndex, String world, int chunkX, int chunkZ,
            long createdAt) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement anchor = connection.prepareStatement(
                    "INSERT INTO base_claims (faction_id, slot_index, anchor_world, anchor_x, anchor_z, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)");
                 PreparedStatement member = connection.prepareStatement(regionInsertSql())) {
                requireFaction(connection, factionId);
                anchor.setInt(1, factionId);
                anchor.setInt(2, slotIndex);
                anchor.setString(3, world);
                anchor.setInt(4, chunkX);
                anchor.setInt(5, chunkZ);
                anchor.setLong(6, createdAt);
                anchor.executeUpdate();
                bindRegionChunk(member, factionId, slotIndex, world, chunkX, chunkZ);
                member.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /** Creates an anchor and its complete initial connected region atomically. */
    public void insertBaseClaimRegion(int factionId,int slotIndex,ChunkKey anchor,long createdAt,
            Collection<ChunkKey> members)throws SQLException{
        try(Connection connection=database.getConnection()){
            boolean autoCommit=connection.getAutoCommit();connection.setAutoCommit(false);
            try(PreparedStatement insertAnchor=connection.prepareStatement(
                    "INSERT INTO base_claims (faction_id, slot_index, anchor_world, anchor_x, anchor_z, created_at) VALUES (?, ?, ?, ?, ?, ?)");
                PreparedStatement insertMember=connection.prepareStatement(regionInsertSql())){
                requireFaction(connection, factionId);
                insertAnchor.setInt(1,factionId);insertAnchor.setInt(2,slotIndex);insertAnchor.setString(3,anchor.world());
                insertAnchor.setInt(4,anchor.x());insertAnchor.setInt(5,anchor.z());insertAnchor.setLong(6,createdAt);
                insertAnchor.executeUpdate();
                for(ChunkKey member:members){bindRegionChunk(insertMember,factionId,slotIndex,member.world(),member.x(),member.z());insertMember.addBatch();}
                insertMember.executeBatch();
                deleteRaidClaims(connection, members);
                connection.commit();
            }catch(SQLException error){connection.rollback();throw error;}finally{connection.setAutoCommit(autoCommit);}
        }
    }

    /** Runtime Base creation with durable rank and claim-ownership checks. */
    public BaseMutationResult insertBaseClaimRegionAuthorized(UUID actor, int factionId, int slotIndex,
            ChunkKey anchor, long createdAt, Collection<ChunkKey> members) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement insertAnchor = connection.prepareStatement(
                    "INSERT INTO base_claims (faction_id, slot_index, anchor_world, anchor_x, anchor_z, created_at) VALUES (?, ?, ?, ?, ?, ?)");
                 PreparedStatement insertMember = connection.prepareStatement(regionInsertSql())) {
                requireFaction(connection, factionId);
                if (!isDurableBaseManager(connection, actor, factionId)) {
                    connection.rollback();
                    return BaseMutationResult.NOT_AUTHORIZED;
                }
                for (ChunkKey member : members) {
                    if (!durablyOwned(connection, member, factionId)) {
                        connection.rollback();
                        return BaseMutationResult.CHANGED;
                    }
                }
                insertAnchor.setInt(1, factionId);
                insertAnchor.setInt(2, slotIndex);
                insertAnchor.setString(3, anchor.world());
                insertAnchor.setInt(4, anchor.x());
                insertAnchor.setInt(5, anchor.z());
                insertAnchor.setLong(6, createdAt);
                insertAnchor.executeUpdate();
                for (ChunkKey member : members) {
                    bindRegionChunk(insertMember, factionId, slotIndex,
                            member.world(), member.x(), member.z());
                    insertMember.addBatch();
                }
                insertMember.executeBatch();
                deleteRaidClaims(connection, members);
                connection.commit();
                return BaseMutationResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
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
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(regionInsertSql())) {
                requireFaction(connection, factionId);
                bindRegionChunk(statement, factionId, slotIndex, world, chunkX, chunkZ);
                statement.executeUpdate();
                deleteRaidClaims(connection, List.of(new ChunkKey(world, chunkX, chunkZ)));
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /** Persists a Base Claim flood-growth result with one connection/transaction. */
    public void insertRegionChunks(int factionId, int slotIndex, Collection<ChunkKey> chunks)
            throws SQLException {
        if (chunks == null || chunks.isEmpty()) return;
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(regionInsertSql())) {
                requireFaction(connection, factionId);
                for (ChunkKey chunk : chunks) {
                    bindRegionChunk(statement, factionId, slotIndex, chunk.world(), chunk.x(), chunk.z());
                    statement.addBatch();
                }
                statement.executeBatch();
                deleteRaidClaims(connection, chunks);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    private String regionInsertSql() {
        return database.dialect() == Database.Dialect.SQLITE
                ? "INSERT OR IGNORE INTO base_claim_region_chunks (faction_id, slot_index, world, chunk_x, chunk_z) "
                        + "VALUES (?, ?, ?, ?, ?)"
                : "INSERT IGNORE INTO base_claim_region_chunks (faction_id, slot_index, world, chunk_x, chunk_z) "
                        + "VALUES (?, ?, ?, ?, ?)";
    }

    private static void bindRegionChunk(PreparedStatement statement, int factionId, int slotIndex, String world,
            int chunkX, int chunkZ) throws SQLException {
        statement.setInt(1, factionId);
        statement.setInt(2, slotIndex);
        statement.setString(3, world);
        statement.setInt(4, chunkX);
        statement.setInt(5, chunkZ);
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

    /** Removes an anchor and all of its membership without a resurrection window. */
    public void deleteBaseClaimRegion(int factionId, int slotIndex) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement members = connection.prepareStatement(
                    "DELETE FROM base_claim_region_chunks WHERE faction_id = ? AND slot_index = ?");
                 PreparedStatement anchor = connection.prepareStatement(
                    "DELETE FROM base_claims WHERE faction_id = ? AND slot_index = ?")) {
                members.setInt(1, factionId);
                members.setInt(2, slotIndex);
                members.executeUpdate();
                anchor.setInt(1, factionId);
                anchor.setInt(2, slotIndex);
                anchor.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Removes a Base region and gives every still-claimed member the same
     * fresh Raid deadline in one transaction. There is no crash window where
     * a chunk is neither Base nor expiring Raid land.
     */
    public void convertBaseRegionToRaid(int factionId, int slotIndex,
            Collection<ChunkKey> claimedMembers, long expiresAt) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                requireFaction(connection, factionId);
                deleteRegionMembers(connection, factionId, slotIndex, null);
                try (PreparedStatement anchor = connection.prepareStatement(
                        "DELETE FROM base_claims WHERE faction_id=? AND slot_index=?")) {
                    anchor.setInt(1, factionId);
                    anchor.setInt(2, slotIndex);
                    anchor.executeUpdate();
                }
                upsertRaidClaims(connection, factionId, claimedMembers, expiresAt);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /** Runtime Base removal with a durable Leader/Co-Leader check. */
    public BaseMutationResult convertBaseRegionToRaidAuthorized(UUID actor, int factionId,
            int slotIndex, Collection<ChunkKey> claimedMembers, long expiresAt) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                requireFaction(connection, factionId);
                if (!isDurableBaseManager(connection, actor, factionId)) {
                    connection.rollback();
                    return BaseMutationResult.NOT_AUTHORIZED;
                }
                if (isDurablyShielded(connection, factionId, System.currentTimeMillis())) {
                    connection.rollback();
                    return BaseMutationResult.SHIELDED;
                }
                deleteRegionMembers(connection, factionId, slotIndex, null);
                try (PreparedStatement anchor = connection.prepareStatement(
                        "DELETE FROM base_claims WHERE faction_id=? AND slot_index=?")) {
                    anchor.setInt(1, factionId);
                    anchor.setInt(2, slotIndex);
                    if (anchor.executeUpdate() != 1) {
                        connection.rollback();
                        return BaseMutationResult.CHANGED;
                    }
                }
                upsertRaidClaims(connection, factionId, claimedMembers, expiresAt);
                connection.commit();
                return BaseMutationResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /** Removes disconnected members from a surviving Base and starts fresh Raid timers atomically. */
    public void convertDisconnectedMembersToRaid(int factionId, int slotIndex,
            Collection<ChunkKey> disconnected, Collection<ChunkKey> stillClaimed, long expiresAt) throws SQLException {
        if (disconnected == null || disconnected.isEmpty()) return;
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                requireFaction(connection, factionId);
                deleteRegionMembers(connection, factionId, slotIndex, disconnected);
                upsertRaidClaims(connection, factionId, stillClaimed, expiresAt);
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    private void deleteRegionMembers(Connection connection, int factionId, int slotIndex,
            Collection<ChunkKey> selected) throws SQLException {
        if (selected == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM base_claim_region_chunks WHERE faction_id=? AND slot_index=?")) {
                statement.setInt(1, factionId);
                statement.setInt(2, slotIndex);
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM base_claim_region_chunks WHERE faction_id=? AND slot_index=? AND world=? AND chunk_x=? AND chunk_z=?")) {
            for (ChunkKey chunk : selected) {
                statement.setInt(1, factionId);
                statement.setInt(2, slotIndex);
                statement.setString(3, chunk.world());
                statement.setInt(4, chunk.x());
                statement.setInt(5, chunk.z());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void upsertRaidClaims(Connection connection, int factionId,
            Collection<ChunkKey> chunks, long expiresAt) throws SQLException {
        if (chunks == null || chunks.isEmpty()) return;
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO raid_claim_expirations(world,chunk_x,chunk_z,faction_id,expires_at_millis) VALUES(?,?,?,?,?) ON CONFLICT(world,chunk_x,chunk_z) DO UPDATE SET faction_id=excluded.faction_id,expires_at_millis=excluded.expires_at_millis"
                : "INSERT INTO raid_claim_expirations(world,chunk_x,chunk_z,faction_id,expires_at_millis) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE faction_id=VALUES(faction_id),expires_at_millis=VALUES(expires_at_millis)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (ChunkKey chunk : chunks) {
                statement.setString(1, chunk.world());
                statement.setInt(2, chunk.x());
                statement.setInt(3, chunk.z());
                statement.setInt(4, factionId);
                statement.setLong(5, expiresAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void deleteRaidClaims(Connection connection, Collection<ChunkKey> chunks) throws SQLException {
        if (chunks == null || chunks.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM raid_claim_expirations WHERE world=? AND chunk_x=? AND chunk_z=?")) {
            for (ChunkKey chunk : chunks) {
                statement.setString(1, chunk.world());
                statement.setInt(2, chunk.x());
                statement.setInt(3, chunk.z());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Purges every Base Claim record owned by a disbanded faction in one transaction. */
    public void deleteFactionData(int factionId) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (String sql : List.of(
                        "DELETE FROM base_claim_region_chunks WHERE faction_id = ?",
                        "DELETE FROM base_claims WHERE faction_id = ?",
                        "DELETE FROM base_claim_slot_purchases WHERE faction_id = ?")) {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setInt(1, factionId);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
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
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                requireFaction(connection, factionId);
                statement.setString(1, world);
                statement.setInt(2, chunkX);
                statement.setInt(3, chunkZ);
                statement.setInt(4, factionId);
                statement.setLong(5, expiresAtMillis);
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    private boolean isDurableBaseManager(Connection connection, UUID actor, int factionId)
            throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM vertex_faction_members WHERE player_uuid=? AND faction_id=?"
                        + suffix)) {
            statement.setString(1, actor.toString());
            statement.setInt(2, factionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return false;
                FactionRole role = FactionRole.parse(row.getString(1), FactionRole.RECRUIT);
                return role == FactionRole.LEADER || role == FactionRole.COLEADER;
            }
        }
    }

    private static boolean durablyOwned(Connection connection, ChunkKey chunk, int factionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM vertex_faction_claims WHERE world=? AND chunk_x=? AND chunk_z=? AND faction_id=?")) {
            statement.setString(1, chunk.world());
            statement.setInt(2, chunk.x());
            statement.setInt(3, chunk.z());
            statement.setInt(4, factionId);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private void requireFaction(Connection connection, int factionId) throws SQLException {
        if (!SqlSchema.lockFactionIfPresent(connection, database.dialect(), factionId)) {
            throw new SQLException("Faction no longer exists");
        }
    }

    private boolean isDurablyShielded(Connection connection, int factionId, long now) throws SQLException {
        ZoneId zone = shieldScheduleZone;
        if (zone == null) return false;

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT forced_state FROM faction_shield_overrides WHERE faction_id=?")) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next()) return "ACTIVE".equalsIgnoreCase(row.getString(1));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT active_until FROM faction_shield_activations WHERE faction_id=?")) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) {
                if (row.next() && now < row.getLong(1)) return true;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_schedule,current_pvp FROM faction_shield_weekly WHERE faction_id=?")) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return false;
                ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone);
                return me.vertex.core.shield.ShieldSchedule.decode(row.getString(1), row.getBoolean(2))
                        .activeAt(time);
            }
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

    public enum BaseMutationResult { OK, NOT_AUTHORIZED, SHIELDED, CHANGED }
}
