package me.vertex.core.mine;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.portal.PortalManager;
import me.vertex.core.pvp.CombatManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Mine-menu entry confirmation with the same combat-safe stationary channel used for Zone entry. */
public final class MineTeleportManager implements Listener {
    private final Plugin plugin; private final MineManager mines; private final CombatManager combat; private final Messages messages;
    private PortalManager portals;
    private final Map<UUID, Request> requests = new ConcurrentHashMap<>(); private BukkitTask task;
    public MineTeleportManager(Plugin plugin,MineManager mines,CombatManager combat,Messages messages){this.plugin=plugin;this.mines=mines;this.combat=combat;this.messages=messages;task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L);}
    public void setPortalManager(PortalManager portals){this.portals=portals;}
    public void open(Player player,String mineId){MineRegion region=mines.region(mineId);if(region==null||!region.isDefined()){player.sendMessage(messages.get(player,"mines.not-placed","mine",mineId));return;}if(combat.isTagged(player.getUniqueId())){player.sendMessage(messages.get(player,"mines.entry-combat"));return;}Inventory inventory=Bukkit.createInventory(new Holder(region.id()),27,messages.getGui(player,"mines.entry-gui-title","mine",region.displayName()));ItemStack fill=item(Material.GRAY_STAINED_GLASS_PANE,Component.empty());for(int slot=0;slot<27;slot++)inventory.setItem(slot,fill);inventory.setItem(13,item(Material.EMERALD_BLOCK,messages.getGui(player,"mines.entry-gui-confirm","mine",region.displayName())));player.openInventory(inventory);}
    @EventHandler public void click(InventoryClickEvent event){if(!(event.getInventory().getHolder() instanceof Holder holder)||!(event.getWhoClicked() instanceof Player player))return;event.setCancelled(true);if(event.getRawSlot()!=13)return;MineRegion region=mines.region(holder.mineId);if(region==null||combat.isTagged(player.getUniqueId())){player.closeInventory();player.sendMessage(messages.get(player,"mines.entry-combat"));return;}requests.put(player.getUniqueId(),new Request(region,player.getLocation().clone(),System.currentTimeMillis()+5000L));player.closeInventory();}
    @EventHandler public void drag(InventoryDragEvent event){if(event.getInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void damage(EntityDamageEvent event){if(event.getEntity() instanceof Player player)cancel(player,"damage");}
    @EventHandler public void move(PlayerMoveEvent event){if(event.getTo()!=null&&(event.getFrom().getBlockX()!=event.getTo().getBlockX()||event.getFrom().getBlockY()!=event.getTo().getBlockY()||event.getFrom().getBlockZ()!=event.getTo().getBlockZ()||!event.getFrom().getWorld().equals(event.getTo().getWorld())))cancel(event.getPlayer(),"movement");}
    @EventHandler public void quit(PlayerQuitEvent event){requests.remove(event.getPlayer().getUniqueId());}
    private void tick(){long now=System.currentTimeMillis();for(var entry:Map.copyOf(requests).entrySet()){Player player=Bukkit.getPlayer(entry.getKey());Request request=entry.getValue();if(player==null){requests.remove(entry.getKey());continue;}if(combat.isTagged(player.getUniqueId())||!player.getWorld().equals(request.origin.getWorld())||player.getLocation().distanceSquared(request.origin)>.01D){cancel(player,"movement/combat");continue;}long remaining=Math.max(0L,request.until-now);if(remaining==0){requests.remove(player.getUniqueId());if(portals==null||!portals.startMineEntry(player,request.region.id()))player.sendMessage(messages.get(player,"mines.entry-failed"));else player.sendMessage(messages.get(player,"mines.entry-success","mine",request.region.displayName()));}else player.sendMessage(messages.get(player,"mines.entry-countdown","mine",request.region.displayName(),"seconds",String.valueOf((remaining+999)/1000)));}}
    private void cancel(Player player,String reason){if(requests.remove(player.getUniqueId())!=null)player.sendMessage(messages.get(player,"mines.entry-cancelled","reason",reason));}
    private ItemStack item(Material material,Component name){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.displayName(name);item.setItemMeta(meta);return item;}
    private record Request(MineRegion region,Location origin,long until){}
    private record Holder(String mineId) implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
}
