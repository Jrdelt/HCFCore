package me.vertex.core.zone;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** /haven, /riftlands, and /zones command tree with permission-gated editing/debug branches. */
public final class ZoneCommand implements CommandExecutor, TabCompleter {
    private final ZoneManager zones;
    private final ZoneMenu menu;
    private final Messages messages;
    private final ZoneType fixedType;

    public ZoneCommand(ZoneManager zones, ZoneMenu menu, Messages messages, ZoneType fixedType) {
        this.zones = zones; this.menu = menu; this.messages = messages; this.fixedType = fixedType;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("This command is player-only."); return true; }
        if (fixedType == null) { menu.openProgress(player); return true; }
        ZoneType type = fixedType;
        if (args.length == 0) return openEntry(player, type);
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("lootpool")) { menu.openLoot(player, type, player.hasPermission("vertex.zones.admin")); return true; }
        if (sub.equals("ticket") && type == ZoneType.RIFTLANDS) return ticket(player,args);
        if (sub.equals("region")) return region(player,type,args);
        if (sub.equals("route")) return route(player,type,args);
        if (sub.equals("admin")) return admin(player,type,args);
        sender.sendMessage(messages.get(player,"zones.usage")); return true;
    }

    private boolean openEntry(Player player, ZoneType type) {
        if (!player.hasPermission("vertex.zones.use")) { player.sendMessage(messages.get(player, "zones.no-permission")); return true; }
        String result = zones.requestEntry(player,type);
        if ("ok".equals(result)) menu.openEntry(player,type);
        else if (result.equals("combat")) player.sendMessage(messages.get(player,"zones.entry-combat"));
        else if (result.startsWith("cooldown:")) player.sendMessage(messages.get(player,"zones.entry-cooldown","seconds",result.substring("cooldown:".length())));
        else player.sendMessage(messages.get(player,"zones.no-route"));
        return true;
    }
    private boolean adminAllowed(Player player) { if(player.hasPermission("vertex.zones.admin"))return true; player.sendMessage(messages.get(player,"zones.no-permission")); return false; }
    private boolean region(Player player, ZoneType type, String[] args) {
        if(!adminAllowed(player))return true; if(args.length<2){player.sendMessage("§e/"+type.configKey()+" region create|delete|list|wand <name>");return true;}
        String action=args[1].toLowerCase(Locale.ROOT);
        if(action.equals("list")){player.sendMessage("§6"+type.displayName()+" regions: §f"+String.join(", ",zones.regions(type).stream().map(ZoneRegion::id).toList()));return true;}
        if(action.equals("wand")){player.getInventory().addItem(zones.selectorItem());player.sendMessage("§aZone selector given.");return true;}
        if(args.length<3){player.sendMessage("§cA region name is required.");return true;}
        if(action.equals("create")){String result=zones.beginRegionSelection(player,type,args[2]);player.sendMessage("ok".equals(result)?"§aSelect two corners with the Zone Selector, then sneak-air click to save.":"§cInvalid region name.");return true;}
        if(action.equals("delete")){player.sendMessage(zones.deleteRegion(args[2])?"§aRegion deleted.":"§cRegion not found.");return true;}
        return true;
    }
    private boolean route(Player player,ZoneType type,String[] args){
        if(!adminAllowed(player))return true;if(args.length<2){player.sendMessage("§e/"+type.configKey()+" route create <region> <name>|delete|preview <name>|list [region]");return true;}String action=args[1].toLowerCase(Locale.ROOT);
        if(action.equals("list")){String region=args.length>2?args[2]:"";player.sendMessage("§6Routes: §f"+String.join(", ",zones.allRoutes().stream().filter(r->region.isBlank()||r.regionId().equalsIgnoreCase(region)).map(ZoneRoute::id).toList()));return true;}
        if(action.equals("preview")&&args.length>2){player.sendMessage(zones.previewRoute(player,args[2])?"§aRoute preview started. Interact to release.":"§cRoute not found or invalid.");return true;}
        if(action.equals("delete")&&args.length>2){player.sendMessage(zones.deleteRoute(args[2])?"§aRoute deleted.":"§cRoute not found.");return true;}
        if(action.equals("create")&&args.length>3){String result=zones.beginRouteSelection(player,type,args[2],args[3]);player.sendMessage("ok".equals(result)?"§aLeft-click blocks to add route points, right-click removes the last, then sneak-air click to validate/save.":"§cCould not start route selection: "+result);return true;}
        return true;
    }
    private boolean ticket(Player player,String[] args){if(!adminAllowed(player))return true;if(args.length<4||!args[1].equalsIgnoreCase("give")){player.sendMessage("§e/riftlands ticket give <player> <amount>");return true;}Player target=Bukkit.getPlayerExact(args[2]);int amount=parsePositive(args[3]);if(target==null||amount<1){player.sendMessage("§cPlayer/amount invalid.");return true;}for(int i=0;i<amount;i++){ItemStack ticket=zones.createTicket();target.getInventory().addItem(ticket).values().forEach(left->target.getWorld().dropItemNaturally(target.getLocation(),left));}player.sendMessage("§aGave "+amount+" Riftlands ticket(s) to "+target.getName()+".");return true;}
    private boolean admin(Player player,ZoneType type,String[] args){if(!adminAllowed(player))return true;if(args.length<2){player.sendMessage("§e/"+type.configKey()+" admin event start|stop|seasonreset|inspect <player>");return true;}switch(args[1].toLowerCase(Locale.ROOT)){case "event"->{if(args.length>2&&args[2].equalsIgnoreCase("start")){zones.forceStartEvent();player.sendMessage("§aKill event started.");}else if(args.length>2&&args[2].equalsIgnoreCase("stop")){zones.forceStopEvent();player.sendMessage("§aKill event stopped.");}}case "seasonreset"->{zones.resetSeason();player.sendMessage("§aZone seasonal progression reset. Winner boosters are retained.");}case "inspect"->{Player target=args.length>2?Bukkit.getPlayerExact(args[2]):player;if(target!=null){player.sendMessage("§6"+target.getName()+" §7Haven: §f"+zones.kills(target,ZoneType.HAVEN)+" §7Riftlands: §f"+zones.kills(target,ZoneType.RIFTLANDS)+" §7Amplification: §a+"+zones.amplification(target,zones.isIn(target,ZoneType.RIFTLANDS)?ZoneType.RIFTLANDS:ZoneType.HAVEN)+"%");}}default->player.sendMessage("§cUnknown admin action.");}return true;}
    private int parsePositive(String raw){try{return Math.max(0,Integer.parseInt(raw));}catch(NumberFormatException ignored){return 0;}}
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){if(fixedType==null)return List.of();if(args.length==1)return complete(args[0],List.of("lootpool","region","route","ticket","admin"));if(args[0].equalsIgnoreCase("region")){if(args.length==2)return complete(args[1],List.of("create","delete","list","wand"));if(args.length==3&&args[1].equalsIgnoreCase("delete"))return complete(args[2],zones.regions(fixedType).stream().map(ZoneRegion::id).toList());}if(args[0].equalsIgnoreCase("route")){if(args.length==2)return complete(args[1],List.of("create","delete","preview","list"));if(args.length==3&&args[1].equalsIgnoreCase("create"))return complete(args[2],zones.regions(fixedType).stream().map(ZoneRegion::id).toList());if(args.length==3&&(args[1].equalsIgnoreCase("delete")||args[1].equalsIgnoreCase("preview")))return complete(args[2],zones.allRoutes().stream().map(ZoneRoute::id).toList());}if(args[0].equalsIgnoreCase("ticket")&&args.length==2)return complete(args[1],List.of("give"));if(args[0].equalsIgnoreCase("ticket")&&args.length==3)return complete(args[2],Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());if(args[0].equalsIgnoreCase("admin")&&args.length==2)return complete(args[1],List.of("event","seasonreset","inspect"));if(args[0].equalsIgnoreCase("admin")&&args.length==3&&args[1].equalsIgnoreCase("event"))return complete(args[2],List.of("start","stop"));if(args[0].equalsIgnoreCase("admin")&&args.length==3&&args[1].equalsIgnoreCase("inspect"))return complete(args[2],Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());return List.of();}
    private List<String> complete(String prefix,List<String> choices){String needle=prefix.toLowerCase(Locale.ROOT);return choices.stream().filter(choice->choice.toLowerCase(Locale.ROOT).startsWith(needle)).toList();}
}
