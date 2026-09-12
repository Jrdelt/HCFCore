package me.vertex.core.teleport;

import me.vertex.core.claims.ChunkKey;
import me.vertex.core.factions.FactionService;
import me.vertex.core.lang.Messages;
import me.vertex.core.network.NetworkLocation;
import me.vertex.core.network.NetworkManager;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** GUI RTP with destination-shard safe-location discovery and final revalidation. */
public final class RtpManager implements Listener {
    private static final List<Integer> CONTENT = List.of(10,11,12,13,14,15,16,19,20,21,22,23,24,25,
            28,29,30,31,32,33,34,37,38,39,40,41,42,43);
    private final Plugin plugin;
    private final NetworkManager network;
    private final TeleportManager teleports;
    private final FactionService factions;
    private final RtpStorage storage;
    private final Messages messages;
    private final Map<String,Destination> destinations = new LinkedHashMap<>();
    private final Set<String> delivering = ConcurrentHashMap.newKeySet();
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();
    private BukkitTask task;
    private int attempts;
    private int claimBuffer;
    private int countdown;
    private long cooldownMillis;
    private long retryMillis;

    public RtpManager(Plugin plugin, NetworkManager network, TeleportManager teleports,
            FactionService factions, RtpStorage storage, Messages messages) {
        this.plugin=plugin;this.network=network;this.teleports=teleports;this.factions=factions;
        this.storage=storage;this.messages=messages;
    }

    public void load() throws Exception {
        storage.init();
        storage.recover(System.currentTimeMillis()-60_000L);
        attempts=bounded(plugin.getConfig().getInt("teleports.rtp.attempts",5),1,100);
        claimBuffer=bounded(plugin.getConfig().getInt("teleports.rtp.claim-buffer-chunks",5),0,100);
        countdown=bounded(plugin.getConfig().getInt("teleports.rtp.countdown-seconds",5),0,60);
        cooldownMillis=Math.max(0L,plugin.getConfig().getLong("teleports.rtp.cooldown-seconds",300L)*1_000L);
        retryMillis=Math.max(1_000L,plugin.getConfig().getLong("teleports.rtp.retry-delay-seconds",5L)*1_000L);
        destinations.clear();
        ConfigurationSection section=plugin.getConfig().getConfigurationSection("teleports.rtp.destinations");
        if(section!=null)for(String id:section.getKeys(false)){
            String path="teleports.rtp.destinations."+id+".";
            String shard=plugin.getConfig().getString(path+"shard",network.shardId());
            if (shard == null || shard.equalsIgnoreCase("local")) shard = network.shardId();
            String world=plugin.getConfig().getString(path+"world","world");
            int size=Math.max(128,plugin.getConfig().getInt(path+"world-size",20_000));
            String display=plugin.getConfig().getString(path+"display",id);
            Material icon=Material.matchMaterial(plugin.getConfig().getString(path+"icon","GRASS_BLOCK"));
            destinations.put(id.toLowerCase(Locale.ROOT),new Destination(id,shard.toLowerCase(Locale.ROOT),world,size,
                    display,icon==null?Material.GRASS_BLOCK:icon));
        }
        network.registerArrivalHandler("rtp", (player, ignored) -> network.saveCooldown(player.getUniqueId(),
                "rtp", System.currentTimeMillis()+cooldownMillis));
        network.registerDestinationRepair("rtp", (player, invalid, result) -> {
            Destination destination = destinations.values().stream().filter(d ->
                    d.shard().equalsIgnoreCase(invalid.shardId()) && d.world().equals(invalid.world()))
                    .findFirst().orElse(null);
            if (destination == null) {
                failed(player);
                result.accept(null);
                return;
            }
            search(destination, claimBuffer, attempts, replacement -> {
                if (replacement == null) failed(player);
                result.accept(replacement);
            });
        });
    }

