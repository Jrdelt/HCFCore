package me.vertex.core.storage;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Persists overflow before attempting restart-safe tagged inventory delivery. */
public final class DeliveryManager implements Listener {
    private final Plugin plugin;private final DeliveryStorage storage;private final Messages messages;
    private final DeliveryWal wal;
    private final java.util.Set<UUID> active=ConcurrentHashMap.newKeySet();
    private final java.util.Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final java.util.Queue<PendingEnqueue> unsaved=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Set<UUID> retryOwners=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean admissionHealthy=new AtomicBoolean(true);
    private BukkitTask retryTask;
    public DeliveryManager(Plugin plugin,Database database,Messages messages){this.plugin=plugin;this.storage=new DeliveryStorage(database);this.messages=messages;this.wal=new DeliveryWal(plugin.getDataFolder());}
    public void init()throws Exception{
        storage.init();
        for(DeliveryWal.WalBatch recovered:wal.load()){
            PendingEnqueue batch=new PendingEnqueue(recovered.batchId(),recovered.owner(),recovered.items(),
                    recovered.source(),new CompletableFuture<>(),new AtomicBoolean());
            unsaved.add(batch);persist(batch);
        }
        retryTask=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            for(PendingEnqueue batch:unsaved)persist(batch);
            for(UUID owner:List.copyOf(retryOwners)){
                Player player=Bukkit.getPlayer(owner);
                if(player!=null&&player.isOnline())deliver(player);else retryOwners.remove(owner);
            }
        },100L,100L);
    }

    /**
     * Synchronously admits overflow to either the local WAL or SQL before
     * returning. Callers that removed a source item must treat {@code false}
     * as a failed transaction and restore/retain that source.
     */
    public static boolean queueOverflow(Plugin plugin,Player player,Collection<ItemStack> items,String source){
        if(items==null||items.isEmpty())return true;
        if(plugin instanceof me.vertex.core.VertexPlugin vertex&&vertex.deliveryManager()!=null){
            return vertex.deliveryManager().admit(player.getUniqueId(),items,source);
        }
        plugin.getLogger().severe("Durable delivery inbox is unavailable for "+source+"; retaining overflow in memory was impossible.");
        return false;
    }

    public CompletableFuture<Boolean> queue(Player player,Collection<ItemStack> items,String source){return queue(player.getUniqueId(),items,source);}
    public CompletableFuture<Boolean> queue(UUID owner,Collection<ItemStack> items,String source){
        Admission admission=enqueue(owner,items,source);
        return admission.completion();
    }

    /** Immediate admission result; SQL persistence may continue asynchronously after a successful WAL write. */
    public boolean admit(UUID owner,Collection<ItemStack> items,String source){
        return enqueue(owner,items,source).accepted();
    }

    public boolean admissionHealthy(){return admissionHealthy.get();}

    private Admission enqueue(UUID owner,Collection<ItemStack> items,String source){
        List<ItemStack> copies=items==null?List.of():items.stream().filter(item->item!=null&&!item.isEmpty()).map(ItemStack::clone).toList();
        if(copies.isEmpty())return new Admission(true,CompletableFuture.completedFuture(true));
        List<DeliveryStorage.PreparedDelivery> prepared=DeliveryStorage.prepare(copies);
        String batchId=UUID.randomUUID().toString();
        PendingEnqueue batch=new PendingEnqueue(batchId,owner,prepared,source,new CompletableFuture<>(),new AtomicBoolean());
        try{
            wal.put(new DeliveryWal.WalBatch(batchId,owner,prepared,source));
            admissionHealthy.set(true);
        }catch(Exception error){
            // A read-only/full plugin directory must not lose an item while
            // the SQL backend is still healthy. Fall back to a synchronous,
            // idempotent SQL admission using the same stable delivery IDs.
            plugin.getLogger().log(Level.WARNING,"Could not write the local item-delivery WAL for "+owner
                    +" from "+source+"; attempting direct SQL admission.",error);
            try{
                storage.enqueuePrepared(owner,prepared,source);
                batch.result().complete(true);retryOwners.add(owner);
                if(plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,()->{
                    Player player=Bukkit.getPlayer(owner);if(player!=null&&player.isOnline())deliver(player);
                });
                return new Admission(true,batch.result());
            }catch(Exception sqlError){
                admissionHealthy.set(false);
                plugin.getLogger().log(Level.SEVERE,"Both WAL and SQL delivery admission failed for "+owner
                        +" from "+source+"; the source transaction must remain unchanged.",sqlError);
                batch.result().complete(false);return new Admission(false,batch.result());
            }
        }
        unsaved.add(batch);persist(batch);return new Admission(true,batch.result());
    }

    private void persist(PendingEnqueue batch){
        if(!batch.writing().compareAndSet(false,true))return;
        CompletableFuture<Boolean> write=CompletableFuture.supplyAsync(()->{
            try{
                storage.enqueuePrepared(batch.owner(),batch.items(),batch.source());
                // Completed SQL IDs are retained, so another shard may deliver
                // safely even if local WAL removal fails and is later replayed.
                wal.remove(batch.batchId());
                return true;
            }
            catch(Exception error){plugin.getLogger().log(Level.SEVERE,"Could not persist item delivery for "
                    +batch.owner()+" from "+batch.source()+"; it remains queued for retry.",error);return false;}
        });track(write);
        write.whenComplete((saved,error)->{
            batch.writing().set(false);
            if(!Boolean.TRUE.equals(saved))return;
            unsaved.remove(batch);batch.result().complete(true);retryOwners.add(batch.owner());
            if(plugin.isEnabled())Bukkit.getScheduler().runTask(plugin,()->{
                Player player=Bukkit.getPlayer(batch.owner());if(player!=null&&player.isOnline())deliver(player);
            });
        });
    }

    public void deliver(Player player){
        UUID owner=player.getUniqueId();if(!active.add(owner))return;
        CompletableFuture<Batch> load=CompletableFuture.supplyAsync(()->{try{List<DeliveryStorage.Reservation> rows=new ArrayList<>(storage.delivering(owner));DeliveryStorage.Reservation fresh=storage.reserve(owner);if(!fresh.rows().isEmpty())rows.add(fresh);return new Batch(rows,fresh.rows().isEmpty()?null:fresh.token());}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}});track(load);
        load.whenComplete((batch,error)->Bukkit.getScheduler().runTask(plugin,()->{
            if(error!=null){plugin.getLogger().log(Level.WARNING,"Could not load item deliveries for "+owner,error);retryOwners.add(owner);active.remove(owner);return;}
            // A reservation may contain more than an inventory can ever hold.
            // Acknowledge rows separately so the player can clear space and resume.
            List<DeliveryStorage.Reservation> individual=new ArrayList<>();
            for(DeliveryStorage.Reservation reservation:batch.reservations())for(DeliveryStorage.Row row:reservation.rows())
                individual.add(new DeliveryStorage.Reservation(reservation.token(),List.of(row)));
            deliverAt(player,new Batch(individual,batch.freshToken()),0);
        }));
    }

    private void deliverAt(Player player,Batch batch,int index){
        if(!player.isOnline()){releaseFresh(player.getUniqueId(),batch);active.remove(player.getUniqueId());return;}
        if(index>=batch.reservations().size()){
            ClaimDelivery.clearSourceMarkers(player,plugin,"delivery");active.remove(player.getUniqueId());
            return;
        }
        DeliveryStorage.Reservation reservation=batch.reservations().get(index);List<ClaimDelivery.TaggedItem> expected=new ArrayList<>();
        for(DeliveryStorage.Row row:reservation.rows())expected.add(ClaimDelivery.tagged(plugin,"delivery",reservation.token(),row.id(),0,row.item()));
        List<ClaimDelivery.TaggedItem> missing=ClaimDelivery.missing(player,plugin,expected);
        if(!ClaimDelivery.canFit(player,missing)||!ClaimDelivery.add(player,missing)){
            // Retain the same reservation/markers until the player makes room.
            // Dropping here would make recovery unable to locate the items.
            retryOwners.add(player.getUniqueId());active.remove(player.getUniqueId());
            return;
        }
        CompletableFuture<Integer> complete=CompletableFuture.supplyAsync(()->{try{return storage.complete(player.getUniqueId(),reservation.token(),reservation.rows().getFirst().id());}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}});track(complete);
        complete.whenComplete((changed,error)->Bukkit.getScheduler().runTask(plugin,()->{
            if(error!=null||changed==null||changed!=reservation.rows().size()){plugin.getLogger().log(Level.SEVERE,"Could not acknowledge item delivery "+reservation.token(),error);retryOwners.add(player.getUniqueId());active.remove(player.getUniqueId());return;}
            ClaimDelivery.clearMarker(player,plugin,expected.getFirst().marker());deliverAt(player,batch,index+1);
        }));
    }

    private void releaseFresh(UUID owner,Batch batch){if(batch.freshToken()==null)return;CompletableFuture<Void> release=CompletableFuture.runAsync(()->{try{storage.release(owner,batch.freshToken());}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}});track(release);}
    @EventHandler public void onJoin(PlayerJoinEvent event){retryOwners.add(event.getPlayer().getUniqueId());Bukkit.getScheduler().runTaskLater(plugin,()->deliver(event.getPlayer()),20L);}
    private void track(CompletableFuture<?> future){pending.add(future);future.whenComplete((ignored,error)->pending.remove(future));}
    public void awaitWrites(){
        if(retryTask!=null)retryTask.cancel();
        drainWrites();
    }
    /** Flushes current delivery work without disabling the live retry scheduler. */
    public void drainWrites(){
        for(PendingEnqueue batch:unsaved)persist(batch);
        try{CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).get(10,TimeUnit.SECONDS);}catch(Exception error){plugin.getLogger().log(Level.WARNING,"Timed out waiting for item delivery writes.",error);}
        if(!unsaved.isEmpty())plugin.getLogger().severe(unsaved.size()+" item delivery batch(es) could not be persisted before shutdown.");
    }
    private record Batch(List<DeliveryStorage.Reservation> reservations,String freshToken){}
    private record Admission(boolean accepted,CompletableFuture<Boolean> completion){}
    private record PendingEnqueue(String batchId,UUID owner,List<DeliveryStorage.PreparedDelivery> items,String source,
            CompletableFuture<Boolean> result,AtomicBoolean writing){}
}
