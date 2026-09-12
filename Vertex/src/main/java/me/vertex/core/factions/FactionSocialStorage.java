package me.vertex.core.factions;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable relation requests, faction bans, focuses, and faction audit history. */
public final class FactionSocialStorage {
    private final Database database;

    public FactionSocialStorage(Database database) { this.database = database; }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_relation_requests (requester_id INT NOT NULL, target_id INT NOT NULL, relation VARCHAR(16) NOT NULL, expires_at BIGINT NOT NULL, requested_by VARCHAR(36), PRIMARY KEY(requester_id,target_id,relation))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_bans (faction_id INT NOT NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, banned_by VARCHAR(36), banned_at BIGINT NOT NULL, PRIMARY KEY(faction_id,player_uuid))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_focuses (source_faction_id INT NOT NULL, target_faction_id INT NOT NULL, expires_at BIGINT NOT NULL, set_by VARCHAR(36), PRIMARY KEY(source_faction_id,target_faction_id))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_logs (id " + idColumn()
                    + ", faction_id INT NOT NULL, action VARCHAR(64) NOT NULL, actor_uuid VARCHAR(36), actor_name VARCHAR(32), details VARCHAR(512), created_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_archives (faction_id INT PRIMARY KEY, tag VARCHAR(32) NOT NULL, disbanded_at BIGINT NOT NULL, expires_at BIGINT NOT NULL)");
        }
    }

    public List<Request> loadRequests() throws SQLException {
        List<Request> result = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT requester_id,target_id,relation,expires_at,requested_by FROM vertex_faction_relation_requests"); ResultSet rows = s.executeQuery()) {
            while (rows.next()) result.add(new Request(rows.getInt(1), rows.getInt(2),
                    FactionRelation.parse(rows.getString(3), FactionRelation.NEUTRAL), rows.getLong(4), uuid(rows.getString(5))));
        }
        return result;
    }

    public List<Ban> loadBans() throws SQLException {
        List<Ban> result = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT faction_id,player_uuid,player_name,banned_by,banned_at FROM vertex_faction_bans"); ResultSet rows = s.executeQuery()) {
            while (rows.next()) result.add(new Ban(rows.getInt(1), UUID.fromString(rows.getString(2)), rows.getString(3), uuid(rows.getString(4)), rows.getLong(5)));
        }
        return result;
    }

    public List<Focus> loadFocuses() throws SQLException {
        List<Focus> result = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT source_faction_id,target_faction_id,expires_at,set_by FROM vertex_faction_focuses"); ResultSet rows = s.executeQuery()) {
            while (rows.next()) result.add(new Focus(rows.getInt(1), rows.getInt(2), rows.getLong(3), uuid(rows.getString(4))));
        }
        return result;
    }

    public List<Archive> loadArchives() throws SQLException {
        List<Archive> result = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT faction_id,tag,disbanded_at,expires_at FROM vertex_faction_archives");
             ResultSet rows = s.executeQuery()) {
            while (rows.next()) result.add(new Archive(rows.getInt(1), rows.getString(2),
                    rows.getLong(3), rows.getLong(4)));
        }
        return result;
    }

    public void saveRequest(Request request) throws SQLException {
        try (Connection connection = database.getConnection()) { saveRequest(connection, request); }
    }

    public void deleteRequest(int requester, int target, FactionRelation relation) throws SQLException {
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_relation_requests WHERE requester_id=? AND target_id=? AND relation=?")){s.setInt(1,requester);s.setInt(2,target);s.setString(3,relation.name());s.executeUpdate();}
    }

    public void saveBan(Ban ban) throws SQLException {
        String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_bans(faction_id,player_uuid,player_name,banned_by,banned_at) VALUES(?,?,?,?,?) ON CONFLICT(faction_id,player_uuid) DO UPDATE SET player_name=excluded.player_name,banned_by=excluded.banned_by,banned_at=excluded.banned_at":"INSERT INTO vertex_faction_bans(faction_id,player_uuid,player_name,banned_by,banned_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),banned_by=VALUES(banned_by),banned_at=VALUES(banned_at)";
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,ban.factionId());s.setString(2,ban.playerUuid().toString());s.setString(3,ban.playerName());s.setString(4,string(ban.bannedBy()));s.setLong(5,ban.bannedAt());s.executeUpdate();}
    }

    public void deleteBan(int factionId, UUID player) throws SQLException {try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_bans WHERE faction_id=? AND player_uuid=?")){s.setInt(1,factionId);s.setString(2,player.toString());s.executeUpdate();}}

    public void saveFocus(Focus focus) throws SQLException {
        try (Connection connection = database.getConnection()) { saveFocus(connection, focus); }
    }

    public void deleteFocus(int source, int target) throws SQLException {try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_focuses WHERE source_faction_id=? AND target_faction_id=?")){s.setInt(1,source);s.setInt(2,target);s.executeUpdate();}}

    /**
     * Applies the complete relation request/accept/remove state machine while
     * both faction rows are locked. This makes ally limits and reciprocal
     * requests authoritative across every shard sharing the database.
     */
    public RelationMutationOutcome mutateRelation(int source, int target, FactionRelation desired,
            int allyLimit, long now, long requestExpiresAt, UUID actor, FactionRole expectedRole,
            boolean specificDefault, boolean genericDefault) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!lockFactionPair(connection, source, target)) {
                    connection.rollback();
                    return new RelationMutationOutcome(RelationWriteResult.NOT_FOUND,
                            FactionRelation.NEUTRAL, null);
                }
                FactionRole durableRole = lockMemberRole(connection, actor, source);
                String action = desired == FactionRelation.ALLY ? "ally-request"
                        : desired == FactionRelation.ENEMY ? "enemy-declare" : "neutral-request";
                if (durableRole == null || durableRole != expectedRole
                        || !(actionAllowed(connection, source, durableRole, action, specificDefault)
                        || actionAllowed(connection, source, durableRole, "relation", genericDefault))) {
                    connection.rollback();
                    return new RelationMutationOutcome(RelationWriteResult.NOT_AUTHORIZED,
                            FactionRelation.NEUTRAL, null);
                }
                deleteExpiredRequests(connection, now);
                FactionRelation current = durableRelation(connection, source, target);
                if (desired == FactionRelation.ENEMY) {
                    saveMutualRelation(connection, source, target, FactionRelation.ENEMY);
                    deleteRequestsBetween(connection, source, target);
                    deleteFocusPair(connection, source, target);
                    connection.commit();
                    return new RelationMutationOutcome(RelationWriteResult.OK,
                            FactionRelation.ENEMY, null);
                }
                if ((desired == FactionRelation.ALLY && current == FactionRelation.ALLY)
                        || (desired == FactionRelation.NEUTRAL && current == FactionRelation.ALLY)) {
                    saveMutualRelation(connection, source, target, FactionRelation.NEUTRAL);
                    deleteRequestsBetween(connection, source, target);
                    deleteFocusPair(connection, source, target);
                    connection.commit();
                    return new RelationMutationOutcome(RelationWriteResult.REMOVED,
                            FactionRelation.NEUTRAL, null);
                }
                if (desired == FactionRelation.NEUTRAL && current == FactionRelation.NEUTRAL) {
                    connection.commit();
                    return new RelationMutationOutcome(RelationWriteResult.OK,
                            FactionRelation.NEUTRAL, null);
                }
                if (desired == FactionRelation.ALLY
                        && (countAllies(connection, source) >= allyLimit
                        || countAllies(connection, target) >= allyLimit)) {
                    connection.rollback();
                    return new RelationMutationOutcome(RelationWriteResult.ALLY_LIMIT, current, null);
                }
                Request reverse = request(connection, target, source, desired, now);
                if (reverse != null) {
                    // Recheck after both faction locks are held. Every relation
                    // writer follows this same lock order, so the count cannot
                    // change underneath this transaction.
                    if (desired == FactionRelation.ALLY
                            && (countAllies(connection, source) >= allyLimit
                            || countAllies(connection, target) >= allyLimit)) {
                        connection.rollback();
                        return new RelationMutationOutcome(RelationWriteResult.ALLY_LIMIT, current, null);
                    }
                    saveMutualRelation(connection, source, target, desired);
                    deleteRequestsBetween(connection, source, target);
                    deleteFocusPair(connection, source, target);
                    connection.commit();
                    return new RelationMutationOutcome(RelationWriteResult.ACCEPTED, desired, null);
                }
                Request request = new Request(source, target, desired, requestExpiresAt, actor);
                saveRequest(connection, request);
                connection.commit();
                return new RelationMutationOutcome(RelationWriteResult.REQUEST_SENT, current, request);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Admin relation override that also clears every now-invalid request and focus. */
    public boolean forceRelation(int source, int target, FactionRelation relation) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!lockFactionPair(connection, source, target)) {
                    connection.rollback();
                    return false;
                }
                saveMutualRelation(connection, source, target, relation);
                deleteRequestsBetween(connection, source, target);
                deleteFocusPair(connection, source, target);
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

    /** Atomically enforces the per-faction focus limit across all shards. */
    public FocusMutationOutcome mutateFocus(int source, int target, boolean remove, int limit,
            long now, long expiresAt, UUID actor) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!lockFactionPair(connection, source, target)) {
                    connection.rollback();
                    return new FocusMutationOutcome(FocusWriteResult.NOT_FOUND, null);
                }
                if (lockMemberRole(connection, actor, source) == null) {
                    connection.rollback();
                    return new FocusMutationOutcome(FocusWriteResult.NOT_AUTHORIZED, null);
                }
                deleteExpiredFocuses(connection, source, now);
                if (durableRelation(connection, source, target) == FactionRelation.NEUTRAL) {
                    connection.rollback();
                    return new FocusMutationOutcome(FocusWriteResult.INVALID_RELATION, null);
                }
                Focus existing = focus(connection, source, target);
                if (remove) {
                    if (existing == null) {
                        connection.rollback();
                        return new FocusMutationOutcome(FocusWriteResult.NOT_FOUND, null);
                    }
                    deleteFocus(connection, source, target);
                    connection.commit();
                    return new FocusMutationOutcome(FocusWriteResult.REMOVED, null);
                }
                if (existing == null && countFocuses(connection, source) >= Math.max(1, limit)) {
                    connection.rollback();
                    return new FocusMutationOutcome(FocusWriteResult.LIMIT_REACHED, null);
                }
                Focus focus = new Focus(source, target, expiresAt, actor);
                saveFocus(connection, focus);
                connection.commit();
                return new FocusMutationOutcome(FocusWriteResult.OK, focus);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    /** Leader-only ban write with invite invalidation in the same commit. */
    public BanWriteResult mutateBan(Ban ban, boolean remove) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!lockFaction(connection, ban.factionId())) {
                    connection.rollback();
                    return BanWriteResult.NOT_FOUND;
                }
                if (lockMemberRole(connection, ban.bannedBy(), ban.factionId()) != FactionRole.LEADER) {
                    connection.rollback();
                    return BanWriteResult.NOT_AUTHORIZED;
                }
                if (remove) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM vertex_faction_bans WHERE faction_id=? AND player_uuid=?")) {
                        statement.setInt(1, ban.factionId());
                        statement.setString(2, ban.playerUuid().toString());
                        if (statement.executeUpdate() != 1) {
                            connection.rollback();
                            return BanWriteResult.NOT_BANNED;
                        }
                    }
                } else {
                    if (lockMemberRole(connection, ban.playerUuid(), ban.factionId()) != null) {
                        connection.rollback();
                        return BanWriteResult.ALREADY_MEMBER;
                    }
                    saveBan(connection, ban);
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM vertex_faction_invites WHERE faction_id=? AND player_uuid=?")) {
                        statement.setInt(1, ban.factionId());
                        statement.setString(2, ban.playerUuid().toString());
                        statement.executeUpdate();
                    }
                }
                connection.commit();
                return BanWriteResult.OK;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    public void deleteFactionData(int factionId) throws SQLException {
        try(Connection c=database.getConnection()) {
            boolean previous=c.getAutoCommit();c.setAutoCommit(false);
            try {
                for(String sql:List.of("DELETE FROM vertex_faction_relation_requests WHERE requester_id=? OR target_id=?","DELETE FROM vertex_faction_bans WHERE faction_id=?","DELETE FROM vertex_faction_focuses WHERE source_faction_id=? OR target_faction_id=?")){
                    try(PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,factionId);if(sql.contains(" OR "))s.setInt(2,factionId);s.executeUpdate();}
                }
                c.commit();
            } catch(SQLException error){c.rollback();throw error;} finally{c.setAutoCommit(previous);}
        }
    }

    public void deleteFactionDataForMissingFactions() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM vertex_faction_relation_requests WHERE requester_id NOT IN (SELECT id FROM vertex_factions) OR target_id NOT IN (SELECT id FROM vertex_factions)");
            statement.executeUpdate("DELETE FROM vertex_faction_bans WHERE faction_id NOT IN (SELECT id FROM vertex_factions)");
            statement.executeUpdate("DELETE FROM vertex_faction_focuses WHERE source_faction_id NOT IN (SELECT id FROM vertex_factions) OR target_faction_id NOT IN (SELECT id FROM vertex_factions)");
        }
    }

    public void log(int factionId, String action, UUID actor, String actorName, String details, long at) throws SQLException {
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO vertex_faction_logs(faction_id,action,actor_uuid,actor_name,details,created_at) VALUES(?,?,?,?,?,?)")){s.setInt(1,factionId);s.setString(2,action);s.setString(3,string(actor));s.setString(4,actorName);s.setString(5,details);s.setLong(6,at);s.executeUpdate();}
    }

    public void insertLog(int factionId, String action, UUID actor, String actorName, String details, long at) throws SQLException {
        log(factionId, action, actor, actorName, details, at);
    }

    public List<LogRow> logs(int factionId, int limit, int offset) throws SQLException {
        List<LogRow> rows=new ArrayList<>();
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("SELECT id,action,actor_uuid,actor_name,details,created_at FROM vertex_faction_logs WHERE faction_id=? ORDER BY id DESC LIMIT ? OFFSET ?")){s.setInt(1,factionId);s.setInt(2,Math.max(1,Math.min(100,limit)));s.setInt(3,Math.max(0,offset));try(ResultSet r=s.executeQuery()){while(r.next())rows.add(new LogRow(r.getLong(1),r.getString(2),uuid(r.getString(3)),r.getString(4),r.getString(5),r.getLong(6)));}}
        return rows;
    }

    public List<LogEntry> loadLogEntries(int factionId, int page, int pageSize) throws SQLException {
        return logs(factionId, pageSize, Math.max(0, page) * pageSize).stream()
                .map(row -> new LogEntry(row.id(), row.action(), row.actor(), row.actorName(), row.details(), row.createdAt()))
                .toList();
    }

    public void deleteExpired(long now, long ignoredArchiveBefore) throws SQLException {
        try(Connection c=database.getConnection()){
            boolean previous=c.getAutoCommit();c.setAutoCommit(false);
            try {
                try(PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_relation_requests WHERE expires_at<=?")){s.setLong(1,now);s.executeUpdate();}
                try(PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_focuses WHERE expires_at<=?")){s.setLong(1,now);s.executeUpdate();}
                // Retention is measured from disband time, not each log's
                // creation time, so the faction's complete history remains
                // inspectable for the full seven-day archive window.
                try(PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_logs WHERE faction_id IN (SELECT faction_id FROM vertex_faction_archives WHERE expires_at<=?)")){s.setLong(1,now);s.executeUpdate();}
                try(PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_archives WHERE expires_at<=?")){s.setLong(1,now);s.executeUpdate();}
                c.commit();
            } catch (SQLException error) { c.rollback(); throw error; }
            finally { c.setAutoCommit(previous); }
        }
    }

    private String idColumn(){return database.dialect()==Database.Dialect.SQLITE?"INTEGER PRIMARY KEY AUTOINCREMENT":"INT AUTO_INCREMENT PRIMARY KEY";}
    private boolean lockFactionPair(Connection connection, int left, int right) throws SQLException {
        if (left == right) return false;
        String sql = "SELECT id FROM vertex_factions WHERE id IN (?,?) ORDER BY id"
                + (database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "");
        int found = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.min(left, right));
            statement.setInt(2, Math.max(left, right));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) found++;
            }
        }
        return found == 2;
    }

    private boolean lockFaction(Connection connection, int factionId) throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM vertex_factions WHERE id=?" + suffix)) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private FactionRole lockMemberRole(Connection connection, UUID player, int factionId) throws SQLException {
        if (player == null) return null;
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM vertex_faction_members WHERE player_uuid=? AND faction_id=?" + suffix)) {
            statement.setString(1, player.toString());
            statement.setInt(2, factionId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? FactionRole.parse(row.getString(1), null) : null;
            }
        }
    }

    private static boolean actionAllowed(Connection connection, int factionId, FactionRole role,
            String action, boolean fallback) throws SQLException {
        if (role == FactionRole.LEADER) return true;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT allowed FROM vertex_faction_permissions WHERE faction_id=? AND role=? AND action_key=?")) {
            statement.setInt(1, factionId);
            statement.setString(2, role.permissionBucket());
            statement.setString(3, action);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getBoolean(1) : fallback; }
        }
    }

    private void saveBan(Connection connection, Ban ban) throws SQLException {
        String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_bans(faction_id,player_uuid,player_name,banned_by,banned_at) VALUES(?,?,?,?,?) ON CONFLICT(faction_id,player_uuid) DO UPDATE SET player_name=excluded.player_name,banned_by=excluded.banned_by,banned_at=excluded.banned_at":"INSERT INTO vertex_faction_bans(faction_id,player_uuid,player_name,banned_by,banned_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),banned_by=VALUES(banned_by),banned_at=VALUES(banned_at)";
        try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setInt(1,ban.factionId());statement.setString(2,ban.playerUuid().toString());statement.setString(3,ban.playerName());statement.setString(4,string(ban.bannedBy()));statement.setLong(5,ban.bannedAt());statement.executeUpdate();}
    }

    private FactionRelation durableRelation(Connection connection, int left, int right) throws SQLException {
        FactionRelation forward = relation(connection, left, right);
        FactionRelation reverse = relation(connection, right, left);
        if (forward == FactionRelation.ENEMY || reverse == FactionRelation.ENEMY) return FactionRelation.ENEMY;
        if (forward == FactionRelation.ALLY && reverse == FactionRelation.ALLY) return FactionRelation.ALLY;
        return FactionRelation.NEUTRAL;
    }

    private static FactionRelation relation(Connection connection, int source, int target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT relation FROM vertex_faction_relations WHERE faction_id=? AND target_faction_id=?")) {
            statement.setInt(1, source);
            statement.setInt(2, target);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? FactionRelation.parse(row.getString(1), FactionRelation.NEUTRAL)
                        : FactionRelation.NEUTRAL;
            }
        }
    }

    private int countAllies(Connection connection, int factionId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM vertex_faction_relations a "
                + "JOIN vertex_faction_relations b ON b.faction_id=a.target_faction_id "
                + "AND b.target_faction_id=a.faction_id AND b.relation='ALLY' "
                + "WHERE a.faction_id=? AND a.relation='ALLY'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, factionId);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
        }
    }

    private Request request(Connection connection, int requester, int target,
            FactionRelation relation, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT expires_at,requested_by FROM vertex_faction_relation_requests "
                        + "WHERE requester_id=? AND target_id=? AND relation=? AND expires_at>?")) {
            statement.setInt(1, requester);
            statement.setInt(2, target);
            statement.setString(3, relation.name());
            statement.setLong(4, now);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new Request(requester, target, relation, row.getLong(1),
                        uuid(row.getString(2))) : null;
            }
        }
    }

    private void saveRequest(Connection connection, Request request) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO vertex_faction_relation_requests(requester_id,target_id,relation,expires_at,requested_by) VALUES(?,?,?,?,?) ON CONFLICT(requester_id,target_id,relation) DO UPDATE SET expires_at=excluded.expires_at,requested_by=excluded.requested_by"
                : "INSERT INTO vertex_faction_relation_requests(requester_id,target_id,relation,expires_at,requested_by) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at),requested_by=VALUES(requested_by)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, request.requesterId()); statement.setInt(2, request.targetId());
            statement.setString(3, request.relation().name()); statement.setLong(4, request.expiresAt());
            statement.setString(5, string(request.requestedBy())); statement.executeUpdate();
        }
    }

    private void saveFocus(Connection connection, Focus focus) throws SQLException {
        String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_focuses(source_faction_id,target_faction_id,expires_at,set_by) VALUES(?,?,?,?) ON CONFLICT(source_faction_id,target_faction_id) DO UPDATE SET expires_at=excluded.expires_at,set_by=excluded.set_by":"INSERT INTO vertex_faction_focuses(source_faction_id,target_faction_id,expires_at,set_by) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE expires_at=VALUES(expires_at),set_by=VALUES(set_by)";
        try(PreparedStatement statement=connection.prepareStatement(sql)){statement.setInt(1,focus.sourceId());statement.setInt(2,focus.targetId());statement.setLong(3,focus.expiresAt());statement.setString(4,string(focus.setBy()));statement.executeUpdate();}
    }

    private static void saveMutualRelation(Connection connection, int left, int right,
            FactionRelation relation) throws SQLException {
        if (relation == FactionRelation.NEUTRAL) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM vertex_faction_relations WHERE (faction_id=? AND target_faction_id=?) OR (faction_id=? AND target_faction_id=?)")) {
                statement.setInt(1, left); statement.setInt(2, right);
                statement.setInt(3, right); statement.setInt(4, left); statement.executeUpdate();
            }
            return;
        }
        // UPDATE then INSERT is portable across both supported SQL dialects.
        for (int[] pair : List.of(new int[]{left, right}, new int[]{right, left})) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE vertex_faction_relations SET relation=? WHERE faction_id=? AND target_faction_id=?")) {
                update.setString(1, relation.name()); update.setInt(2, pair[0]); update.setInt(3, pair[1]);
                if (update.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO vertex_faction_relations(faction_id,target_faction_id,relation) VALUES(?,?,?)")) {
                        insert.setInt(1, pair[0]); insert.setInt(2, pair[1]);
                        insert.setString(3, relation.name()); insert.executeUpdate();
                    }
                }
            }
        }
    }

    private static void deleteExpiredRequests(Connection connection, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM vertex_faction_relation_requests WHERE expires_at<=?")) {
            statement.setLong(1, now); statement.executeUpdate();
        }
    }

    private static void deleteRequestsBetween(Connection connection, int left, int right) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM vertex_faction_relation_requests WHERE (requester_id=? AND target_id=?) OR (requester_id=? AND target_id=?)")) {
            statement.setInt(1, left); statement.setInt(2, right);
            statement.setInt(3, right); statement.setInt(4, left); statement.executeUpdate();
        }
    }

    private static void deleteFocusPair(Connection connection, int left, int right) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM vertex_faction_focuses WHERE (source_faction_id=? AND target_faction_id=?) OR (source_faction_id=? AND target_faction_id=?)")) {
            statement.setInt(1, left); statement.setInt(2, right);
            statement.setInt(3, right); statement.setInt(4, left); statement.executeUpdate();
        }
    }

    private static void deleteExpiredFocuses(Connection connection, int source, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM vertex_faction_focuses WHERE source_faction_id=? AND expires_at<=?")) {
            statement.setInt(1, source); statement.setLong(2, now); statement.executeUpdate();
        }
    }

    private static Focus focus(Connection connection, int source, int target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT expires_at,set_by FROM vertex_faction_focuses WHERE source_faction_id=? AND target_faction_id=?")) {
            statement.setInt(1, source); statement.setInt(2, target);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new Focus(source, target, row.getLong(1), uuid(row.getString(2))) : null;
            }
        }
    }

    private static int countFocuses(Connection connection, int source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM vertex_faction_focuses WHERE source_faction_id=?")) {
            statement.setInt(1, source);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
        }
    }

    private static void deleteFocus(Connection connection, int source, int target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM vertex_faction_focuses WHERE source_faction_id=? AND target_faction_id=?")) {
            statement.setInt(1, source); statement.setInt(2, target); statement.executeUpdate();
        }
    }

    private static String string(UUID uuid){return uuid==null?null:uuid.toString();}
    private static UUID uuid(String raw){return raw==null?null:UUID.fromString(raw);}
    public record Request(int requesterId,int targetId,FactionRelation relation,long expiresAt,UUID requestedBy){}
    public record Ban(int factionId,UUID playerUuid,String playerName,UUID bannedBy,long bannedAt){}
    public record Focus(int sourceId,int targetId,long expiresAt,UUID setBy){}
    public record Archive(int factionId,String tag,long disbandedAt,long expiresAt){}
    public record LogRow(long id,String action,UUID actor,String actorName,String details,long createdAt){}
    public record LogEntry(long id,String action,UUID actorUuid,String actorName,String details,long createdAt){}
    public enum RelationWriteResult { OK, REQUEST_SENT, ACCEPTED, REMOVED, ALLY_LIMIT, NOT_FOUND, NOT_AUTHORIZED }
    public record RelationMutationOutcome(RelationWriteResult result, FactionRelation relation,
                                          Request request) { }
    public enum FocusWriteResult { OK, REMOVED, NOT_FOUND, INVALID_RELATION, LIMIT_REACHED, NOT_AUTHORIZED }
    public record FocusMutationOutcome(FocusWriteResult result, Focus focus) { }
    public enum BanWriteResult { OK, NOT_FOUND, NOT_AUTHORIZED, ALREADY_MEMBER, NOT_BANNED }
}
