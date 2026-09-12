package me.vertex.core.faction;

import me.vertex.core.factions.FactionRole;
import me.vertex.core.storage.Database;
import me.vertex.core.storage.SqlSchema;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One atomic serialized inventory per faction. */
public final class FactionVaultStorage {
    private final Database database;
    public FactionVaultStorage(Database database){this.database=database;}
    public void init()throws SQLException{try(Connection c=database.getConnection();Statement s=c.createStatement()){s.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_vaults (faction_id INT PRIMARY KEY, contents LONGBLOB NOT NULL, updated_at BIGINT NOT NULL)");s.executeUpdate("CREATE TABLE IF NOT EXISTS vertex_faction_vault_locks (faction_id INT PRIMARY KEY, viewer_uuid VARCHAR(36) NOT NULL, viewer_name VARCHAR(128) NOT NULL, lease_token VARCHAR(36) NOT NULL, lease_until BIGINT NOT NULL)");SqlSchema.ensureColumn(c,"vertex_faction_vault_locks","lease_token","VARCHAR(36) NOT NULL DEFAULT ''");s.executeUpdate("DELETE FROM vertex_faction_vault_locks WHERE lease_until<"+System.currentTimeMillis());}}
    public Map<Integer,ItemStack[]> loadAll()throws SQLException{Map<Integer,ItemStack[]> result=new LinkedHashMap<>();try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("SELECT faction_id,contents FROM vertex_faction_vaults");ResultSet rows=s.executeQuery()){while(rows.next()){try{result.put(rows.getInt(1),ItemStack.deserializeItemsFromBytes(rows.getBytes(2)));}catch(RuntimeException error){throw new SQLException("Corrupt faction vault for faction "+rows.getInt(1),error);}}}return result;}
    public ItemStack[] load(int factionId)throws SQLException{try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("SELECT contents FROM vertex_faction_vaults WHERE faction_id=?")){s.setInt(1,factionId);try(ResultSet row=s.executeQuery()){if(!row.next())return new ItemStack[54];try{return ItemStack.deserializeItemsFromBytes(row.getBytes(1));}catch(RuntimeException error){throw new SQLException("Corrupt faction vault for faction "+factionId,error);}}}}
    public void save(int factionId,ItemStack[] contents)throws SQLException{
        String sql=database.dialect()==Database.Dialect.SQLITE?"INSERT INTO vertex_faction_vaults(faction_id,contents,updated_at) VALUES(?,?,?) ON CONFLICT(faction_id) DO UPDATE SET contents=excluded.contents,updated_at=excluded.updated_at":"INSERT INTO vertex_faction_vaults(faction_id,contents,updated_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE contents=VALUES(contents),updated_at=VALUES(updated_at)";
        try(Connection c=database.getConnection()){
            boolean previous=c.getAutoCommit();c.setAutoCommit(false);
            try{
                if(!SqlSchema.lockFactionIfPresent(c,database.dialect(),factionId))throw new SQLException("Faction no longer exists");
                try(PreparedStatement s=c.prepareStatement(sql)){s.setInt(1,factionId);s.setBytes(2,ItemStack.serializeItemsAsBytes(contents));s.setLong(3,System.currentTimeMillis());s.executeUpdate();}
                c.commit();
            }catch(SQLException|RuntimeException error){c.rollback();throw error;}finally{c.setAutoCommit(previous);}
        }
    }
    public void delete(int factionId)throws SQLException{try(Connection c=database.getConnection()){boolean previous=c.getAutoCommit();c.setAutoCommit(false);try(PreparedStatement vault=c.prepareStatement("DELETE FROM vertex_faction_vaults WHERE faction_id=?");PreparedStatement lock=c.prepareStatement("DELETE FROM vertex_faction_vault_locks WHERE faction_id=?")){vault.setInt(1,factionId);vault.executeUpdate();lock.setInt(1,factionId);lock.executeUpdate();c.commit();}catch(SQLException error){c.rollback();throw error;}finally{c.setAutoCommit(previous);}}}

