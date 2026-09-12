package me.vertex.core.factions;

import me.vertex.core.grace.DurationParser;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Routes the social branches under the native /f root. */
public final class FactionSocialCommand implements Listener {
    private static final List<String> ROOT = List.of("ally", "neutral", "enemy", "focus", "ban", "unban", "bans", "logs");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
    private final Plugin plugin;
    private final FactionService factions;
    private final FactionSocialManager social;
    private final Messages messages;

    public FactionSocialCommand(Plugin plugin, FactionService factions, FactionSocialManager social, Messages messages) {
        this.plugin=plugin;this.factions=factions;this.social=social;this.messages=messages;
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onCommand(PlayerCommandPreprocessEvent event){
        String[] parts=event.getMessage().substring(1).trim().split("\\s+");
        if(parts.length<2||!isFactionCommand(parts[0])||!ROOT.contains(parts[1].toLowerCase(Locale.ROOT)))return;
        event.setCancelled(true);Player player=event.getPlayer();String action=parts[1].toLowerCase(Locale.ROOT);
        switch(action){
            case "ally"->relation(player,parts,FactionRelation.ALLY);
            case "neutral"->relation(player,parts,FactionRelation.NEUTRAL);
            case "enemy"->relation(player,parts,FactionRelation.ENEMY);
            case "focus"->focus(player,parts);
            case "ban"->ban(player,parts,false);
            case "unban"->ban(player,parts,true);
            case "bans"->showBans(player);
            case "logs"->showLogs(player,parts);
        }
    }

    private void relation(Player player,String[] parts,FactionRelation relation){
        FactionData target=parts.length<3?null:factions.faction(parts[2]).orElse(null);
        if(target==null){send(player,"faction-not-found");return;}
        factions.submitMutation(()->social.changeRelation(player,target.id(),relation)).whenComplete((result,error)->onMain(()->result(player,error==null?result:FactionSocialManager.Result.DATABASE_ERROR,"relation",target.tag())));
    }

    private void focus(Player player,String[] parts){
        int factionId=factions.factionId(player);
        if(factionId==FactionService.NO_FACTION){result(player,FactionSocialManager.Result.NO_FACTION,"focus","");return;}
        if(parts.length<3){
            List<String> names=social.focuses(factionId).stream().map(focus->factions.faction(focus.targetId()).map(FactionData::tag).orElse("#"+focus.targetId())).toList();
            player.sendMessage(messages.get(player,"faction-social.focus-list","targets",names.isEmpty()?messages.getRaw(player,"general.none"):String.join(", ",names)));return;
        }
        boolean remove=parts[2].equalsIgnoreCase("remove");String raw=remove&&parts.length>=4?parts[3]:parts[2];
        FactionData target=resolveFaction(raw);if(target==null){send(player,"faction-not-found");return;}
        factions.submitMutation(()->social.focus(player,target.id(),remove)).whenComplete((outcome,error)->onMain(()->result(player,error==null?outcome:FactionSocialManager.Result.DATABASE_ERROR,"focus",target.tag())));
    }

    private void ban(Player player,String[] parts,boolean remove){
        if(parts.length<3){player.sendMessage(messages.get(player,"faction-social.ban-usage"));return;}
        OfflinePlayer target=resolvePlayer(parts[2]);if(target==null){player.sendMessage(messages.get(player,"general.player-not-found"));return;}
        factions.submitMutation(()->remove?social.unban(player,target.getUniqueId()):social.ban(player,target.getUniqueId(),target.getName()==null?parts[2]:target.getName())).whenComplete((outcome,error)->onMain(()->result(player,error==null?outcome:FactionSocialManager.Result.DATABASE_ERROR,remove?"unban":"ban",target.getName()==null?parts[2]:target.getName())));
    }

    private void showBans(Player player){
        FactionMember member=factions.member(player.getUniqueId());if(member==null){result(player,FactionSocialManager.Result.NO_FACTION,"bans","");return;}
        if(member.role()!=FactionRole.LEADER){result(player,FactionSocialManager.Result.NO_PERMISSION,"bans","");return;}
        String names=social.bans(member.factionId()).stream().map(FactionSocialStorage.Ban::playerName).reduce((a,b)->a+", "+b).orElse(messages.getRaw(player,"general.none"));
        player.sendMessage(messages.get(player,"faction-social.bans-list","players",names));
    }

    private void showLogs(Player player,String[] parts){
        FactionMember member=factions.member(player.getUniqueId());if(member==null){result(player,FactionSocialManager.Result.NO_FACTION,"logs","");return;}
        if(member.role()!=FactionRole.LEADER&&member.role()!=FactionRole.COLEADER){result(player,FactionSocialManager.Result.NO_PERMISSION,"logs","");return;}
        int page=0;if(parts.length>=3)try{page=Math.max(0,Integer.parseInt(parts[2])-1);}catch(NumberFormatException ignored){}
        int selectedPage=page;
        social.logs(member.factionId(),page).whenComplete((entries,error)->onMain(()->{
            if(error!=null){result(player,FactionSocialManager.Result.DATABASE_ERROR,"logs","");return;}
            player.sendMessage(messages.get(player,"faction-social.logs-header","page",String.valueOf(selectedPage+1)));
            if(entries.isEmpty())player.sendMessage(messages.get(player,"faction-social.logs-empty"));
            for(FactionSocialStorage.LogEntry entry:entries){
                String actor=entry.actorName()==null?messages.getRaw(player,"general.server"):entry.actorName();
                Component line=messages.get(player,"faction-social.logs-entry","action",pretty(entry.action()),"actor",actor,"time",TIME.format(Instant.ofEpochMilli(entry.createdAt())));
                if(entry.details()!=null&&!entry.details().isBlank())line=line.hoverEvent(HoverEvent.showText(Component.text(entry.details(),NamedTextColor.GRAY)));
                player.sendMessage(line);
            }
            Component nav=Component.empty();if(selectedPage>0)nav=nav.append(messages.get(player,"faction-social.logs-back").clickEvent(ClickEvent.runCommand("/f logs "+selectedPage)));
            if(entries.size()==10)nav=nav.append(Component.space()).append(messages.get(player,"faction-social.logs-next").clickEvent(ClickEvent.runCommand("/f logs "+(selectedPage+2))));
            if(!nav.equals(Component.empty()))player.sendMessage(nav);
        }));
    }

    private void result(Player player,FactionSocialManager.Result result,String action,String target){
        String key=switch(result){case OK->action+"-ok";case REQUEST_SENT->"relation-request-sent";case ACCEPTED->"relation-accepted";case REMOVED->action+"-removed";case NO_FACTION->"no-faction";case NO_PERMISSION->"no-permission";case NOT_FOUND->"not-found";case INVALID_RELATION->"focus-invalid-relation";case ALLY_LIMIT->"ally-limit";case LIMIT_REACHED->"focus-limit";case BANNED->"already-banned";case NOT_BANNED->"not-banned";case DATABASE_ERROR->"database-error";};
        player.sendMessage(messages.get(player,"faction-social."+key,"target",target));
    }

    private FactionData resolveFaction(String raw){FactionData direct=factions.faction(raw).orElse(null);if(direct!=null)return direct;Player player=Bukkit.getPlayerExact(raw);return player==null?null:factions.faction(player).orElse(null);}
    private OfflinePlayer resolvePlayer(String raw){Player online=Bukkit.getPlayerExact(raw);if(online!=null)return online;OfflinePlayer cached=Bukkit.getOfflinePlayerIfCached(raw);if(cached!=null)return cached;try{return Bukkit.getOfflinePlayer(java.util.UUID.fromString(raw));}catch(IllegalArgumentException ignored){return null;}}
    private void send(Player player,String key){player.sendMessage(messages.get(player,"native-factions."+key));}
    private void onMain(Runnable task){Bukkit.getScheduler().runTask(plugin,task);}
    private boolean isFactionCommand(String command){String normalized=command.contains(":")?command.substring(command.indexOf(':')+1):command;return plugin.getConfig().getStringList("factions.command-aliases").stream().anyMatch(alias->alias.equalsIgnoreCase(normalized));}
    private static String pretty(String value){return Arrays.stream(value.toLowerCase(Locale.ROOT).split("_")).map(word->word.isEmpty()?word:Character.toUpperCase(word.charAt(0))+word.substring(1)).reduce((a,b)->a+" "+b).orElse(value);}

    @EventHandler public void onTab(TabCompleteEvent event){if(!(event.getSender() instanceof Player)||!event.getBuffer().startsWith("/"))return;String[] parts=event.getBuffer().substring(1).split("\\s+",-1);if(parts.length<2||!isFactionCommand(parts[0]))return;List<String> additions=new ArrayList<>();if(parts.length==2)additions.addAll(ROOT);else if(parts.length==3&&List.of("ally","neutral","enemy").contains(parts[1].toLowerCase(Locale.ROOT)))additions.addAll(factions.factions().stream().filter(faction->!faction.system()).map(FactionData::tag).toList());else if(parts.length==3&&parts[1].equalsIgnoreCase("focus")){additions.add("remove");additions.addAll(factions.factions().stream().filter(faction->!faction.system()).map(FactionData::tag).toList());}else if(parts.length==4&&parts[1].equalsIgnoreCase("focus")&&parts[2].equalsIgnoreCase("remove"))additions.addAll(factions.factions().stream().filter(faction->!faction.system()).map(FactionData::tag).toList());else if(parts.length==3&&List.of("ban","unban").contains(parts[1].toLowerCase(Locale.ROOT)))additions.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());String partial=parts[parts.length-1].toLowerCase(Locale.ROOT);List<String> result=new ArrayList<>(event.getCompletions());additions.stream().filter(value->value.toLowerCase(Locale.ROOT).startsWith(partial)).filter(value->result.stream().noneMatch(value::equalsIgnoreCase)).forEach(result::add);event.setCompletions(result);}
}
