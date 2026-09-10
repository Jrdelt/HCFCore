package me.vertex.core.portal;

import me.vertex.core.storage.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Durable portal/route definitions. Runtime flight state intentionally does not survive a restart. */
public final class PortalStorage {
    private final Database database;
    public PortalStorage(Database database) { this.database=database; }
    public void init() throws SQLException { try(Connection c=database.getConnection();var s=c.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS entry_portals (id VARCHAR(64) PRIMARY KEY,target VARCHAR(96) NOT NULL,world VARCHAR(128) NOT NULL,min_x INT NOT NULL,min_y INT NOT NULL,min_z INT NOT NULL,max_x INT NOT NULL,max_y INT NOT NULL,max_z INT NOT NULL)");s.executeUpdate("CREATE TABLE IF NOT EXISTS entry_portal_routes (id VARCHAR(64) PRIMARY KEY,target VARCHAR(96) NOT NULL,speed DOUBLE NOT NULL,waypoints TEXT NOT NULL)");} }
    public List<EntryPortal> loadPortals() throws SQLException {List<EntryPortal> out=new ArrayList<>();try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("SELECT id,target,world,min_x,min_y,min_z,max_x,max_y,max_z FROM entry_portals");ResultSet rs=s.executeQuery()){while(rs.next()){PortalTarget target=PortalTarget.fromStorage(rs.getString(2));if(target!=null)out.add(new EntryPortal(rs.getString(1),target,rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getInt(9)));}}return out;}
    public List<RouteRow> loadRoutes() throws SQLException {List<RouteRow> out=new ArrayList<>();try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("SELECT id,target,speed,waypoints FROM entry_portal_routes");ResultSet rs=s.executeQuery()){while(rs.next())out.add(new RouteRow(rs.getString(1),rs.getString(2),rs.getDouble(3),rs.getString(4)));}return out;}
    public record RouteRow(String id,String target,double speed,String waypoints){}
    public void upsertPortal(EntryPortal portal)throws SQLException{String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO entry_portals (id,target,world,min_x,min_y,min_z,max_x,max_y,max_z) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET target=excluded.target,world=excluded.world,min_x=excluded.min_x,min_y=excluded.min_y,min_z=excluded.min_z,max_x=excluded.max_x,max_y=excluded.max_y,max_z=excluded.max_z":"INSERT INTO entry_portals (id,target,world,min_x,min_y,min_z,max_x,max_y,max_z) VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE target=VALUES(target),world=VALUES(world),min_x=VALUES(min_x),min_y=VALUES(min_y),min_z=VALUES(min_z),max_x=VALUES(max_x),max_y=VALUES(max_y),max_z=VALUES(max_z)";try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,portal.id());s.setString(2,portal.target().storageKey());s.setString(3,portal.world());s.setInt(4,portal.minX());s.setInt(5,portal.minY());s.setInt(6,portal.minZ());s.setInt(7,portal.maxX());s.setInt(8,portal.maxY());s.setInt(9,portal.maxZ());s.executeUpdate();}}
    public void upsertRoute(PortalRoute route,String encoded)throws SQLException{String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO entry_portal_routes (id,target,speed,waypoints) VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET target=excluded.target,speed=excluded.speed,waypoints=excluded.waypoints":"INSERT INTO entry_portal_routes (id,target,speed,waypoints) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE target=VALUES(target),speed=VALUES(speed),waypoints=VALUES(waypoints)";try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement(sql)){s.setString(1,route.id());s.setString(2,route.target().storageKey());s.setDouble(3,route.speed());s.setString(4,encoded);s.executeUpdate();}}
    public void deletePortal(String id)throws SQLException{try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM entry_portals WHERE id=?")){s.setString(1,id);s.executeUpdate();}}
    public void deleteRoute(String id)throws SQLException{try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM entry_portal_routes WHERE id=?")){s.setString(1,id);s.executeUpdate();}}
}