    public void start(){task=Bukkit.getScheduler().runTaskTimer(plugin,this::poll,20L,20L);}
    public void shutdown(){if(task!=null)task.cancel();}
    public List<Destination> destinations(){return List.copyOf(destinations.values());}

    public void open(Player player){
        Holder holder=new Holder();Inventory inventory=Bukkit.createInventory(holder,54,messages.getGui(player,"rtp.gui.title"));holder.inventory=inventory;
        ItemStack filler=item(player,Material.BLACK_STAINED_GLASS_PANE,"rtp.gui.filler",List.of());
        for(int slot=0;slot<54;slot++)inventory.setItem(slot,filler);
        int index=0;for(Destination destination:destinations.values()){
            if(index>=CONTENT.size())break;int slot=CONTENT.get(index++);holder.destinationBySlot.put(slot,destination.id());
            Map<String,String> corners=factions.cornerClaimOwners(destination.shard(),destination.world(),destination.worldSize());
            var shard=network.shard(destination.shard());
            inventory.setItem(slot,item(player,destination.icon(),"rtp.gui.entry-name",messages.getGuiList(player,"rtp.gui.entry-lore",
                    "name",destination.display(),"shard",destination.shard(),"world",destination.world(),
                    "size",String.valueOf(destination.worldSize()),"players",String.valueOf(shard==null?0:shard.currentPlayers()),
                    "positive_positive",corners.get("+X +Z"),"positive_negative",corners.get("+X -Z"),
                    "negative_negative",corners.get("-X -Z"),"negative_positive",corners.get("-X +Z"))));
        }
        player.openInventory(inventory);
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder() instanceof Holder holder))return;event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||event.getRawSlot()<0||event.getRawSlot()>=54)return;
        Destination destination=destinations.get(holder.destinationBySlot.get(event.getRawSlot()));if(destination==null)return;
        player.closeInventory();begin(player,destination);
    }

    public void begin(Player player,Destination destination){
        if(!teleports.canStart(player)){player.sendMessage(messages.get(player,"rtp.cannot-start"));return;}
        if(!searching.add(player.getUniqueId())){player.sendMessage(messages.get(player,"rtp.cannot-start"));return;}
        NetworkLocation probe=new NetworkLocation(destination.shard(),destination.world(),0,64,0,0,0);
        NetworkManager.TransferStatus status=network.destinationStatus(probe);
        if(status!=null&&status!=NetworkManager.TransferStatus.INVALID_DESTINATION){
            searching.remove(player.getUniqueId());player.sendMessage(messages.get(player,"rtp.shard-unavailable","shard",destination.shard()));return;
        }
        CompletableFuture<Long> normal=network.cooldown(player.getUniqueId(),"rtp"),retry=network.cooldown(player.getUniqueId(),"rtp-retry");
        normal.thenCombine(retry,Math::max).thenAccept(available->Bukkit.getScheduler().runTask(plugin,()->{
            if(!player.isOnline()){searching.remove(player.getUniqueId());return;}
            long remaining=available-System.currentTimeMillis();
            if(!player.hasPermission("vertex.teleport.bypass")&&remaining>0){searching.remove(player.getUniqueId());player.sendMessage(messages.get(player,"teleport.cooldown","time",me.vertex.core.grace.DurationParser.format((remaining+999)/1000)));return;}
            if(destination.shard().equalsIgnoreCase(network.shardId()))search(destination,claimBuffer,attempts,target->{
                if(target==null)failed(player);else startResolved(player,destination,target);
            });
            else requestRemote(player,destination);
        }));
    }

    private void requestRemote(Player player,Destination destination){
        CompletableFuture.supplyAsync(()->{
            try{return storage.active(player.getUniqueId(),network.shardId()).orElseGet(()->{
                try{return storage.create(player.getUniqueId(),network.shardId(),destination.shard(),destination.world(),destination.worldSize(),claimBuffer,attempts);}
                catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
            });}
            catch(Exception error){plugin.getLogger().warning("Could not create remote RTP request: "+error.getMessage());return null;}
        }).thenAccept(request->Bukkit.getScheduler().runTask(plugin,()->{
            if(request==null){searching.remove(player.getUniqueId());if(player.isOnline())failed(player);}
            else if(player.isOnline())player.sendMessage(messages.get(player,"rtp.searching"));
        }));
    }

    private void poll(){
        CompletableFuture.runAsync(()->{
            try{
                for(RtpStorage.Request request:storage.pendingFor(network.shardId(),4)){
                    if(storage.claim(request.id()))Bukkit.getScheduler().runTask(plugin,()->process(request));
                }
                for(RtpStorage.Request request:storage.readyFrom(network.shardId())){
                    if(delivering.add(request.id()))Bukkit.getScheduler().runTask(plugin,()->deliver(request));
                }
            }catch(Exception error){plugin.getLogger().warning("RTP network poll failed: "+error.getMessage());}
        });
    }

    private void process(RtpStorage.Request request){
        Destination destination=new Destination(request.destinationShard(),request.destinationShard(),request.world(),request.worldSize(),request.destinationShard(),Material.GRASS_BLOCK);
        search(destination,request.claimBuffer(),request.attempts(),target->CompletableFuture.runAsync(()->{
            try{storage.complete(request.id(),target,target==null?"no safe location":null);}
            catch(Exception error){plugin.getLogger().warning("Could not complete RTP request: "+error.getMessage());}
        }));
    }

    private void deliver(RtpStorage.Request request){
        Player player=Bukkit.getPlayer(request.player());
        if(player==null){searching.remove(request.player());delivering.remove(request.id());return;}
        CompletableFuture.runAsync(()->{try{storage.delete(request.id());}catch(Exception error){throw new java.util.concurrent.CompletionException(error);}})
                .whenComplete((ignored,error)->Bukkit.getScheduler().runTask(plugin,()->{
                    delivering.remove(request.id());
                    searching.remove(request.player());
                    if(error!=null){player.sendMessage(messages.get(player,"rtp.failed"));return;}
                    if(request.result()==null)failed(player);
                    else startResolved(player,destinations.values().stream().filter(d->d.shard().equalsIgnoreCase(request.destinationShard())&&d.world().equals(request.world())).findFirst().orElse(new Destination(request.destinationShard(),request.destinationShard(),request.world(),request.worldSize(),request.destinationShard(),Material.GRASS_BLOCK)),request.result());
                }));
    }

    private void startResolved(Player player,Destination destination,NetworkLocation target){
        TeleportManager.Target resolved=new TeleportManager.Target(target,System.nanoTime(),destination.display());
        long localCooldown=destination.shard().equalsIgnoreCase(network.shardId())?cooldownMillis:0L;
        teleports.request(player,"rtp",()->resolved,countdown,localCooldown,false,
                ignored->valid(destination,target,claimBuffer),
                ()->{
                    if(destination.shard().equalsIgnoreCase(network.shardId()))search(destination,claimBuffer,attempts,replacement->{
                        if(replacement==null)failed(player);else startResolved(player,destination,replacement);
                    });
                    else if(searching.add(player.getUniqueId()))requestRemote(player,destination);
                });
        searching.remove(player.getUniqueId());
    }

    private void failed(Player player){
        searching.remove(player.getUniqueId());
        network.saveCooldown(player.getUniqueId(),"rtp-retry",System.currentTimeMillis()+retryMillis);
        player.sendMessage(messages.get(player,"rtp.failed","time",me.vertex.core.grace.DurationParser.format((retryMillis+999L)/1_000L)));
    }

    private void search(Destination destination,int buffer,int maximum,Consumer<NetworkLocation> result){search(destination,buffer,maximum,0,result);}
    private void search(Destination destination,int buffer,int maximum,int used,Consumer<NetworkLocation> result){
        World world=Bukkit.getWorld(destination.world());if(world==null||used>=maximum){result.accept(null);return;}
        int radius=Math.max(32,destination.worldSize()/2-32);
        int x=ThreadLocalRandom.current().nextInt(-radius,radius+1),z=ThreadLocalRandom.current().nextInt(-radius,radius+1);
        world.getChunkAtAsync(x>>4,z>>4,true).whenComplete((chunk,error)->Bukkit.getScheduler().runTask(plugin,()->{
            NetworkLocation safe=error==null?safeAt(destination,world,x,z,buffer):null;
            if(safe!=null)result.accept(safe);else search(destination,buffer,maximum,used+1,result);
        }));
    }

    private NetworkLocation safeAt(Destination destination,World world,int x,int z,int buffer){
        int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);Block ground=world.getBlockAt(x,y,z);
        if(!validGround(ground.getType())||y+2>=world.getMaxHeight()||!world.getBlockAt(x,y+1,z).isPassable()||!world.getBlockAt(x,y+2,z).isPassable())return null;
        NetworkLocation target=new NetworkLocation(destination.shard(),destination.world(),x+.5D,y+1D,z+.5D,0F,0F);
        return valid(destination,target,buffer)?target:null;
    }

    public boolean validate(NetworkLocation target){
        Destination destination=destinations.values().stream().filter(d->d.shard().equalsIgnoreCase(target.shardId())&&d.world().equals(target.world())).findFirst().orElse(null);
        return destination!=null&&valid(destination,target,claimBuffer);
    }

    private boolean valid(Destination destination,NetworkLocation target,int buffer){
        ChunkKey centre=ChunkKey.at(destination.shard(),destination.world(),((int)Math.floor(target.x()))>>4,((int)Math.floor(target.z()))>>4);
        for(int dx=-buffer;dx<=buffer;dx++)for(int dz=-buffer;dz<=buffer;dz++)if(factions.factionIdAt(new ChunkKey(centre.world(),centre.x()+dx,centre.z()+dz))!=FactionService.NO_FACTION)return false;
        if(!destination.shard().equalsIgnoreCase(network.shardId()))return true;
        World world=Bukkit.getWorld(target.world());if(world==null)return false;
        LocationCheck check=new LocationCheck((int)Math.floor(target.x()),(int)Math.floor(target.y()),(int)Math.floor(target.z()));
        if(check.y()<=world.getMinHeight()||check.y()+1>=world.getMaxHeight())return false;
        if(!world.getWorldBorder().isInside(new org.bukkit.Location(world,target.x(),target.y(),target.z())))return false;
        Block ground=world.getBlockAt(check.x(),check.y()-1,check.z());
        if(!validGround(ground.getType())||!world.getBlockAt(check.x(),check.y(),check.z()).isPassable()||!world.getBlockAt(check.x(),check.y()+1,check.z()).isPassable())return false;
        return true;
    }

    private static boolean validGround(Material material){return material.isSolid()&&!Set.of(Material.WATER,Material.LAVA,Material.MAGMA_BLOCK,Material.CACTUS,Material.FIRE,Material.SOUL_FIRE,Material.CAMPFIRE,Material.SOUL_CAMPFIRE,Material.POWDER_SNOW).contains(material);}
    private ItemStack item(Player p,Material material,String name,List<net.kyori.adventure.text.Component> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(messages.getGui(p,name));if(!lore.isEmpty())meta.lore(new ArrayList<>(lore));item.setItemMeta(meta);return item;}
    private static int bounded(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    public record Destination(String id,String shard,String world,int worldSize,String display,Material icon){}
    private record LocationCheck(int x,int y,int z){}
    private static final class Holder implements InventoryHolder{private final Map<Integer,String> destinationBySlot=new LinkedHashMap<>();private Inventory inventory;@Override public Inventory getInventory(){return inventory;}}
}
