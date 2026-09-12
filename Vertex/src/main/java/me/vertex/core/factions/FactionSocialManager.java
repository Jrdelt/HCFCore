package me.vertex.core.factions;

import me.vertex.core.factions.event.FactionClaimEvent;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Native faction social rules: requests, bans, focus, and faction history. */
public final class FactionSocialManager implements Listener {
    public enum Result { OK, REQUEST_SENT, ACCEPTED, REMOVED, NO_FACTION, NO_PERMISSION, NOT_FOUND, INVALID_RELATION, ALLY_LIMIT, LIMIT_REACHED, BANNED, NOT_BANNED, DATABASE_ERROR }
    private record RequestKey(int requester, int target, FactionRelation relation) { }
    private record BanKey(int faction, UUID player) { }
    private record FocusKey(int source, int target) { }

    private final Plugin plugin;
    private final FactionService factions;
    private final FactionSocialStorage storage;
    private final Messages messages;
    private final Map<RequestKey, FactionSocialStorage.Request> requests = new HashMap<>();
    private final Map<BanKey, FactionSocialStorage.Ban> bans = new HashMap<>();
    private final Map<FocusKey, FactionSocialStorage.Focus> focuses = new HashMap<>();
    private final Map<Integer, FactionSocialStorage.Archive> archives = new HashMap<>();
    private final Map<UUID, Set<UUID>> displayedGlow = new HashMap<>();
    private final Object writeLock = new Object();
    private CompletableFuture<Void> writeTail = CompletableFuture.completedFuture(null);
    private volatile long requestDurationMillis;
    private volatile long focusDurationMillis;
    private volatile int focusLimit;
    private volatile int allyLimit;
    private volatile Runnable mutationPublisher = () -> { };
    private BukkitTask visualTask;

    public FactionSocialManager(Plugin plugin, FactionService factions, FactionSocialStorage storage, Messages messages) {
        this.plugin = plugin; this.factions = factions; this.storage = storage; this.messages = messages;
    }

    public void load() throws SQLException {
        reloadConfig();
        requests.clear(); bans.clear(); focuses.clear(); archives.clear();
        long now = System.currentTimeMillis();
        storage.deleteFactionDataForMissingFactions();
        storage.deleteExpired(now, now - TimeUnit.DAYS.toMillis(7));
        for (FactionSocialStorage.Request request : storage.loadRequests()) if (request.expiresAt() > now)
            requests.put(new RequestKey(request.requesterId(), request.targetId(), request.relation()), request);
        for (FactionSocialStorage.Ban ban : storage.loadBans()) bans.put(new BanKey(ban.factionId(), ban.playerUuid()), ban);
        for (FactionSocialStorage.Focus focus : storage.loadFocuses()) if (focus.expiresAt() > now)
            focuses.put(new FocusKey(focus.sourceId(), focus.targetId()), focus);
        for (FactionSocialStorage.Archive archive : storage.loadArchives()) archives.put(archive.factionId(), archive);
        factions.setFactionBanQuery(this::isBanned);
    }

    public void reloadConfig() {
        requestDurationMillis = TimeUnit.SECONDS.toMillis(Math.max(60L, plugin.getConfig().getLong("factions.relations.request-expiry-seconds", 7_200L)));
        focusDurationMillis = TimeUnit.SECONDS.toMillis(Math.max(10L, plugin.getConfig().getLong("factions.focus.duration-seconds", 900L)));
        focusLimit = Math.max(1, plugin.getConfig().getInt("factions.focus.concurrent-targets", 2));
        allyLimit = Math.max(0, plugin.getConfig().getInt("factions.relations.ally-limit", 1));
    }

    public void setMutationPublisher(Runnable publisher) {
        mutationPublisher = publisher == null ? () -> { } : publisher;
    }

