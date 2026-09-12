package me.vertex.core.storage;

import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Durable overflow inbox shared by paid, generated, and staff-granted items. */
public final class DeliveryStorage {
    private final Database database;

    public DeliveryStorage(Database database){this.database=database;}

    public void init()throws SQLException{
        try(Connection connection=database.getConnection();var statement=connection.createStatement()){
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS item_delivery_inbox (delivery_id VARCHAR(36) PRIMARY KEY, owner_uuid VARCHAR(36) NOT NULL, source VARCHAR(64) NOT NULL, item LONGBLOB NOT NULL, state VARCHAR(16) NOT NULL, reservation_token VARCHAR(36), created_at BIGINT NOT NULL)");
            SqlSchema.ensureIndex(connection,"item_delivery_inbox","idx_delivery_owner_state",false,"owner_uuid","state","reservation_token");
        }
    }

    public void enqueue(UUID owner,Collection<ItemStack> items,String source)throws SQLException{
        enqueuePrepared(owner,prepare(items),source);
    }

    /** Every new inbox row fits one normal item stack. Stable IDs are retained by the WAL. */
    public static List<PreparedDelivery> prepare(Collection<ItemStack> items){
        List<PreparedDelivery> prepared=new ArrayList<>();
        if(items==null)return prepared;
        for(ItemStack item:items)if(item!=null&&!item.isEmpty()){
            int remaining=item.getAmount(),max=Math.max(1,item.getMaxStackSize());
            while(remaining>0){ItemStack part=item.clone();part.setAmount(Math.min(remaining,max));
                prepared.add(new PreparedDelivery(UUID.randomUUID().toString(),part));remaining-=part.getAmount();}
        }
        return List.copyOf(prepared);
    }

    /** Retry-safe enqueue: callers retain the generated ids until the transaction is known to have committed. */
    public void enqueuePrepared(UUID owner,Collection<PreparedDelivery> items,String source)throws SQLException{
        if(items==null||items.isEmpty())return;
        try(Connection connection=database.getConnection();PreparedStatement insert=connection.prepareStatement(
                "INSERT INTO item_delivery_inbox (delivery_id,owner_uuid,source,item,state,reservation_token,created_at) VALUES (?,?,?,?, 'READY',NULL,?)");
            PreparedStatement existing=connection.prepareStatement(
                    "SELECT owner_uuid FROM item_delivery_inbox WHERE delivery_id=?")){
            boolean autoCommit=connection.getAutoCommit();connection.setAutoCommit(false);
            try{
                long now=System.currentTimeMillis();
                for(PreparedDelivery prepared:items){
                    existing.setString(1,prepared.id());
                    try(ResultSet row=existing.executeQuery()){
                        if(row.next()){
                            if(!owner.toString().equals(row.getString(1)))throw new SQLException(
                                    "Delivery id collision for "+prepared.id());
                            continue;
                        }
                    }
                    insert.setString(1,prepared.id());insert.setString(2,owner.toString());
                    insert.setString(3,safeSource(source));insert.setBytes(4,ItemStack.serializeItemsAsBytes(
                            new ItemStack[]{prepared.item()}));insert.setLong(5,now);insert.addBatch();
                }
                insert.executeBatch();connection.commit();
            }catch(SQLException error){connection.rollback();throw error;}finally{connection.setAutoCommit(autoCommit);}
        }
    }

    public Reservation reserve(UUID owner)throws SQLException{
        String token=UUID.randomUUID().toString();
        try(Connection connection=database.getConnection()){
            boolean autoCommit=connection.getAutoCommit();connection.setAutoCommit(false);
            try(PreparedStatement update=connection.prepareStatement("UPDATE item_delivery_inbox SET state='DELIVERING',reservation_token=? WHERE owner_uuid=? AND state='READY'");
                PreparedStatement select=connection.prepareStatement("SELECT delivery_id,source,item FROM item_delivery_inbox WHERE owner_uuid=? AND state='DELIVERING' AND reservation_token=? ORDER BY created_at,delivery_id")){
                update.setString(1,token);update.setString(2,owner.toString());update.executeUpdate();
                select.setString(1,owner.toString());select.setString(2,token);List<Row> rows=read(select);connection.commit();return new Reservation(token,rows);
            }catch(SQLException error){connection.rollback();throw error;}finally{connection.setAutoCommit(autoCommit);}
        }
    }

