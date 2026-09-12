package me.vertex.core.teleport;

import me.vertex.core.network.NetworkLocation;
import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable cross-shard safe-location requests. */
public final class RtpStorage {
    private final Database database;
    public RtpStorage(Database database) { this.database = database; }

    public void init() throws SQLException {
        try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_rtp_requests (request_id VARCHAR(36) PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL, source_shard VARCHAR(64) NOT NULL, destination_shard VARCHAR(64) NOT NULL, destination_world VARCHAR(128) NOT NULL, world_size INT NOT NULL, claim_buffer INT NOT NULL, attempts INT NOT NULL, state VARCHAR(16) NOT NULL, result_x DOUBLE, result_y DOUBLE, result_z DOUBLE, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, error VARCHAR(256))");
        }
    }

    public Request create(UUID player, String source, String destination, String world, int worldSize,
            int buffer, int attempts) throws SQLException {
        long now = System.currentTimeMillis();
        Request request = new Request(UUID.randomUUID().toString(), player, source, destination, world,
                worldSize, buffer, attempts, "PENDING", null, now, now, null);
        try (Connection connection = database.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vertex_rtp_requests(request_id,player_uuid,source_shard,destination_shard,destination_world,world_size,claim_buffer,attempts,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, request.id()); statement.setString(2, player.toString()); statement.setString(3, source);
            statement.setString(4,destination); statement.setString(5,world); statement.setInt(6,worldSize);
            statement.setInt(7,buffer); statement.setInt(8,attempts); statement.setString(9,"PENDING");
            statement.setLong(10,now); statement.setLong(11,now); statement.executeUpdate();
        }
        return request;
    }

    public Optional<Request> active(UUID player, String sourceShard) throws SQLException {
        try (Connection connection=database.getConnection();PreparedStatement statement=connection.prepareStatement(
                "SELECT request_id,player_uuid,source_shard,destination_shard,destination_world,world_size,claim_buffer,attempts,state,result_x,result_y,result_z,created_at,updated_at,error FROM vertex_rtp_requests WHERE player_uuid=? AND source_shard=? ORDER BY created_at DESC LIMIT 1")) {
            statement.setString(1,player.toString());statement.setString(2,sourceShard);
            try(ResultSet result=statement.executeQuery()){return result.next()?Optional.of(read(result)):Optional.empty();}
        }
    }

    public List<Request> pendingFor(String shard, int limit) throws SQLException {
        List<Request> rows = new ArrayList<>();
        try (Connection connection=database.getConnection();PreparedStatement statement=connection.prepareStatement(
                "SELECT request_id,player_uuid,source_shard,destination_shard,destination_world,world_size,claim_buffer,attempts,state,result_x,result_y,result_z,created_at,updated_at,error FROM vertex_rtp_requests WHERE destination_shard=? AND state='PENDING' ORDER BY created_at LIMIT ?")) {
            statement.setString(1,shard);statement.setInt(2,Math.max(1,Math.min(20,limit)));
            try(ResultSet result=statement.executeQuery()){while(result.next())rows.add(read(result));}
        }
        return rows;
    }

    public List<Request> readyFrom(String shard) throws SQLException {
        List<Request> rows = new ArrayList<>();
        try (Connection connection=database.getConnection();PreparedStatement statement=connection.prepareStatement(
                "SELECT request_id,player_uuid,source_shard,destination_shard,destination_world,world_size,claim_buffer,attempts,state,result_x,result_y,result_z,created_at,updated_at,error FROM vertex_rtp_requests WHERE source_shard=? AND state IN('READY','FAILED') ORDER BY created_at")) {
            statement.setString(1,shard);try(ResultSet result=statement.executeQuery()){while(result.next())rows.add(read(result));}
        }
        return rows;
    }

    public boolean claim(String id) throws SQLException {
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(
                "UPDATE vertex_rtp_requests SET state='SEARCHING',updated_at=? WHERE request_id=? AND state='PENDING'")){
            s.setLong(1,System.currentTimeMillis());s.setString(2,id);return s.executeUpdate()==1;
        }
    }

    public void complete(String id, NetworkLocation result, String error) throws SQLException {
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(
                "UPDATE vertex_rtp_requests SET state=?,result_x=?,result_y=?,result_z=?,updated_at=?,error=? WHERE request_id=? AND state='SEARCHING'")){
            boolean success=result!=null;s.setString(1,success?"READY":"FAILED");
            if(success){s.setDouble(2,result.x());s.setDouble(3,result.y());s.setDouble(4,result.z());}
            else{s.setNull(2,java.sql.Types.DOUBLE);s.setNull(3,java.sql.Types.DOUBLE);s.setNull(4,java.sql.Types.DOUBLE);}
            s.setLong(5,System.currentTimeMillis());s.setString(6,error);s.setString(7,id);s.executeUpdate();
        }
    }

    public void delete(String id) throws SQLException {try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM vertex_rtp_requests WHERE request_id=?")){s.setString(1,id);s.executeUpdate();}}

    public void recover(long staleBefore) throws SQLException {
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("UPDATE vertex_rtp_requests SET state='PENDING' WHERE state='SEARCHING' AND updated_at<?")){s.setLong(1,staleBefore);s.executeUpdate();}
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM vertex_rtp_requests WHERE created_at<?")){s.setLong(1,staleBefore-300_000L);s.executeUpdate();}
    }

    private static Request read(ResultSet r)throws SQLException{
        Number x=(Number)r.getObject(10),y=(Number)r.getObject(11),z=(Number)r.getObject(12);
        NetworkLocation location=x==null||y==null||z==null?null:new NetworkLocation(r.getString(4),r.getString(5),x.doubleValue(),y.doubleValue(),z.doubleValue(),0F,0F);
        return new Request(r.getString(1),UUID.fromString(r.getString(2)),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),r.getInt(7),r.getInt(8),r.getString(9),location,r.getLong(13),r.getLong(14),r.getString(15));
    }
    public record Request(String id,UUID player,String sourceShard,String destinationShard,String world,
                          int worldSize,int claimBuffer,int attempts,String state,NetworkLocation result,
                          long createdAt,long updatedAt,String error){}
}