    /** Refreshes relation-request, ban, and focus caches after another shard writes them. */
    public void refreshAsync() {
        CompletableFuture.supplyAsync(() -> {
            try { return new SocialSnapshot(storage.loadRequests(), storage.loadBans(), storage.loadFocuses()); }
            catch (SQLException error) { throw new java.util.concurrent.CompletionException(error); }
        }).whenComplete((snapshot, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not refresh faction social state.", error);
                return;
            }
            long now = System.currentTimeMillis();
            synchronized (this) {
                requests.clear(); bans.clear(); focuses.clear();
                for (FactionSocialStorage.Request request : snapshot.requests()) if (request.expiresAt() > now)
                    requests.put(new RequestKey(request.requesterId(), request.targetId(), request.relation()), request);
                for (FactionSocialStorage.Ban ban : snapshot.bans())
                    bans.put(new BanKey(ban.factionId(), ban.playerUuid()), ban);
                for (FactionSocialStorage.Focus focus : snapshot.focuses()) if (focus.expiresAt() > now)
                    focuses.put(new FocusKey(focus.sourceId(), focus.targetId()), focus);
            }
        }));
    }

    public void start() {
        if (visualTask != null) visualTask.cancel();
        visualTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickVisuals, 20L, 20L);
    }

    public void shutdown() {
        if (visualTask != null) visualTask.cancel();
        for (Player viewer : Bukkit.getOnlinePlayers()) clearViewerGlow(viewer);
        CompletableFuture<Void> tail;
        synchronized (writeLock) { tail = writeTail; }
        try { tail.get(10, TimeUnit.SECONDS); }
        catch (Exception error) { plugin.getLogger().log(Level.WARNING, "Timed out waiting for faction social writes.", error); }
    }

    public synchronized Result changeRelation(Player actor, int targetId, FactionRelation desired) {
        FactionMember member = actor == null ? null : factions.member(actor.getUniqueId());
        if (member == null) return Result.NO_FACTION;
        if (targetId == member.factionId() || factions.faction(targetId).isEmpty()) return Result.NOT_FOUND;
        String action = desired == FactionRelation.ALLY ? "ally-request" : desired == FactionRelation.ENEMY
                ? "enemy-declare" : "neutral-request";
        if (!factions.hasAction(member, action) && !factions.hasAction(member, "relation")) return Result.NO_PERMISSION;
        int sourceId = member.factionId();
        long now = System.currentTimeMillis();
        try {
            FactionSocialStorage.RelationMutationOutcome outcome = storage.mutateRelation(sourceId,
                    targetId, desired, allyLimit, now, now + requestDurationMillis, actor.getUniqueId(),
                    member.role(), factions.configuredActionDefault(member.role(), action),
                    factions.configuredActionDefault(member.role(), "relation"));
            Result result = switch (outcome.result()) {
                case OK -> Result.OK;
                case REQUEST_SENT -> Result.REQUEST_SENT;
                case ACCEPTED -> Result.ACCEPTED;
                case REMOVED -> Result.REMOVED;
                case ALLY_LIMIT -> Result.ALLY_LIMIT;
                case NOT_FOUND -> Result.NOT_FOUND;
                case NOT_AUTHORIZED -> Result.NO_PERMISSION;
            };
            if (result == Result.REQUEST_SENT && outcome.request() != null) {
                FactionSocialStorage.Request request = outcome.request();
                requests.put(new RequestKey(request.requesterId(), request.targetId(), request.relation()), request);
            } else if (result != Result.ALLY_LIMIT && result != Result.NOT_FOUND
                    && result != Result.NO_PERMISSION) {
                removeRequestsBetweenCache(sourceId, targetId);
                if (result == Result.ACCEPTED || result == Result.REMOVED || desired == FactionRelation.ENEMY) {
                    clearFocusPairCache(sourceId, targetId);
                    factions.applyMutualRelationCache(sourceId, targetId, outcome.relation());
                }
            }
            String logAction = switch (result) {
                case REQUEST_SENT -> "RELATION_REQUEST";
                case ACCEPTED -> "RELATION_ACCEPTED";
                case REMOVED -> "RELATION_NEUTRAL";
                case OK -> desired == FactionRelation.ENEMY ? "RELATION_ENEMY" : null;
                default -> null;
            };
            if (logAction != null) log(sourceId, logAction, actor,
                    "target=" + targetId + ";relation=" + desired);
            if (result != Result.ALLY_LIMIT && result != Result.NOT_FOUND
                    && result != Result.NO_PERMISSION) mutationPublisher.run();
            return result;
        } catch (SQLException error) {
            plugin.getLogger().log(Level.WARNING, "Could not change faction relation.", error);
            return Result.DATABASE_ERROR;
        }
    }

    /** Safe /fa override: relation, requests, and focus are committed as one unit. */
    public synchronized boolean forceRelation(int left, int right, FactionRelation relation, Player actor) {
        if (left == right || relation == null) return false;
        try {
            if (!storage.forceRelation(left, right, relation)) return false;
            removeRequestsBetweenCache(left, right);
            clearFocusPairCache(left, right);
            factions.applyMutualRelationCache(left, right, relation);
            log(left, "RELATION_ADMIN_OVERRIDE", actor,
                    "target=" + right + ";relation=" + relation.name());
            log(right, "RELATION_ADMIN_OVERRIDE", actor,
                    "target=" + left + ";relation=" + relation.name());
            mutationPublisher.run();
            return true;
        } catch (SQLException error) {
            plugin.getLogger().log(Level.WARNING, "Could not force faction relation.", error);
            return false;
        }
    }

    public synchronized Result ban(Player actor, UUID target, String name) {
        FactionMember member=actor==null?null:factions.member(actor.getUniqueId());
        if(member==null)return Result.NO_FACTION;
        if(member.role()!=FactionRole.LEADER)return Result.NO_PERMISSION;
        if(target==null)return Result.NOT_FOUND;
        FactionMember targetMember=factions.member(target);
        if(targetMember!=null&&targetMember.factionId()==member.factionId())return Result.BANNED;
        FactionSocialStorage.Ban ban=new FactionSocialStorage.Ban(member.factionId(),target,name==null?target.toString():name,actor.getUniqueId(),System.currentTimeMillis());
        try{FactionSocialStorage.BanWriteResult saved=storage.mutateBan(ban,false);if(saved==FactionSocialStorage.BanWriteResult.NOT_AUTHORIZED)return Result.NO_PERMISSION;if(saved==FactionSocialStorage.BanWriteResult.ALREADY_MEMBER)return Result.BANNED;if(saved!=FactionSocialStorage.BanWriteResult.OK)return Result.NOT_FOUND;bans.put(new BanKey(member.factionId(),target),ban);factions.invalidateInviteCache(member.factionId(),target);log(member.factionId(),"MEMBER_BANNED",actor,"player="+ban.playerName());mutationPublisher.run();return Result.OK;}
        catch(SQLException error){plugin.getLogger().log(Level.WARNING,"Could not save faction ban.",error);return Result.DATABASE_ERROR;}
    }

    public synchronized Result unban(Player actor, UUID target) {
        FactionMember member=actor==null?null:factions.member(actor.getUniqueId());
        if(member==null)return Result.NO_FACTION;if(member.role()!=FactionRole.LEADER)return Result.NO_PERMISSION;
        BanKey key=new BanKey(member.factionId(),target);if(!bans.containsKey(key))return Result.NOT_BANNED;
        try{FactionSocialStorage.Ban placeholder=new FactionSocialStorage.Ban(member.factionId(),target,target.toString(),actor.getUniqueId(),System.currentTimeMillis());FactionSocialStorage.BanWriteResult saved=storage.mutateBan(placeholder,true);if(saved==FactionSocialStorage.BanWriteResult.NOT_AUTHORIZED)return Result.NO_PERMISSION;if(saved==FactionSocialStorage.BanWriteResult.NOT_BANNED)return Result.NOT_BANNED;if(saved!=FactionSocialStorage.BanWriteResult.OK)return Result.NOT_FOUND;bans.remove(key);log(member.factionId(),"MEMBER_UNBANNED",actor,"player="+target);mutationPublisher.run();return Result.OK;}
        catch(SQLException error){return Result.DATABASE_ERROR;}
    }

    public synchronized boolean isBanned(int factionId, UUID player) { return bans.containsKey(new BanKey(factionId, player)); }
    public synchronized List<FactionSocialStorage.Ban> bans(int factionId) { return bans.values().stream().filter(ban->ban.factionId()==factionId).sorted(java.util.Comparator.comparing(FactionSocialStorage.Ban::playerName,String.CASE_INSENSITIVE_ORDER)).toList(); }

    public synchronized Result focus(Player actor, int targetId, boolean remove) {
        FactionMember member=actor==null?null:factions.member(actor.getUniqueId());if(member==null)return Result.NO_FACTION;
        int source=member.factionId();
        FocusKey key=new FocusKey(source,targetId);
        try{
            long now=System.currentTimeMillis();
            FactionSocialStorage.FocusMutationOutcome outcome=storage.mutateFocus(source,targetId,
                    remove,focusLimit,now,now+focusDurationMillis,actor.getUniqueId());
            Result result=switch(outcome.result()){
                case OK->Result.OK;case REMOVED->Result.REMOVED;case NOT_FOUND->Result.NOT_FOUND;
                case INVALID_RELATION->Result.INVALID_RELATION;case LIMIT_REACHED->Result.LIMIT_REACHED;
                case NOT_AUTHORIZED->Result.NO_FACTION;};
            if(result==Result.OK&&outcome.focus()!=null)focuses.put(key,outcome.focus());
            else if(result==Result.REMOVED)focuses.remove(key);
            if(result==Result.OK||result==Result.REMOVED){log(source,result==Result.OK?"FOCUS_ADDED":"FOCUS_REMOVED",actor,"target="+targetId);mutationPublisher.run();}
            return result;
        }catch(SQLException error){return Result.DATABASE_ERROR;}
    }

    public synchronized List<FactionSocialStorage.Focus> focuses(int factionId) { long now=System.currentTimeMillis();return focuses.values().stream().filter(focus->focus.sourceId()==factionId&&focus.expiresAt()>now).toList(); }

    public CompletableFuture<List<FactionSocialStorage.LogEntry>> logs(int factionId, int page) {
        return CompletableFuture.supplyAsync(() -> {
            try { return storage.loadLogEntries(factionId, Math.max(0,page), 10); }
            catch (SQLException error) { throw new java.util.concurrent.CompletionException(error); }
        });
    }

    public synchronized FactionSocialStorage.Archive archive(String tagOrId) {
        if (tagOrId == null || tagOrId.isBlank()) return null;
        try {
            FactionSocialStorage.Archive byId = archives.get(Integer.parseInt(tagOrId));
            if (byId != null) return byId;
        } catch (NumberFormatException ignored) { }
        return archives.values().stream().filter(value -> value.tag().equalsIgnoreCase(tagOrId)).findFirst().orElse(null);
    }

    @EventHandler public void onClaim(FactionClaimEvent event) {
        if(event.action()==FactionClaimEvent.Action.UNCLAIM)return;
        log(event.faction().id(),"CLAIM_"+event.action().name(),event.actor(),event.chunk().world()+":"+event.chunk().x()+","+event.chunk().z());
    }

    @EventHandler public void onLifecycle(FactionLifecycleEvent event) {
        log(event.faction().id(),event.action().name(),event.actor(),event.previousTag()==null?null:"previous="+event.previousTag());
        if(event.action()==FactionLifecycleEvent.Action.DISBAND){
            synchronized(this){requests.keySet().removeIf(key->key.requester()==event.faction().id()||key.target()==event.faction().id());bans.keySet().removeIf(key->key.faction()==event.faction().id());focuses.keySet().removeIf(key->key.source()==event.faction().id()||key.target()==event.faction().id());long now=System.currentTimeMillis();archives.put(event.faction().id(),new FactionSocialStorage.Archive(event.faction().id(),event.faction().tag(),now,now+TimeUnit.DAYS.toMillis(7)));}
            queueWrite(()->storage.deleteFactionData(event.faction().id()));
            mutationPublisher.run();
        }
    }

    public void log(int factionId,String action,Player actor,String details){
        UUID uuid=actor==null?null:actor.getUniqueId();String name=actor==null?null:actor.getName();
        queueWrite(()->storage.insertLog(factionId,action,uuid,name,details,System.currentTimeMillis()));
    }

    private synchronized void clearFocusPairCache(int left,int right){
        focuses.keySet().removeIf(key->key.source()==left&&key.target()==right||key.source()==right&&key.target()==left);
    }
    private synchronized void removeRequestsBetweenCache(int left,int right){
        requests.keySet().removeIf(key->key.requester()==left&&key.target()==right||key.requester()==right&&key.target()==left);
    }

    private void tickVisuals(){
        long now=System.currentTimeMillis();
        boolean changed=false;
        synchronized(this){for(Map.Entry<FocusKey,FactionSocialStorage.Focus> entry:List.copyOf(focuses.entrySet()))if(entry.getValue().expiresAt()<=now||factions.relation(entry.getKey().source(),entry.getKey().target())==FactionRelation.NEUTRAL){focuses.remove(entry.getKey());queueWrite(()->storage.deleteFocus(entry.getKey().source(),entry.getKey().target()));changed=true;}}
        if(changed)mutationPublisher.run();
        for(Player viewer:Bukkit.getOnlinePlayers()){
            int source=factions.factionId(viewer);Set<UUID> shown=new HashSet<>();
            for(FactionSocialStorage.Focus focus:focuses(source)){
                FactionRelation relation=factions.relation(source,focus.targetId());Color color=relation==FactionRelation.ALLY?Color.PURPLE:Color.RED;
                for(FactionMember targetMember:factions.members(focus.targetId())){Player target=Bukkit.getPlayer(targetMember.playerUuid());if(target==null||!target.isOnline()||target.equals(viewer))continue;viewer.sendPotionEffectChange(target,new PotionEffect(PotionEffectType.GLOWING,40,0,false,false,false));viewer.spawnParticle(Particle.DUST,target.getLocation().add(0,1,0),3,0.3,0.6,0.3,new Particle.DustOptions(color,1F));shown.add(target.getUniqueId());}
            }
            Set<UUID> previous=displayedGlow.put(viewer.getUniqueId(),shown);if(previous!=null)for(UUID removed:previous)if(!shown.contains(removed)){Player target=Bukkit.getPlayer(removed);if(target!=null)viewer.sendPotionEffectChangeRemove(target,PotionEffectType.GLOWING);}
        }
    }

    private void clearViewerGlow(Player viewer){Set<UUID> previous=displayedGlow.remove(viewer.getUniqueId());if(previous!=null)for(UUID uuid:previous){Player target=Bukkit.getPlayer(uuid);if(target!=null)viewer.sendPotionEffectChangeRemove(target,PotionEffectType.GLOWING);}}
    private void queueWrite(SqlAction action){synchronized(writeLock){writeTail=writeTail.handle((ignored,error)->null).thenRunAsync(()->{try{action.run();}catch(SQLException error){throw new java.util.concurrent.CompletionException(error);}}).exceptionally(error->{plugin.getLogger().log(Level.WARNING,"Faction social write failed.",error);return null;});}}
    @FunctionalInterface private interface SqlAction{void run()throws SQLException;}
    private record SocialSnapshot(List<FactionSocialStorage.Request> requests,
                                  List<FactionSocialStorage.Ban> bans,
                                  List<FactionSocialStorage.Focus> focuses) { }
}
