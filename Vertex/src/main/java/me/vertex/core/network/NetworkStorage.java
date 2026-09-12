package me.vertex.core.network;

import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Shared SQL state for shard health, transfer handoffs, locations, queues, and cooldowns. */
public final class NetworkStorage {
    private final Database database;

    public NetworkStorage(Database database) { this.database = database; }

    public void init() throws SQLException {
        String eventId = database.dialect() == Database.Dialect.SQLITE
                ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT AUTO_INCREMENT PRIMARY KEY";
        String blob = database.dialect() == Database.Dialect.SQLITE ? "BLOB" : "LONGBLOB";
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_network_shards (shard_id VARCHAR(64) PRIMARY KEY, role VARCHAR(32) NOT NULL, state VARCHAR(32) NOT NULL, max_players INT NOT NULL, current_players INT NOT NULL, heartbeat_at BIGINT NOT NULL, restart_eta BIGINT NOT NULL, updated_by VARCHAR(64))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_network_events (id " + eventId + ", source_shard VARCHAR(64) NOT NULL, topic VARCHAR(64) NOT NULL, payload VARCHAR(2048), created_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_transfer_handoffs (transfer_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, source_shard VARCHAR(64) NOT NULL, destination_shard VARCHAR(64) NOT NULL, destination_world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, reason VARCHAR(64) NOT NULL, state VARCHAR(24) NOT NULL, snapshot " + blob + " NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, error VARCHAR(512))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_queued_transfers (player_uuid VARCHAR(36) PRIMARY KEY, source_shard VARCHAR(64) NOT NULL, destination_shard VARCHAR(64) NOT NULL, destination_world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, reason VARCHAR(64) NOT NULL, expires_at BIGINT NOT NULL, created_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_transfer_locks (player_uuid VARCHAR(36) PRIMARY KEY, transfer_kind VARCHAR(16) NOT NULL, reference_id VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_global_locations (location_type VARCHAR(32) NOT NULL, location_name VARCHAR(64) NOT NULL, shard_id VARCHAR(64) NOT NULL, world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, description VARCHAR(512), revision BIGINT NOT NULL, PRIMARY KEY(location_type,location_name))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_teleport_cooldowns (player_uuid VARCHAR(36) NOT NULL, cooldown_type VARCHAR(32) NOT NULL, available_at BIGINT NOT NULL, PRIMARY KEY(player_uuid,cooldown_type))");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_network_state_history (snapshot_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, shard_id VARCHAR(64) NOT NULL, reason VARCHAR(64) NOT NULL, snapshot " + blob + " NOT NULL, created_at BIGINT NOT NULL)");
            SqlSchema.ensureColumn(connection, "vertex_queued_transfers", "source_shard",
                    "VARCHAR(64) NOT NULL DEFAULT ''");
            SqlSchema.ensureIndex(connection, "vertex_network_events", "vertex_network_events_created_idx",
                    false, "created_at");
            SqlSchema.ensureIndex(connection, "vertex_transfer_handoffs", "vertex_transfer_player_state_idx",
                    false, "player_uuid", "state");
            reconcileTransferLocks(connection);
        }
    }

    public Optional<ShardRow> shard(String shardId) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT shard_id,role,state,max_players,current_players,heartbeat_at,restart_eta,updated_by FROM vertex_network_shards WHERE shard_id=?")) {
            statement.setString(1, shardId);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(readShard(row)) : Optional.empty(); }
        }
    }

    public List<ShardRow> shards() throws SQLException {
        List<ShardRow> rows = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT shard_id,role,state,max_players,current_players,heartbeat_at,restart_eta,updated_by FROM vertex_network_shards ORDER BY shard_id");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) rows.add(readShard(results));
        }
        return rows;
    }

    private static ShardRow readShard(ResultSet row) throws SQLException {
        return new ShardRow(row.getString(1), row.getString(2), ShardState.parse(row.getString(3), ShardState.OFFLINE),
                row.getInt(4), row.getInt(5), row.getLong(6), row.getLong(7), row.getString(8));
    }

    /**
     * Publishes liveness without allowing stale local state to clear a
     * CRASH_RECOVERY decision made by another shard. Only setShardState may
     * explicitly return such a shard to service.
     */
    public ShardState heartbeat(String shardId, String role, ShardState state, int maxPlayers,
            int players, long restartEta, String actor) throws SQLException {
        String sql;
        if (database.dialect() == Database.Dialect.SQLITE) {
            sql = "INSERT INTO vertex_network_shards(shard_id,role,state,max_players,current_players,heartbeat_at,restart_eta,updated_by) "
                    + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(shard_id) DO UPDATE SET "
                    + "role=excluded.role,max_players=excluded.max_players,current_players=excluded.current_players,"
                    + "heartbeat_at=excluded.heartbeat_at,restart_eta=excluded.restart_eta,updated_by=excluded.updated_by,"
                    + "state=CASE WHEN vertex_network_shards.state='CRASH_RECOVERY' AND excluded.state<>'CRASH_RECOVERY' "
                    + "THEN vertex_network_shards.state ELSE excluded.state END";
        } else {
            sql = "INSERT INTO vertex_network_shards(shard_id,role,state,max_players,current_players,heartbeat_at,restart_eta,updated_by) "
                    + "VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                    + "role=VALUES(role),max_players=VALUES(max_players),current_players=VALUES(current_players),"
                    + "heartbeat_at=VALUES(heartbeat_at),restart_eta=VALUES(restart_eta),updated_by=VALUES(updated_by),"
                    + "state=IF(state='CRASH_RECOVERY' AND VALUES(state)<>'CRASH_RECOVERY',state,VALUES(state))";
        }
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, shardId); statement.setString(2, role); statement.setString(3, state.name());
            statement.setInt(4, maxPlayers); statement.setInt(5, players); statement.setLong(6, System.currentTimeMillis());
            statement.setLong(7, restartEta); statement.setString(8, actor); statement.executeUpdate();
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT state FROM vertex_network_shards WHERE shard_id=?")) {
                read.setString(1, shardId);
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) throw new SQLException("Heartbeat row disappeared for shard " + shardId);
                    return ShardState.parse(row.getString(1), ShardState.CRASH_RECOVERY);
                }
            }
        }
    }

    public boolean hasActiveTransfers() throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM vertex_transfer_locks LIMIT 1"); ResultSet row = statement.executeQuery()) {
            return row.next();
        }
    }

    public void setShardState(String shardId, ShardState state, long eta, String actor) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE vertex_network_shards SET state=?,restart_eta=?,updated_by=?,heartbeat_at=? WHERE shard_id=?")) {
            statement.setString(1, state.name()); statement.setLong(2, eta); statement.setString(3, actor);
            statement.setLong(4, System.currentTimeMillis()); statement.setString(5, shardId);
            if (statement.executeUpdate() == 0) throw new SQLException("Unknown shard " + shardId);
        }
    }

    public long publish(String shard, String topic, String payload) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vertex_network_events(source_shard,topic,payload,created_at) VALUES(?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, shard); statement.setString(2, topic); statement.setString(3, payload);
            statement.setLong(4, System.currentTimeMillis()); statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) { return keys.next() ? keys.getLong(1) : 0L; }
        }
    }

    public List<EventRow> eventsAfter(long id, int limit) throws SQLException {
        List<EventRow> rows = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id,source_shard,topic,payload,created_at FROM vertex_network_events WHERE id>? ORDER BY id ASC LIMIT ?")) {
            statement.setLong(1, id); statement.setInt(2, Math.max(1, Math.min(1000, limit)));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) rows.add(new EventRow(results.getLong(1), results.getString(2),
                        results.getString(3), results.getString(4), results.getLong(5)));
            }
        }
        return rows;
    }

    public long latestEventId() throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(id),0) FROM vertex_network_events"); ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }

    public boolean createHandoff(Handoff row) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit(); connection.setAutoCommit(false);
            try {
                if (!acquireTransferLock(connection, row.playerUuid(), "HANDOFF", row.id(), row.createdAt())) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO vertex_transfer_handoffs(transfer_id,player_uuid,source_shard,destination_shard,destination_world,x,y,z,yaw,pitch,reason,state,snapshot,created_at,updated_at,error) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                 PreparedStatement history = connection.prepareStatement(
                    "INSERT INTO vertex_network_state_history(snapshot_id,player_uuid,shard_id,reason,snapshot,created_at) VALUES(?,?,?,?,?,?)")) {
                    bindHandoff(insert, row); insert.executeUpdate();
                    history.setString(1, UUID.randomUUID().toString()); history.setString(2, row.playerUuid().toString());
                    history.setString(3, row.sourceShard()); history.setString(4, "TRANSFER_" + row.reason());
                    history.setBytes(5, row.snapshot()); history.setLong(6, row.createdAt()); history.executeUpdate();
                    connection.commit();
                    return true;
                }
            } catch (SQLException error) { connection.rollback(); throw error; }
            finally { connection.setAutoCommit(previous); }
        }
    }

    public Optional<Handoff> incoming(UUID player, String destination) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT h.transfer_id,h.player_uuid,h.source_shard,h.destination_shard,h.destination_world,h.x,h.y,h.z,h.yaw,h.pitch,h.reason,h.state,h.snapshot,h.created_at,h.updated_at,h.error FROM vertex_transfer_handoffs h JOIN vertex_transfer_locks l ON l.reference_id=h.transfer_id AND l.player_uuid=h.player_uuid AND l.transfer_kind='HANDOFF' WHERE h.player_uuid=? AND h.destination_shard=? AND h.state='PREPARED' ORDER BY h.created_at DESC LIMIT 1")) {
            statement.setString(1, player.toString()); statement.setString(2, destination);
            try (ResultSet results = statement.executeQuery()) { return results.next() ? Optional.of(readHandoff(results)) : Optional.empty(); }
        }
    }

    public Optional<Handoff> unresolved(UUID player) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT h.transfer_id,h.player_uuid,h.source_shard,h.destination_shard,h.destination_world,h.x,h.y,h.z,h.yaw,h.pitch,h.reason,h.state,h.snapshot,h.created_at,h.updated_at,h.error FROM vertex_transfer_handoffs h JOIN vertex_transfer_locks l ON l.reference_id=h.transfer_id AND l.player_uuid=h.player_uuid AND l.transfer_kind='HANDOFF' WHERE h.player_uuid=? AND h.state IN('PREPARED','LOADING','RECOVERY_REQUIRED','RECOVERING') ORDER BY h.created_at DESC LIMIT 1")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) { return results.next() ? Optional.of(readHandoff(results)) : Optional.empty(); }
        }
    }

    public List<Handoff> staleHandoffs(long olderThan) throws SQLException {
        List<Handoff> rows = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT h.transfer_id,h.player_uuid,h.source_shard,h.destination_shard,h.destination_world,h.x,h.y,h.z,h.yaw,h.pitch,h.reason,h.state,h.snapshot,h.created_at,h.updated_at,h.error FROM vertex_transfer_handoffs h JOIN vertex_transfer_locks l ON l.reference_id=h.transfer_id AND l.player_uuid=h.player_uuid AND l.transfer_kind='HANDOFF' WHERE h.state IN('PREPARED','LOADING','RECOVERY_REQUIRED','RECOVERING') AND h.updated_at<?")) {
            statement.setLong(1, olderThan);
            try (ResultSet results = statement.executeQuery()) { while (results.next()) rows.add(readHandoff(results)); }
        }
        return rows;
    }

    public boolean transition(String id, String expected, String next, String error) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous=connection.getAutoCommit();connection.setAutoCommit(false);
            try(PreparedStatement statement = connection.prepareStatement(
                    "UPDATE vertex_transfer_handoffs SET state=?,updated_at=?,error=? WHERE transfer_id=? AND state=?")) {
                statement.setString(1, next); statement.setLong(2, System.currentTimeMillis()); statement.setString(3, error);
                statement.setString(4, id); statement.setString(5, expected);
                boolean changed=statement.executeUpdate()==1;
                if(changed&&isTerminal(next))releaseHandoffLock(connection,id);
                connection.commit();return changed;
            }catch(SQLException errorThrown){connection.rollback();throw errorThrown;}finally{connection.setAutoCommit(previous);}
        }
    }

    /** Leaves the source snapshot active so Spawn can restore it. */
    public boolean markRecoveryRequired(String id,String error)throws SQLException{
        try(Connection connection=database.getConnection();PreparedStatement statement=connection.prepareStatement(
                "UPDATE vertex_transfer_handoffs SET state='RECOVERY_REQUIRED',updated_at=?,error=? WHERE transfer_id=? AND state IN('PREPARED','LOADING','RECOVERING')")){
            statement.setLong(1,System.currentTimeMillis());statement.setString(2,error);statement.setString(3,id);
            return statement.executeUpdate()==1;
        }
    }

    /** Replaces a still-PREPARED destination after final validation requests a safe reroll. */
    public boolean replacePreparedDestination(String id, NetworkLocation destination) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE vertex_transfer_handoffs SET destination_shard=?,destination_world=?,x=?,y=?,z=?,yaw=?,pitch=?,updated_at=? WHERE transfer_id=? AND state='PREPARED'")) {
            statement.setString(1, destination.shardId());
            statement.setString(2, destination.world());
            statement.setDouble(3, destination.x());
            statement.setDouble(4, destination.y());
            statement.setDouble(5, destination.z());
            statement.setFloat(6, destination.yaw());
            statement.setFloat(7, destination.pitch());
            statement.setLong(8, System.currentTimeMillis());
            statement.setString(9, id);
            return statement.executeUpdate() == 1;
        }
    }

    public List<Handoff> unresolvedHandoffs() throws SQLException {
        List<Handoff> rows = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT h.transfer_id,h.player_uuid,h.source_shard,h.destination_shard,h.destination_world,h.x,h.y,h.z,h.yaw,h.pitch,h.reason,h.state,h.snapshot,h.created_at,h.updated_at,h.error FROM vertex_transfer_handoffs h JOIN vertex_transfer_locks l ON l.reference_id=h.transfer_id AND l.player_uuid=h.player_uuid AND l.transfer_kind='HANDOFF' WHERE h.state IN('PREPARED','LOADING','RECOVERY_REQUIRED','RECOVERING') ORDER BY h.created_at");
             ResultSet results = statement.executeQuery()) {
            while (results.next()) rows.add(readHandoff(results));
        }
        return rows;
    }

    public boolean resolveHandoff(String id, String next, String error) throws SQLException {
        if (!List.of("ACKED", "FAILED", "ABORTED").contains(next)) return false;
        try (Connection connection = database.getConnection()) {
            boolean previous=connection.getAutoCommit();connection.setAutoCommit(false);
            try(PreparedStatement statement = connection.prepareStatement(
                    "UPDATE vertex_transfer_handoffs SET state=?,updated_at=?,error=? WHERE transfer_id=? AND state IN('PREPARED','LOADING','RECOVERY_REQUIRED','RECOVERING')")) {
                statement.setString(1, next);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, error);
                statement.setString(4, id);
                boolean changed=statement.executeUpdate()==1;
                if(changed)releaseHandoffLock(connection,id);
                connection.commit();return changed;
            }catch(SQLException errorThrown){connection.rollback();throw errorThrown;}finally{connection.setAutoCommit(previous);}
        }
    }

    public boolean queue(UUID player, String sourceShard, NetworkLocation destination, String reason, long expiresAt) throws SQLException {
        String sql = upsert("vertex_queued_transfers", "player_uuid,source_shard,destination_shard,destination_world,x,y,z,yaw,pitch,reason,expires_at,created_at",
                "?,?,?,?,?,?,?,?,?,?,?,?", "source_shard,destination_shard,destination_world,x,y,z,yaw,pitch,reason,expires_at,created_at");
        try (Connection connection = database.getConnection()) {
            boolean previous=connection.getAutoCommit();connection.setAutoCommit(false);
            try {
                long now=System.currentTimeMillis();
                if(!acquireTransferLock(connection,player,"QUEUE",player.toString(),now)){connection.rollback();return false;}
                try(PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, player.toString()); statement.setString(2, normalize(sourceShard));
                    statement.setString(3, destination.shardId()); statement.setString(4, destination.world());
                    statement.setDouble(5, destination.x()); statement.setDouble(6, destination.y()); statement.setDouble(7, destination.z());
                    statement.setFloat(8, destination.yaw()); statement.setFloat(9, destination.pitch()); statement.setString(10, reason);
                    statement.setLong(11, expiresAt); statement.setLong(12, now); statement.executeUpdate();
                }
                connection.commit();return true;
            }catch(SQLException error){connection.rollback();throw error;}finally{connection.setAutoCommit(previous);}
        }
    }

    public List<QueuedTransfer> queuedFrom(String shard) throws SQLException {
        List<QueuedTransfer> rows = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid,source_shard,destination_shard,destination_world,x,y,z,yaw,pitch,reason,expires_at,created_at FROM vertex_queued_transfers WHERE source_shard=?")) {
            statement.setString(1, shard);
            try (ResultSet results = statement.executeQuery()) { while (results.next()) rows.add(new QueuedTransfer(
                    UUID.fromString(results.getString(1)), results.getString(2),
                    new NetworkLocation(results.getString(3), results.getString(4), results.getDouble(5),
                            results.getDouble(6), results.getDouble(7), results.getFloat(8), results.getFloat(9)),
                    results.getString(10), results.getLong(11), results.getLong(12))); }
        }
        return rows;
    }

    public Optional<QueuedTransfer> queued(UUID player) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid,source_shard,destination_shard,destination_world,x,y,z,yaw,pitch,reason,expires_at,created_at FROM vertex_queued_transfers WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) return Optional.empty();
                return Optional.of(new QueuedTransfer(UUID.fromString(results.getString(1)), results.getString(2),
                        new NetworkLocation(results.getString(3), results.getString(4), results.getDouble(5),
                                results.getDouble(6), results.getDouble(7), results.getFloat(8), results.getFloat(9)),
                        results.getString(10), results.getLong(11), results.getLong(12)));
            }
        }
    }

    public void deleteQueue(UUID player) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous=connection.getAutoCommit();connection.setAutoCommit(false);
            try(PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM vertex_queued_transfers WHERE player_uuid=?")) {
                statement.setString(1, player.toString()); statement.executeUpdate();
                releaseQueueLock(connection,player);connection.commit();
            }catch(SQLException error){connection.rollback();throw error;}finally{connection.setAutoCommit(previous);}
        }
    }

    public void cancelQueuesFor(String shard) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous=connection.getAutoCommit();connection.setAutoCommit(false);
            try(PreparedStatement locks=connection.prepareStatement("DELETE FROM vertex_transfer_locks WHERE transfer_kind='QUEUE' AND player_uuid IN (SELECT player_uuid FROM vertex_queued_transfers WHERE destination_shard=?)");PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM vertex_queued_transfers WHERE destination_shard=?")) {
                locks.setString(1,shard);locks.executeUpdate();statement.setString(1, shard); statement.executeUpdate();connection.commit();
            }catch(SQLException error){connection.rollback();throw error;}finally{connection.setAutoCommit(previous);}
        }
    }

    public void saveLocation(String type, String name, NetworkLocation location, String description) throws SQLException {
        String sql = upsert("vertex_global_locations", "location_type,location_name,shard_id,world,x,y,z,yaw,pitch,description,revision",
                "?,?,?,?,?,?,?,?,?,?,?", "shard_id,world,x,y,z,yaw,pitch,description,revision");
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalize(type)); statement.setString(2, normalize(name)); statement.setString(3, location.shardId());
            statement.setString(4, location.world()); statement.setDouble(5, location.x()); statement.setDouble(6, location.y());
            statement.setDouble(7, location.z()); statement.setFloat(8, location.yaw()); statement.setFloat(9, location.pitch());
            statement.setString(10, description); statement.setLong(11, System.currentTimeMillis()); statement.executeUpdate();
        }
    }

    public Optional<LocationRow> location(String type, String name) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT shard_id,world,x,y,z,yaw,pitch,description,revision FROM vertex_global_locations WHERE location_type=? AND location_name=?")) {
            statement.setString(1, normalize(type)); statement.setString(2, normalize(name));
            try (ResultSet row = statement.executeQuery()) { return row.next() ? Optional.of(new LocationRow(normalize(type), normalize(name),
                    new NetworkLocation(row.getString(1), row.getString(2), row.getDouble(3), row.getDouble(4), row.getDouble(5),
                            row.getFloat(6), row.getFloat(7)), row.getString(8), row.getLong(9))) : Optional.empty(); }
        }
    }

    public List<LocationRow> locations(String type) throws SQLException {
        List<LocationRow> rows = new ArrayList<>();
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT location_name,shard_id,world,x,y,z,yaw,pitch,description,revision FROM vertex_global_locations WHERE location_type=? ORDER BY location_name")) {
            statement.setString(1, normalize(type));
            try (ResultSet row = statement.executeQuery()) { while (row.next()) rows.add(new LocationRow(normalize(type), row.getString(1),
                    new NetworkLocation(row.getString(2), row.getString(3), row.getDouble(4), row.getDouble(5), row.getDouble(6),
                            row.getFloat(7), row.getFloat(8)), row.getString(9), row.getLong(10))); }
        }
        return rows;
    }

    public void deleteLocation(String type, String name) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM vertex_global_locations WHERE location_type=? AND location_name=?")) {
            statement.setString(1, normalize(type)); statement.setString(2, normalize(name)); statement.executeUpdate();
        }
    }

    public long cooldown(UUID player, String type) throws SQLException {
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT available_at FROM vertex_teleport_cooldowns WHERE player_uuid=? AND cooldown_type=?")) {
            statement.setString(1, player.toString()); statement.setString(2, normalize(type));
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getLong(1) : 0L; }
        }
    }

    public void saveCooldown(UUID player, String type, long availableAt) throws SQLException {
        String sql = upsert("vertex_teleport_cooldowns", "player_uuid,cooldown_type,available_at", "?,?,?", "available_at");
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString()); statement.setString(2, normalize(type));
            statement.setLong(3, availableAt); statement.executeUpdate();
        }
    }

    public void clearSeasonCooldowns() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM vertex_teleport_cooldowns");
            statement.executeUpdate("DELETE FROM vertex_queued_transfers");
            statement.executeUpdate("DELETE FROM vertex_transfer_locks WHERE transfer_kind='QUEUE'");
        }
    }

    public void prune(long historyBefore, long eventsBefore) throws SQLException {
        try (Connection connection = database.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement history = connection.prepareStatement(
                    "DELETE FROM vertex_network_state_history WHERE created_at<?");
                 PreparedStatement handoffs = connection.prepareStatement(
                    "DELETE FROM vertex_transfer_handoffs WHERE updated_at<? AND state IN('ACKED','FAILED','ABORTED')");
                 PreparedStatement events = connection.prepareStatement(
                    "DELETE FROM vertex_network_events WHERE created_at<?")) {
                history.setLong(1, historyBefore); history.executeUpdate();
                handoffs.setLong(1, historyBefore); handoffs.executeUpdate();
                events.setLong(1, eventsBefore); events.executeUpdate();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(previous);
            }
        }
    }

    private String upsert(String table, String columns, String placeholders, String updates) {
        String[] keys = updates.split(",");
        StringBuilder update = new StringBuilder();
        for (String key : keys) {
            if (!update.isEmpty()) update.append(',');
            String trimmed = key.trim();
            update.append(trimmed).append(database.dialect() == Database.Dialect.SQLITE
                    ? "=excluded." + trimmed : "=VALUES(" + trimmed + ")");
        }
        if (database.dialect() == Database.Dialect.SQLITE) {
            int primaryColumns = table.equals("vertex_global_locations") || table.equals("vertex_teleport_cooldowns") ? 2 : 1;
            String conflict = String.join(",", java.util.Arrays.asList(columns.split(",")).subList(0, primaryColumns));
            return "INSERT INTO " + table + "(" + columns + ") VALUES(" + placeholders + ") ON CONFLICT("
                    + conflict + ") DO UPDATE SET " + update;
        }
        return "INSERT INTO " + table + "(" + columns + ") VALUES(" + placeholders
                + ") ON DUPLICATE KEY UPDATE " + update;
    }

    private boolean acquireTransferLock(Connection connection, UUID player, String kind,
            String reference, long createdAt) throws SQLException {
        String sql=database.dialect()==Database.Dialect.SQLITE
                ?"INSERT OR IGNORE INTO vertex_transfer_locks(player_uuid,transfer_kind,reference_id,created_at) VALUES(?,?,?,?)"
                :"INSERT IGNORE INTO vertex_transfer_locks(player_uuid,transfer_kind,reference_id,created_at) VALUES(?,?,?,?)";
        try(PreparedStatement statement=connection.prepareStatement(sql)){
            statement.setString(1,player.toString());statement.setString(2,kind);
            statement.setString(3,reference);statement.setLong(4,createdAt);
            return statement.executeUpdate()==1;
        }
    }

    private static void releaseHandoffLock(Connection connection,String transferId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement(
                "DELETE FROM vertex_transfer_locks WHERE transfer_kind='HANDOFF' AND reference_id=?")){
            statement.setString(1,transferId);statement.executeUpdate();
        }
    }

    private static void releaseQueueLock(Connection connection,UUID player)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement(
                "DELETE FROM vertex_transfer_locks WHERE player_uuid=? AND transfer_kind='QUEUE'")){
            statement.setString(1,player.toString());statement.executeUpdate();
        }
    }

    private void reconcileTransferLocks(Connection connection)throws SQLException{
        try(Statement statement=connection.createStatement()){
            statement.executeUpdate("DELETE FROM vertex_transfer_locks WHERE transfer_kind='HANDOFF' AND reference_id NOT IN (SELECT transfer_id FROM vertex_transfer_handoffs WHERE state IN('PREPARED','LOADING','RECOVERY_REQUIRED','RECOVERING'))");
            statement.executeUpdate("DELETE FROM vertex_transfer_locks WHERE transfer_kind='QUEUE' AND player_uuid NOT IN (SELECT player_uuid FROM vertex_queued_transfers)");
        }
        try(PreparedStatement handoffs=connection.prepareStatement(
                "SELECT transfer_id,player_uuid,created_at FROM vertex_transfer_handoffs WHERE state IN('PREPARED','LOADING','RECOVERY_REQUIRED','RECOVERING') ORDER BY created_at DESC");ResultSet rows=handoffs.executeQuery()){
            while(rows.next())acquireTransferLock(connection,UUID.fromString(rows.getString(2)),
                    "HANDOFF",rows.getString(1),rows.getLong(3));
        }
        try(PreparedStatement queues=connection.prepareStatement(
                "SELECT player_uuid,created_at FROM vertex_queued_transfers ORDER BY created_at DESC");ResultSet rows=queues.executeQuery()){
            while(rows.next()){UUID player=UUID.fromString(rows.getString(1));acquireTransferLock(connection,
                    player,"QUEUE",player.toString(),rows.getLong(2));}
        }
    }

    private static boolean isTerminal(String state){return List.of("ACKED","FAILED","ABORTED").contains(state);}

    private static void bindHandoff(PreparedStatement statement, Handoff row) throws SQLException {
        statement.setString(1, row.id()); statement.setString(2, row.playerUuid().toString());
        statement.setString(3, row.sourceShard()); statement.setString(4, row.destination().shardId());
        statement.setString(5, row.destination().world()); statement.setDouble(6, row.destination().x());
        statement.setDouble(7, row.destination().y()); statement.setDouble(8, row.destination().z());
        statement.setFloat(9, row.destination().yaw()); statement.setFloat(10, row.destination().pitch());
        statement.setString(11, row.reason()); statement.setString(12, row.state()); statement.setBytes(13, row.snapshot());
        statement.setLong(14, row.createdAt()); statement.setLong(15, row.updatedAt()); statement.setString(16, row.error());
    }

    private static Handoff readHandoff(ResultSet row) throws SQLException {
        return new Handoff(row.getString(1), UUID.fromString(row.getString(2)), row.getString(3),
                new NetworkLocation(row.getString(4), row.getString(5), row.getDouble(6), row.getDouble(7),
                        row.getDouble(8), row.getFloat(9), row.getFloat(10)), row.getString(11), row.getString(12),
                row.getBytes(13), row.getLong(14), row.getLong(15), row.getString(16));
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT); }

    public record ShardRow(String shardId, String role, ShardState state, int maxPlayers,
                           int currentPlayers, long heartbeatAt, long restartEta, String updatedBy) { }
    public record EventRow(long id, String sourceShard, String topic, String payload, long createdAt) { }
    public record Handoff(String id, UUID playerUuid, String sourceShard, NetworkLocation destination,
                          String reason, String state, byte[] snapshot, long createdAt, long updatedAt,
                          String error) { }
    public record QueuedTransfer(UUID playerUuid, String sourceShard, NetworkLocation destination, String reason,
                                 long expiresAt, long createdAt) { }
    public record LocationRow(String type, String name, NetworkLocation location,
                              String description, long revision) { }
}
