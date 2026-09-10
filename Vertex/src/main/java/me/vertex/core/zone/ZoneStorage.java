package me.vertex.core.zone;

import me.vertex.core.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** SQL backing for durable zone structure, player progression, loot, and event state. */
public final class ZoneStorage {
    private final Database database;

    public ZoneStorage(Database database) {
        this.database = database;
    }

    public void init() throws SQLException {
        try (Connection c = database.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_regions (id VARCHAR(64) PRIMARY KEY, type VARCHAR(16) NOT NULL, world VARCHAR(128) NOT NULL, min_x INT NOT NULL, min_y INT NOT NULL, min_z INT NOT NULL, max_x INT NOT NULL, max_y INT NOT NULL, max_z INT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_routes (id VARCHAR(64) PRIMARY KEY, region_id VARCHAR(64) NOT NULL, enabled BOOLEAN NOT NULL, speed DOUBLE NOT NULL, auto_drop BOOLEAN NOT NULL, waypoints TEXT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_loot (zone_type VARCHAR(16) NOT NULL, position INT NOT NULL, item BLOB NOT NULL, chance DOUBLE NOT NULL, PRIMARY KEY (zone_type, position))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_players (player_uuid VARCHAR(36) PRIMARY KEY, last_name VARCHAR(32) NOT NULL, haven_kills BIGINT NOT NULL, riftlands_kills BIGINT NOT NULL, haven_cooldown_until BIGINT NOT NULL, riftlands_cooldown_until BIGINT NOT NULL, rift_session_id VARCHAR(64), winner_boost DOUBLE NOT NULL, winner_cycle BIGINT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_event_scores (event_start BIGINT NOT NULL, player_uuid VARCHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, score DOUBLE NOT NULL, reached_at BIGINT NOT NULL, PRIMARY KEY (event_start, player_uuid))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_event_state (state_key VARCHAR(32) PRIMARY KEY, value BIGINT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS zone_flight_returns (player_uuid VARCHAR(36) PRIMARY KEY, zone_region VARCHAR(64) NOT NULL, world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL)");
        }
    }

    public List<ZoneRegion> loadRegions() throws SQLException {
        List<ZoneRegion> result = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,type,world,min_x,min_y,min_z,max_x,max_y,max_z FROM zone_regions"); ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                try { result.add(new ZoneRegion(rs.getString(1), ZoneType.valueOf(rs.getString(2)), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9))); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        return result;
    }

    public void upsertRegion(ZoneRegion r) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO zone_regions (id,type,world,min_x,min_y,min_z,max_x,max_y,max_z) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET type=excluded.type,world=excluded.world,min_x=excluded.min_x,min_y=excluded.min_y,min_z=excluded.min_z,max_x=excluded.max_x,max_y=excluded.max_y,max_z=excluded.max_z"
                : "INSERT INTO zone_regions (id,type,world,min_x,min_y,min_z,max_x,max_y,max_z) VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE type=VALUES(type),world=VALUES(world),min_x=VALUES(min_x),min_y=VALUES(min_y),min_z=VALUES(min_z),max_x=VALUES(max_x),max_y=VALUES(max_y),max_z=VALUES(max_z)";
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, r.id()); s.setString(2, r.type().name()); s.setString(3, r.world()); s.setInt(4, r.minX()); s.setInt(5, r.minY()); s.setInt(6, r.minZ()); s.setInt(7, r.maxX()); s.setInt(8, r.maxY()); s.setInt(9, r.maxZ()); s.executeUpdate();
        }
    }

    public void deleteRegion(String id) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement route = c.prepareStatement("DELETE FROM zone_routes WHERE region_id=?"); PreparedStatement region = c.prepareStatement("DELETE FROM zone_regions WHERE id=?")) {
            c.setAutoCommit(false); route.setString(1, id); route.executeUpdate(); region.setString(1, id); region.executeUpdate(); c.commit();
        }
    }

    public List<RouteRow> loadRoutes() throws SQLException {
        List<RouteRow> result = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement("SELECT id,region_id,enabled,speed,auto_drop,waypoints FROM zone_routes"); ResultSet rs = s.executeQuery()) {
            while (rs.next()) result.add(new RouteRow(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getDouble(4), rs.getBoolean(5), rs.getString(6)));
        }
        return result;
    }
    public record RouteRow(String id, String regionId, boolean enabled, double speed, boolean autoDrop, String waypoints) { }
    public void upsertRoute(ZoneRoute route, String encoded) throws SQLException {
        String sql = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO zone_routes (id,region_id,enabled,speed,auto_drop,waypoints) VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET region_id=excluded.region_id,enabled=excluded.enabled,speed=excluded.speed,auto_drop=excluded.auto_drop,waypoints=excluded.waypoints"
                : "INSERT INTO zone_routes (id,region_id,enabled,speed,auto_drop,waypoints) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE region_id=VALUES(region_id),enabled=VALUES(enabled),speed=VALUES(speed),auto_drop=VALUES(auto_drop),waypoints=VALUES(waypoints)";
        try (Connection c = database.getConnection(); PreparedStatement s = c.prepareStatement(sql)) { s.setString(1, route.id()); s.setString(2, route.regionId()); s.setBoolean(3, route.enabled()); s.setDouble(4, route.speed()); s.setBoolean(5, route.autoDrop()); s.setString(6, encoded); s.executeUpdate(); }
    }
    public void deleteRoute(String id) throws SQLException { try (Connection c=database.getConnection(); PreparedStatement s=c.prepareStatement("DELETE FROM zone_routes WHERE id=?")) { s.setString(1,id); s.executeUpdate(); } }

    public List<LootRow> loadLoot(ZoneType type) throws SQLException {
        List<LootRow> result=new ArrayList<>(); try(Connection c=database.getConnection(); PreparedStatement s=c.prepareStatement("SELECT position,item,chance FROM zone_loot WHERE zone_type=? ORDER BY position")){s.setString(1,type.name());try(ResultSet rs=s.executeQuery()){while(rs.next()) result.add(new LootRow(rs.getInt(1),rs.getBytes(2),rs.getDouble(3)));}} return result;
    }
    public record LootRow(int position, byte[] item, double chance) { }
    public void replaceLoot(ZoneType type, List<LootRow> rows) throws SQLException {
        try(Connection c=database.getConnection(); PreparedStatement delete=c.prepareStatement("DELETE FROM zone_loot WHERE zone_type=?"); PreparedStatement insert=c.prepareStatement("INSERT INTO zone_loot (zone_type,position,item,chance) VALUES (?,?,?,?)")) { c.setAutoCommit(false); delete.setString(1,type.name()); delete.executeUpdate(); for(LootRow row:rows){insert.setString(1,type.name());insert.setInt(2,row.position());insert.setBytes(3,row.item());insert.setDouble(4,row.chance());insert.addBatch();} insert.executeBatch(); c.commit(); }
    }

    public PlayerRow loadPlayer(UUID uuid) throws SQLException { try(Connection c=database.getConnection(); PreparedStatement s=c.prepareStatement("SELECT last_name,haven_kills,riftlands_kills,haven_cooldown_until,riftlands_cooldown_until,rift_session_id,winner_boost,winner_cycle FROM zone_players WHERE player_uuid=?")){s.setString(1,uuid.toString());try(ResultSet rs=s.executeQuery()){return rs.next()?new PlayerRow(uuid,rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getString(6),rs.getDouble(7),rs.getLong(8)):null;}} }
    public record PlayerRow(UUID uuid,String name,long havenKills,long riftKills,long havenCooldown,long riftCooldown,String sessionId,double winnerBoost,long winnerCycle) { }
    public void upsertPlayer(PlayerRow row) throws SQLException {
        String sql=database.dialect()==Database.Dialect.SQLITE ? "INSERT INTO zone_players (player_uuid,last_name,haven_kills,riftlands_kills,haven_cooldown_until,riftlands_cooldown_until,rift_session_id,winner_boost,winner_cycle) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET last_name=excluded.last_name,haven_kills=excluded.haven_kills,riftlands_kills=excluded.riftlands_kills,haven_cooldown_until=excluded.haven_cooldown_until,riftlands_cooldown_until=excluded.riftlands_cooldown_until,rift_session_id=excluded.rift_session_id,winner_boost=excluded.winner_boost,winner_cycle=excluded.winner_cycle" : "INSERT INTO zone_players (player_uuid,last_name,haven_kills,riftlands_kills,haven_cooldown_until,riftlands_cooldown_until,rift_session_id,winner_boost,winner_cycle) VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE last_name=VALUES(last_name),haven_kills=VALUES(haven_kills),riftlands_kills=VALUES(riftlands_kills),haven_cooldown_until=VALUES(haven_cooldown_until),riftlands_cooldown_until=VALUES(riftlands_cooldown_until),rift_session_id=VALUES(rift_session_id),winner_boost=VALUES(winner_boost),winner_cycle=VALUES(winner_cycle)";
        try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,row.uuid().toString());s.setString(2,row.name());s.setLong(3,row.havenKills());s.setLong(4,row.riftKills());s.setLong(5,row.havenCooldown());s.setLong(6,row.riftCooldown());s.setString(7,row.sessionId());s.setDouble(8,row.winnerBoost());s.setLong(9,row.winnerCycle());s.executeUpdate();}
    }
    public void resetSeason() throws SQLException { try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("UPDATE zone_players SET haven_kills=0,riftlands_kills=0,haven_cooldown_until=0,riftlands_cooldown_until=0,rift_session_id=NULL")){s.executeUpdate();} }

    public long loadLong(String key,long fallback) throws SQLException { try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("SELECT value FROM zone_event_state WHERE state_key=?")){s.setString(1,key);try(ResultSet rs=s.executeQuery()){return rs.next()?rs.getLong(1):fallback;}} }
    public void saveLong(String key,long value) throws SQLException { String sql=database.dialect()==Database.Dialect.SQLITE ? "INSERT INTO zone_event_state (state_key,value) VALUES (?,?) ON CONFLICT(state_key) DO UPDATE SET value=excluded.value" : "INSERT INTO zone_event_state (state_key,value) VALUES (?,?) ON DUPLICATE KEY UPDATE value=VALUES(value)"; try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,key);s.setLong(2,value);s.executeUpdate();} }
    public List<ScoreRow> loadScores(long eventStart) throws SQLException { List<ScoreRow> result=new ArrayList<>();try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("SELECT player_uuid,player_name,score,reached_at FROM zone_event_scores WHERE event_start=?")){s.setLong(1,eventStart);try(ResultSet rs=s.executeQuery()){while(rs.next())result.add(new ScoreRow(UUID.fromString(rs.getString(1)),rs.getString(2),rs.getDouble(3),rs.getLong(4)));}}return result; }
    public record ScoreRow(UUID uuid,String name,double score,long reachedAt) { }
    public void upsertScore(long start,ScoreRow row) throws SQLException {String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO zone_event_scores (event_start,player_uuid,player_name,score,reached_at) VALUES (?,?,?,?,?) ON CONFLICT(event_start,player_uuid) DO UPDATE SET player_name=excluded.player_name,score=excluded.score,reached_at=excluded.reached_at":"INSERT INTO zone_event_scores (event_start,player_uuid,player_name,score,reached_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE player_name=VALUES(player_name),score=VALUES(score),reached_at=VALUES(reached_at)";try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setLong(1,start);s.setString(2,row.uuid().toString());s.setString(3,row.name());s.setDouble(4,row.score());s.setLong(5,row.reachedAt());s.executeUpdate();}}
    /** Clears all prior winner boosts and writes the new Top 3 in one transaction. */
    public void replaceWinnerBoosts(long cycle, List<WinnerRow> winners) throws SQLException {
        String upsert = database.dialect() == Database.Dialect.SQLITE
                ? "INSERT INTO zone_players (player_uuid,last_name,haven_kills,riftlands_kills,haven_cooldown_until,riftlands_cooldown_until,rift_session_id,winner_boost,winner_cycle) VALUES (?,?,0,0,0,0,NULL,?,?) ON CONFLICT(player_uuid) DO UPDATE SET last_name=excluded.last_name,winner_boost=excluded.winner_boost,winner_cycle=excluded.winner_cycle"
                : "INSERT INTO zone_players (player_uuid,last_name,haven_kills,riftlands_kills,haven_cooldown_until,riftlands_cooldown_until,rift_session_id,winner_boost,winner_cycle) VALUES (?,?,0,0,0,0,NULL,?,?) ON DUPLICATE KEY UPDATE last_name=VALUES(last_name),winner_boost=VALUES(winner_boost),winner_cycle=VALUES(winner_cycle)";
        try (Connection c=database.getConnection(); PreparedStatement clear=c.prepareStatement("UPDATE zone_players SET winner_boost=0,winner_cycle=?"); PreparedStatement insert=c.prepareStatement(upsert)) {
            c.setAutoCommit(false); clear.setLong(1,cycle); clear.executeUpdate();
            for (WinnerRow winner : winners) { insert.setString(1,winner.uuid().toString()); insert.setString(2,winner.name()); insert.setDouble(3,winner.boost()); insert.setLong(4,cycle); insert.addBatch(); }
            insert.executeBatch(); c.commit();
        }
    }
    public record WinnerRow(UUID uuid, String name, double boost) { }
    public void saveFlightReturn(UUID uuid,String region,String world,double x,double y,double z)throws SQLException {String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO zone_flight_returns (player_uuid,zone_region,world,x,y,z) VALUES (?,?,?,?,?,?) ON CONFLICT(player_uuid) DO UPDATE SET zone_region=excluded.zone_region,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z":"INSERT INTO zone_flight_returns (player_uuid,zone_region,world,x,y,z) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE zone_region=VALUES(zone_region),world=VALUES(world),x=VALUES(x),y=VALUES(y),z=VALUES(z)";try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,uuid.toString());s.setString(2,region);s.setString(3,world);s.setDouble(4,x);s.setDouble(5,y);s.setDouble(6,z);s.executeUpdate();}}
    public FlightReturn takeFlightReturn(UUID uuid)throws SQLException {try(Connection c=database.getConnection();PreparedStatement select=c.prepareStatement("SELECT zone_region,world,x,y,z FROM zone_flight_returns WHERE player_uuid=?");PreparedStatement delete=c.prepareStatement("DELETE FROM zone_flight_returns WHERE player_uuid=?")){c.setAutoCommit(false);select.setString(1,uuid.toString());FlightReturn result=null;try(ResultSet rs=select.executeQuery()){if(rs.next())result=new FlightReturn(rs.getString(1),rs.getString(2),rs.getDouble(3),rs.getDouble(4),rs.getDouble(5));}delete.setString(1,uuid.toString());delete.executeUpdate();c.commit();return result;}}
    public record FlightReturn(String region,String world,double x,double y,double z) { }
}