    public List<Reservation> delivering(UUID owner)throws SQLException{
        List<Reservation> reservations=new ArrayList<>();
        try(Connection connection=database.getConnection();PreparedStatement tokens=connection.prepareStatement("SELECT DISTINCT reservation_token FROM item_delivery_inbox WHERE owner_uuid=? AND state='DELIVERING' AND reservation_token IS NOT NULL ORDER BY reservation_token")){
            tokens.setString(1,owner.toString());try(ResultSet rs=tokens.executeQuery()){while(rs.next()){
                String token=rs.getString(1);try(PreparedStatement select=connection.prepareStatement("SELECT delivery_id,source,item FROM item_delivery_inbox WHERE owner_uuid=? AND state='DELIVERING' AND reservation_token=? ORDER BY created_at,delivery_id")){
                    select.setString(1,owner.toString());select.setString(2,token);reservations.add(new Reservation(token,read(select)));
                }
            }}
        }
        return reservations;
    }

    /** Keep completed IDs as tombstones: a surviving WAL may replay after delivery. */
    public int complete(UUID owner,String token)throws SQLException{
        return complete(owner,token,null);
    }

    /** Acknowledge one stack at a time, leaving an oversized inbox collectible in parts. */
    public int complete(UUID owner,String token,String deliveryId)throws SQLException{
        try(Connection connection=database.getConnection()){
            boolean autoCommit=connection.getAutoCommit();connection.setAutoCommit(false);
            String rowFilter=deliveryId==null?"":" AND delivery_id=?";
            try(PreparedStatement update=connection.prepareStatement("UPDATE item_delivery_inbox SET state='COMPLETED' WHERE owner_uuid=? AND state='DELIVERING' AND reservation_token=?"+rowFilter);
                PreparedStatement count=connection.prepareStatement("SELECT COUNT(*) FROM item_delivery_inbox WHERE owner_uuid=? AND state='COMPLETED' AND reservation_token=?"+rowFilter)){
                update.setString(1,owner.toString());update.setString(2,token);
                count.setString(1,owner.toString());count.setString(2,token);
                if(deliveryId!=null){update.setString(3,deliveryId);count.setString(3,deliveryId);}
                update.executeUpdate();
                int completed;try(ResultSet rows=count.executeQuery()){rows.next();completed=rows.getInt(1);}
                connection.commit();return completed;
            }catch(SQLException error){connection.rollback();throw error;}finally{connection.setAutoCommit(autoCommit);}
        }
    }
    public void release(UUID owner,String token)throws SQLException{try(Connection connection=database.getConnection();PreparedStatement update=connection.prepareStatement("UPDATE item_delivery_inbox SET state='READY',reservation_token=NULL WHERE owner_uuid=? AND state='DELIVERING' AND reservation_token=?")){update.setString(1,owner.toString());update.setString(2,token);update.executeUpdate();}}

    private static List<Row> read(PreparedStatement select)throws SQLException{List<Row> rows=new ArrayList<>();try(ResultSet rs=select.executeQuery()){while(rs.next()){try{ItemStack[] decoded=ItemStack.deserializeItemsFromBytes(rs.getBytes(3));if(decoded.length!=1||decoded[0]==null||decoded[0].isEmpty())throw new IllegalArgumentException("empty payload");rows.add(new Row(rs.getString(1),rs.getString(2),decoded[0]));}catch(Exception error){throw new SQLException("Corrupt item delivery row "+rs.getString(1),error);}}}return rows;}
    private static String safeSource(String source){if(source==null||source.isBlank())return "unknown";String safe=source.replaceAll("[^A-Za-z0-9_.-]","_");return safe.substring(0,Math.min(64,safe.length()));}
    public record Row(String id,String source,ItemStack item){public Row{item=item.clone();}}
    public record Reservation(String token,List<Row> rows){public Reservation{rows=List.copyOf(rows);}}
    public record PreparedDelivery(String id,ItemStack item){public PreparedDelivery{item=item.clone();}}
}