    /**
     * Acquires a network-wide lease and, for normal members, re-checks the
     * durable role/permission under the same faction lock. Admin callers pass
     * {@code null} for {@code expectedRole} to use the explicit /fa bypass.
     */
    public AcquireResult acquire(int factionId, UUID viewer, String viewerName, UUID token,
            long now, long leaseUntil, FactionRole expectedRole, boolean defaultAllowed) throws SQLException {
        try (Connection c = database.getConnection()) {
            boolean previous = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                if (!SqlSchema.lockFactionIfPresent(c, database.dialect(), factionId)) {
                    throw new SQLException("Faction no longer exists");
                }
                if (expectedRole != null && !authorized(c, factionId, viewer, expectedRole, defaultAllowed)) {
                    c.rollback();
                    return new AcquireResult(false, null);
                }
                String insert = database.dialect() == Database.Dialect.SQLITE
                        ? "INSERT OR IGNORE INTO vertex_faction_vault_locks(faction_id,viewer_uuid,viewer_name,lease_token,lease_until) VALUES(?,?,?,?,?)"
                        : "INSERT IGNORE INTO vertex_faction_vault_locks(faction_id,viewer_uuid,viewer_name,lease_token,lease_until) VALUES(?,?,?,?,?)";
                int changed;
                try (PreparedStatement s = c.prepareStatement(insert)) {
                    s.setInt(1, factionId); s.setString(2, viewer.toString());
                    s.setString(3, viewerName); s.setString(4, token.toString());
                    s.setLong(5, leaseUntil); changed = s.executeUpdate();
                }
                if (changed == 0) {
                    try (PreparedStatement s = c.prepareStatement(
                            "UPDATE vertex_faction_vault_locks SET viewer_uuid=?,viewer_name=?,lease_token=?,lease_until=? WHERE faction_id=? AND lease_until<=?")) {
                        s.setString(1, viewer.toString()); s.setString(2, viewerName);
                        s.setString(3, token.toString()); s.setLong(4, leaseUntil);
                        s.setInt(5, factionId); s.setLong(6, now); changed = s.executeUpdate();
                    }
                }
                Lease lease;
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT viewer_uuid,viewer_name,lease_token,lease_until FROM vertex_faction_vault_locks WHERE faction_id=?")) {
                    s.setInt(1, factionId);
                    try (ResultSet row = s.executeQuery()) {
                        if (!row.next()) throw new SQLException("Vault lease row disappeared");
                        String owner = row.getString(1);
                        String savedToken = row.getString(3);
                        lease = new Lease(owner.equals(viewer.toString()) && savedToken.equals(token.toString()),
                                UUID.fromString(owner), row.getString(2),
                                savedToken.isBlank() ? null : UUID.fromString(savedToken), row.getLong(4));
                    }
                }
                c.commit();
                return new AcquireResult(true, lease);
            } catch (SQLException | RuntimeException error) {
                c.rollback();
                throw error;
            } finally {
                c.setAutoCommit(previous);
            }
        }
    }

    private boolean authorized(Connection connection, int factionId, UUID viewer,
            FactionRole expectedRole, boolean defaultAllowed) throws SQLException {
        String suffix = database.dialect() == Database.Dialect.MYSQL ? " FOR UPDATE" : "";
        FactionRole durableRole;
        try (PreparedStatement member = connection.prepareStatement(
                "SELECT role FROM vertex_faction_members WHERE player_uuid=? AND faction_id=?" + suffix)) {
            member.setString(1, viewer.toString());
            member.setInt(2, factionId);
            try (ResultSet row = member.executeQuery()) {
                if (!row.next()) return false;
                durableRole = FactionRole.parse(row.getString(1), FactionRole.RECRUIT);
            }
        }
        if (durableRole != expectedRole) return false;
        if (durableRole == FactionRole.LEADER) return true;
        try (PreparedStatement permission = connection.prepareStatement(
                "SELECT allowed FROM vertex_faction_permissions WHERE faction_id=? AND role=? AND action_key='vault-use'")) {
            permission.setInt(1, factionId);
            permission.setString(2, durableRole.permissionBucket());
            try (ResultSet row = permission.executeQuery()) {
                return row.next() ? row.getBoolean(1) : defaultAllowed;
            }
        }
    }
    public boolean renew(int factionId,UUID viewer,UUID token,long leaseUntil)throws SQLException{try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("UPDATE vertex_faction_vault_locks SET lease_until=? WHERE faction_id=? AND viewer_uuid=? AND lease_token=?")){s.setLong(1,leaseUntil);s.setInt(2,factionId);s.setString(3,viewer.toString());s.setString(4,token.toString());return s.executeUpdate()==1;}}
    public void release(int factionId,UUID viewer,UUID token)throws SQLException{try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("DELETE FROM vertex_faction_vault_locks WHERE faction_id=? AND viewer_uuid=? AND lease_token=?")){s.setInt(1,factionId);s.setString(2,viewer.toString());s.setString(3,token.toString());s.executeUpdate();}}
    /** Revokes the current token but retains a short safety lease while the old GUI closes/saves. */
    public void revoke(int factionId,long safeUntil)throws SQLException{try(Connection c=database.getConnection();PreparedStatement s=c.prepareStatement("UPDATE vertex_faction_vault_locks SET viewer_name=?,lease_token=?,lease_until=? WHERE faction_id=?")){s.setString(1,"staff override pending");s.setString(2,UUID.randomUUID().toString());s.setLong(3,safeUntil);s.setInt(4,factionId);s.executeUpdate();}}
    public record AcquireResult(boolean authorized, Lease lease) {}
    public record Lease(boolean acquired,UUID viewer,String viewerName,UUID token,long leaseUntil){}
}
