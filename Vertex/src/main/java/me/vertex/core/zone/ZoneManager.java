package me.vertex.core.zone;

import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.booster.BoosterService;
import me.vertex.core.item.ItemKind;
import me.vertex.core.item.TrackedItemIds;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.storage.Database;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Owns Haven/Riftlands state and gameplay. World/entity work stays on the
 * primary thread; SQL uses compact, idempotent records so restart recovery
 * has one source of truth for progression, routes, event standings, and
 * active Riftlands sessions.
 */
public final class ZoneManager {
    private static final DecimalFormat PERCENT = new DecimalFormat("0.##");
    private final Plugin plugin;
    private final ZoneStorage storage;
    private final Messages messages;
    private final CombatManager combat;
    private final BackpackManager backpacks;
    private final TrackedItemIds trackedItems;
    private final NamespacedKey selectorKey, zoneMobKey, mobRegionKey, mobDefinitionKey, ticketKey, chanceKey, sessionKey;
    private final Map<String, ZoneRegion> regions = new ConcurrentHashMap<>();
    private final Map<String, ZoneRoute> routes = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<ZoneType, List<LootEntry>> loot = new EnumMap<>(ZoneType.class);
    private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
    private final Map<UUID, Countdown> entries = new ConcurrentHashMap<>();
    private final Map<UUID, Countdown> exits = new ConcurrentHashMap<>();
    private final Map<UUID, Flight> flights = new ConcurrentHashMap<>();
    private final Set<UUID> slowFalling = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spawnDispatchBypass = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Score> scores = new ConcurrentHashMap<>();
    /** Region -> only entity UUIDs spawned by Vertex, avoiding world-wide cleanup scans every tick. */
    private final Map<String, Set<UUID>> zoneMobsByRegion = new ConcurrentHashMap<>();
    private final Map<ZoneType, ZoneConfig> configs = new EnumMap<>(ZoneType.class);
    private final BossBar eventBar = Bukkit.createBossBar("Mob Kill Event", BarColor.PURPLE, BarStyle.SOLID);
    private volatile BoosterService boosters;
    private volatile long cycleAnchor;
    private volatile long loadedScoreEvent = Long.MIN_VALUE;
    private volatile long lastFinishedEvent = Long.MIN_VALUE;
    private volatile long lastAnnouncedEvent = Long.MIN_VALUE;
    private volatile long lastBossRefresh;
    private BukkitTask tickTask;

    public ZoneManager(Plugin plugin, Database database, Messages messages, CombatManager combat,
            BackpackManager backpacks, TrackedItemIds trackedItems) {
        this.plugin = plugin;
        this.storage = new ZoneStorage(database);
        this.messages = messages;
        this.combat = combat;
        this.backpacks = backpacks;
        this.trackedItems = trackedItems;
        selectorKey = new NamespacedKey(plugin, "zone_selector");
        zoneMobKey = new NamespacedKey(plugin, "zone_mob");
        mobRegionKey = new NamespacedKey(plugin, "zone_mob_region");
        mobDefinitionKey = new NamespacedKey(plugin, "zone_mob_definition");
        ticketKey = new NamespacedKey(plugin, "riftlands_ticket");
        chanceKey = new NamespacedKey(plugin, "zone_loot_chance");
        sessionKey = new NamespacedKey(plugin, "riftlands_session");
        for (ZoneType type : ZoneType.values()) loot.put(type, new ArrayList<>());
    }

    public ZoneStorage storage() { return storage; }
    public Component message(Player player, String key, String... placeholders) { return messages.get(player, key, placeholders); }
    public void setBoosterService(BoosterService boosters) { this.boosters = boosters; }
    public NamespacedKey sessionKey() { return sessionKey; }
    public boolean isZoneMob(Entity entity) { return entity != null && entity.getPersistentDataContainer().has(zoneMobKey, PersistentDataType.STRING); }
    public boolean isTicket(ItemStack item) { return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(ticketKey, PersistentDataType.BYTE); }

    public void initStorage() throws SQLException { storage.init(); }

    public void load() {
        for (ZoneType type : ZoneType.values()) configs.put(type, readConfig(type));
    }

    /** Called once after storage init; all definitions are loaded before commands become usable. */
    public void loadState() {
        try {
            regions.clear(); for (ZoneRegion region : storage.loadRegions()) regions.put(region.id(), region);
            routes.clear(); for (ZoneStorage.RouteRow row : storage.loadRoutes()) decodeRoute(row).ifPresent(route -> routes.put(route.id(), route));
            for (ZoneType type : ZoneType.values()) {
                List<LootEntry> loaded = new ArrayList<>();
                for (ZoneStorage.LootRow row : storage.loadLoot(type)) {
                    try { loaded.add(new LootEntry(ItemStack.deserializeItemsFromBytes(row.item())[0], row.chance())); }
                    catch (Exception ex) { plugin.getLogger().warning("Ignoring corrupt " + type.configKey() + " loot entry " + row.position() + "."); }
                }
                if (loaded.isEmpty() && config(type).ticketInLoot()) {
                    ItemStack ticket = createTicket();
                    loaded.add(new LootEntry(ticket, config(type).ticketChance()));
                    persistLoot(type, loaded);
                }
                loot.put(type, loaded);
            }
            long cycle = Math.max(60_000L, config(ZoneType.HAVEN).eventCycleMillis());
            cycleAnchor = storage.loadLong("cycle_anchor", System.currentTimeMillis() - Math.floorMod(System.currentTimeMillis(), cycle));
            storage.saveLong("cycle_anchor", cycleAnchor);
            restoreCurrentScores();
        } catch (Exception e) {
            throw new IllegalStateException("Could not load Haven/Riftlands state", e);
        }
    }

    public void start() {
        shutdown();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }
    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        tickTask = null;
        for (Player player : Bukkit.getOnlinePlayers()) eventBar.removePlayer(player);
        for (Flight flight : List.copyOf(flights.values())) releaseFlight(flight.playerId(), false);
        entries.clear(); exits.clear(); selections.clear(); slowFalling.clear();
    }

    public ZoneRegion regionAt(Location location) {
        if (location == null) return null;
        return regions.values().stream().filter(region -> region.contains(location))
                .sorted(Comparator.comparing(ZoneRegion::id)).findFirst().orElse(null);
    }
    public ZoneRegion region(String id) { return id == null ? null : regions.get(ZoneRegion.normalizeId(id)); }
    public Collection<ZoneRegion> regions(ZoneType type) { return regions.values().stream().filter(r -> r.type() == type).sorted(Comparator.comparing(ZoneRegion::id)).toList(); }
    public Collection<ZoneRoute> routes(ZoneRegion region) { return routes.values().stream().filter(r -> r.regionId().equals(region.id())).sorted(Comparator.comparing(ZoneRoute::id)).toList(); }
    public Collection<ZoneRoute> allRoutes() { return routes.values().stream().sorted(Comparator.comparing(ZoneRoute::id)).toList(); }
    public boolean isIn(Player player, ZoneType type) { ZoneRegion region = regionAt(player.getLocation()); return region != null && region.type() == type; }
    public boolean isInAnyZone(Location location) { return regionAt(location) != null; }
    public boolean isFlying(UUID uuid) { return flights.containsKey(uuid); }
    public boolean isEntering(UUID uuid) { return entries.containsKey(uuid); }

    // ---- admin selection -------------------------------------------------
    public ItemStack selectorItem() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageFormatter.deserialize("<gold>Zone Selector"));
        meta.lore(List.of(MessageFormatter.deserialize("<gray>Left click: first point"), MessageFormatter.deserialize("<gray>Right click: second point"), MessageFormatter.deserialize("<gray>Sneak-air click: save")));
        meta.getPersistentDataContainer().set(selectorKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta); return item;
    }
    public boolean isSelector(ItemStack item) { return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(selectorKey, PersistentDataType.BYTE); }
    public boolean isRouteSelecting(UUID uuid) { Selection selection = selections.get(uuid); return selection != null && selection.routeId != null; }
    /** One-shot guard used when this module dispatches the server's real /spawn command after a successful channel. */
    public boolean consumeSpawnDispatchBypass(UUID uuid) { return spawnDispatchBypass.remove(uuid); }
    public String beginRegionSelection(Player player, ZoneType type, String id) {
        String normalized = ZoneRegion.normalizeId(id);
        if (normalized == null) return "invalid";
        selections.put(player.getUniqueId(), Selection.region(type, normalized));
        giveSelector(player); return "ok";
    }
    public String beginRouteSelection(Player player, ZoneType type, String regionId, String routeId) {
        ZoneRegion region = region(regionId);
        if (region == null || region.type() != type) return "missing-region";
        String normalized = ZoneRegion.normalizeId(routeId);
        if (normalized == null) return "invalid";
        selections.put(player.getUniqueId(), Selection.route(type, region.id(), normalized));
        giveSelector(player); return "ok";
    }
    private void giveSelector(Player player) { player.getInventory().addItem(selectorItem()).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left)); }
    public String selectCorner(Player player, Location location, boolean first) {
        Selection selection = selections.get(player.getUniqueId()); if (selection == null) return "none";
        if (!sameWorld(selection, location)) { selection.clear(); return "world"; }
        if (first) selection.first = location.toBlockLocation(); else selection.second = location.toBlockLocation();
        return "ok";
    }
    public String addRoutePoint(Player player, Location location) {
        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.routeId == null) return "none";
        ZoneRegion region = region(selection.regionId);
        if (region == null || !region.contains(location)) return "outside";
        selection.points.add(new ZoneRoute.Waypoint(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
        return "ok";
    }
    public String removeRoutePoint(Player player) { Selection s=selections.get(player.getUniqueId()); if(s==null||s.routeId==null||s.points.isEmpty())return "none"; s.points.removeLast(); return "ok"; }
    public String finishSelection(Player player) {
        Selection s=selections.remove(player.getUniqueId()); if(s==null) return "none";
        if (s.routeId != null) return saveRoute(s);
        if (s.first == null || s.second == null || s.first.getWorld()==null || !s.first.getWorld().equals(s.second.getWorld())) return "incomplete";
        ZoneRegion candidate=new ZoneRegion(s.regionId,s.type,s.first.getWorld().getName(),s.first.getBlockX(),s.first.getBlockY(),s.first.getBlockZ(),s.second.getBlockX(),s.second.getBlockY(),s.second.getBlockZ());
        ZoneRegion conflict=regions.values().stream().filter(r->!r.id().equals(candidate.id())&&r.overlaps(candidate)).findFirst().orElse(null);
        if(conflict!=null)return "overlap:"+conflict.id();
        regions.put(candidate.id(),candidate); persist(() -> storage.upsertRegion(candidate)); return "ok";
    }
    private boolean sameWorld(Selection s, Location location) { return s.first==null || (location.getWorld()!=null&&s.first.getWorld()!=null&&s.first.getWorld().equals(location.getWorld())); }
    private String saveRoute(Selection s) {
        ZoneRegion region=region(s.regionId); if(region==null||s.points.size()<2)return "incomplete";
        for(ZoneRoute.Waypoint point:s.points) { World world=Bukkit.getWorld(point.world()); if(world==null||!region.contains(point.location(world)))return "outside"; }
        for(int i=1;i<s.points.size();i++)if(!segmentInside(region,s.points.get(i-1),s.points.get(i)))return "outside";
        ZoneRoute route=new ZoneRoute(s.routeId,region.id(),true,config(region.type()).routeSpeed(),true,s.points); routes.put(route.id(),route); persist(()->storage.upsertRoute(route,encodeRoute(route))); return "ok";
    }
    private boolean segmentInside(ZoneRegion region, ZoneRoute.Waypoint a, ZoneRoute.Waypoint b) {
        World world=Bukkit.getWorld(a.world()); if(world==null||!a.world().equals(b.world()))return false;
        double length=Math.sqrt(Math.pow(a.x()-b.x(),2)+Math.pow(a.y()-b.y(),2)+Math.pow(a.z()-b.z(),2));
        for(double t=0;t<=1D;t+=1D/Math.max(1D,Math.ceil(length/0.5D)))if(!region.contains(new Location(world,a.x()+(b.x()-a.x())*t,a.y()+(b.y()-a.y())*t,a.z()+(b.z()-a.z())*t)))return false;
        return true;
    }
    public boolean deleteRegion(String id) { ZoneRegion removed=regions.remove(ZoneRegion.normalizeId(id)); if(removed==null)return false; routes.values().removeIf(route->route.regionId().equals(removed.id())); persist(()->storage.deleteRegion(removed.id())); return true; }
    public boolean deleteRoute(String id) { ZoneRoute removed=routes.remove(ZoneRegion.normalizeId(id));if(removed==null)return false;persist(()->storage.deleteRoute(removed.id()));return true; }
    /** Staff preview uses the same server-authoritative path as a real entry, but does not create a Riftlands session. */
    public boolean previewRoute(Player player, String id) {
        ZoneRoute route = routes.get(ZoneRegion.normalizeId(id)); if (route == null || route.waypoints().size() < 2) return false;
        ZoneRegion region = region(route.regionId()); World world = region == null ? null : Bukkit.getWorld(region.world());
        if (world == null) return false;
        ZoneRoute.Waypoint first = route.waypoints().getFirst();
        if (!player.teleport(first.location(world))) return false;
        FlightState before = new FlightState(player.getAllowFlight(), player.isFlying(), player.getFlySpeed());
        player.setAllowFlight(true); player.setFlying(true);
        flights.put(player.getUniqueId(), new Flight(player.getUniqueId(), region.type(), route, 0, 0D, before));
        return true;
    }

    // ---- entry, exits, guided flight ------------------------------------
    public String requestEntry(Player player, ZoneType type) {
        if (regions(type).isEmpty()) return "unconfigured";
        if (combat.isTagged(player.getUniqueId())) return "combat";
        PlayerState state=state(player); long remaining=state.cooldown(type)-System.currentTimeMillis();
        if(remaining>0)return "cooldown:"+Math.ceil(remaining/1000D);
        return "ok";
    }
    public String beginEntry(Player player, ZoneType type) {
        String allowed=requestEntry(player,type); if(!"ok".equals(allowed))return allowed;
        entries.put(player.getUniqueId(),new Countdown(type,player.getLocation().clone(),System.currentTimeMillis()+config(type).countdownSeconds()*1000L));
        return "ok";
    }
    public void cancelEntry(Player player,String reason) { if(entries.remove(player.getUniqueId())!=null)player.sendMessage(messages.get(player,"zones.entry-cancelled","reason",reason)); }
    public String beginExit(Player player) {
        ZoneRegion region=regionAt(player.getLocation()); if(region==null)return "not-zone"; if(combat.isTagged(player.getUniqueId()))return "combat";
        exits.put(player.getUniqueId(),new Countdown(region.type(),player.getLocation().clone(),System.currentTimeMillis()+config(region.type()).exitSeconds()*1000L)); return "ok";
    }
    public void cancelExit(Player player,String reason){if(exits.remove(player.getUniqueId())!=null)player.sendMessage(messages.get(player,"zones.spawn-cancelled","reason",reason));}
    public boolean startFlight(Player player, ZoneType type) {
        if(combat.isTagged(player.getUniqueId()))return false;
        ZoneRoute route=chooseRoute(player,type); if(route==null)return false;
        FlightState before=new FlightState(player.getAllowFlight(),player.isFlying(),player.getFlySpeed());
        player.setAllowFlight(true); player.setFlying(true);
        flights.put(player.getUniqueId(),new Flight(player.getUniqueId(),type,route,0,0D,before));
        if(type==ZoneType.RIFTLANDS){PlayerState state=state(player); if(state.sessionId==null){state.sessionId=UUID.randomUUID().toString();persistPlayer(state);}}
        player.sendMessage(messages.get(player,"zones.entered","zone",type.displayName())); return true;
    }
    /** Starts a Riftlands loot ledger after a separately validated portal arrival. */
    public void startPortalRiftSession(Player player) {
        PlayerState state = state(player);
        if (state.sessionId == null) {
            state.sessionId = UUID.randomUUID().toString();
            persistPlayer(state);
        }
    }
    private ZoneRoute chooseRoute(Player player, ZoneType type) {
        List<ZoneRoute> choices=regions(type).stream().flatMap(region->routes(region).stream()).filter(ZoneRoute::enabled).filter(route->route.waypoints().size()>1).toList();
        if(choices.isEmpty())return null;
        if(type==ZoneType.HAVEN)return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        return choices.stream().max(Comparator.comparingDouble(route -> hostileDistance(route.waypoints().getFirst(),player))).orElse(choices.getFirst());
    }
    private double hostileDistance(ZoneRoute.Waypoint waypoint,Player player){World world=Bukkit.getWorld(waypoint.world());if(world==null)return 0D;double nearest=Double.MAX_VALUE;for(Player other:world.getPlayers())if(!other.equals(player)&&isHostile(player,other))nearest=Math.min(nearest,other.getLocation().distanceSquared(waypoint.location(world)));return nearest==Double.MAX_VALUE?Double.MAX_VALUE:nearest;}
    public void releaseFlight(UUID uuid, boolean slowFall) {
        Flight flight=flights.remove(uuid); Player player=Bukkit.getPlayer(uuid); if(flight==null||player==null)return;
        player.setFlying(false); player.setAllowFlight(flight.before.allowFlight());
        if (flight.before.allowFlight() && flight.before.flying()) player.setFlying(true);
        player.setFlySpeed(flight.before.flySpeed());
        if(slowFall){player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING,Integer.MAX_VALUE,0,false,false,false));slowFalling.add(uuid);}
    }
    public void releaseFlightFromHit(Player player){if(flights.containsKey(player.getUniqueId()))releaseFlight(player.getUniqueId(),true);}
    public void recordFlightDisconnect(Player player) { Flight flight=flights.get(player.getUniqueId()); if(flight==null||combat.isTagged(player.getUniqueId()))return; ZoneRegion region=region(flight.route.regionId()); if(region==null)return; Location safe=safeGround(region,player.getLocation(),0); releaseFlight(player.getUniqueId(),false); persist(()->storage.saveFlightReturn(player.getUniqueId(),region.id(),safe.getWorld().getName(),safe.getX(),safe.getY(),safe.getZ())); }
    public void returnAfterFlightDisconnect(Player player) { try{ZoneStorage.FlightReturn row=storage.takeFlightReturn(player.getUniqueId());if(row==null)return;World world=Bukkit.getWorld(row.world());ZoneRegion region=region(row.region());if(world==null||region==null)return;Location safe=safeGround(region,new Location(world,row.x(),row.y(),row.z()),0);Bukkit.getScheduler().runTask(plugin,()->player.teleport(safe));}catch(SQLException e){plugin.getLogger().log(Level.WARNING,"Could not restore zone flight landing",e);} }

    // ---- mobs, loot, progression, sessions ------------------------------
    public void handleZoneDeath(LivingEntity entity, Player killer, List<ItemStack> vanillaDrops) {
        String raw=entity.getPersistentDataContainer().get(zoneMobKey,PersistentDataType.STRING); if(raw==null)return;
        String mobRegion = entity.getPersistentDataContainer().get(mobRegionKey, PersistentDataType.STRING);
        if (mobRegion != null) { Set<UUID> ids = zoneMobsByRegion.get(mobRegion); if (ids != null) ids.remove(entity.getUniqueId()); }
        vanillaDrops.clear(); ZoneType type; try{type=ZoneType.valueOf(raw);}catch(Exception ignored){return;}
        if(killer==null)return; PlayerState state=state(killer); if(type==ZoneType.HAVEN)state.havenKills++;else state.riftKills++;persistPlayer(state);
        if(isEventActive()){double points=type==ZoneType.HAVEN?config(type).havenScore():config(type).riftScore();Score previous=scores.get(killer.getUniqueId());Score score=new Score(killer.getUniqueId(),killer.getName(),(previous==null?0D:previous.score())+points,previous==null?System.currentTimeMillis():previous.reachedAt());scores.put(killer.getUniqueId(),score);long start=currentEventStart();persist(()->storage.upsertScore(start,new ZoneStorage.ScoreRow(score.uuid(),score.name(),score.score(),score.reachedAt())));}
        List<ItemStack> rewards=rollLoot(killer,type); if(!rewards.isEmpty())deliverRewards(killer,type,rewards);
    }
    private List<ItemStack> rollLoot(Player player,ZoneType type){List<LootEntry> pool=loot.getOrDefault(type,List.of());if(pool.isEmpty())return List.of();List<ItemStack> result=new ArrayList<>();Set<String> rareSeen=new HashSet<>();int guaranteed=(int)Math.floor(amplification(player,type)/100D);double chance=amplification(player,type)/100D-guaranteed;int rolls=1+guaranteed+(chance>0D&&ThreadLocalRandom.current().nextDouble()<chance?1:0);for(int i=0;i<rolls;i++)for(LootEntry entry:pool)if(ThreadLocalRandom.current().nextDouble()*100D<entry.chance()){String key=Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(new ItemStack[]{entry.item()}));if(entry.chance()<config(type).rareThreshold()&& !rareSeen.add(key))continue;result.add(entry.item().clone());}return result;}
    private void deliverRewards(Player player,ZoneType type,List<ItemStack> rewards){PlayerState state=state(player);List<ItemStack> tagged=new ArrayList<>();for(ItemStack reward:rewards){ItemStack copy=reward.clone();if(type==ZoneType.RIFTLANDS&&state.sessionId!=null){ItemMeta meta=copy.getItemMeta();meta.getPersistentDataContainer().set(sessionKey,PersistentDataType.STRING,state.sessionId);copy.setItemMeta(meta);}tagged.add(copy);}BackpackManager.EquippedBackpack bag=backpacks.equippedBackpack(player);List<ItemStack> leftovers=bag==null?tagged:backpacks.storeExact(bag,tagged);if(bag!=null){player.getInventory().setItemInOffHand(bag.item().clone());player.updateInventory();}for(ItemStack item:leftovers)player.getInventory().addItem(item).values().forEach(left->player.getWorld().dropItemNaturally(player.getLocation(),left));player.sendMessage(messages.get(player,"zones.loot-reward","amount",String.valueOf(rewards.size())));}
    public List<ItemStack> takeRiftSessionLoot(Player player){PlayerState state=state(player);if(state.sessionId==null)return List.of();String id=state.sessionId;List<ItemStack> drops=new ArrayList<>();for(int slot=0;slot<player.getInventory().getSize();slot++){ItemStack item=player.getInventory().getItem(slot);if(matchesSession(item,id)){drops.add(item.clone());player.getInventory().setItem(slot,null);}}drops.addAll(backpacks.removeMarkedFromEquipped(player,sessionKey,id));state.sessionId=null;persistPlayer(state);return drops;}
    public void secureRiftSession(Player player){PlayerState state=state(player);if(state.sessionId!=null){state.sessionId=null;persistPlayer(state);player.sendMessage(messages.get(player,"zones.rift-loot-secured"));}}
    public boolean isSessionItem(Player player,ItemStack item){PlayerState state=state(player);return state.sessionId!=null&&matchesSession(item,state.sessionId);}
    private boolean matchesSession(ItemStack item,String id){return item!=null&&item.hasItemMeta()&&id.equals(item.getItemMeta().getPersistentDataContainer().get(sessionKey,PersistentDataType.STRING));}
    public void setDeathCooldown(Player player,ZoneType type){PlayerState state=state(player);long seconds=config(type).deathCooldownSeconds();state.setCooldown(type,seconds<=0?0:System.currentTimeMillis()+seconds*1000L);persistPlayer(state);}
    public double progressionBoost(Player player,ZoneType type){long kills=state(player).kills(type);double value=0D;for(var e:config(type).milestones().entrySet())if(kills>=e.getKey())value=e.getValue();return value;}
    public double amplification(Player player,ZoneType type){double value=progressionBoost(player,type)+winnerBoost(player);double backpack=backpacks.equippedDropBonusPercent(player);if(backpack>0)value+=backpack;BoosterService service=boosters;if(service!=null)value+=service.contributions(player,me.vertex.core.booster.BoosterCategory.MOB_DROP).stream().filter(c->c.active()&&!"backpack".equals(c.sourceId())&&!"zone".equals(c.sourceId())).mapToDouble(me.vertex.core.booster.BoosterContribution::percent).sum();double cap=config(type).amplificationCap();return cap<=0D?value:Math.min(value,cap);}
    public double winnerBoost(Player player){return state(player).winnerBoost;}

    // ---- ticket -----------------------------------------------------------
    public ItemStack createTicket(){ZoneConfig c=config(ZoneType.RIFTLANDS);ItemStack item=new ItemStack(c.ticketMaterial());ItemMeta meta=item.getItemMeta();meta.displayName(MessageFormatter.deserialize(c.ticketName()));meta.lore(c.ticketLore().stream().map(MessageFormatter::deserialize).toList());if(c.ticketModel()!=null)meta.setCustomModelData(c.ticketModel());if(c.ticketGlow()){meta.addEnchant(Enchantment.UNBREAKING,1,true);meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);}meta.getPersistentDataContainer().set(ticketKey,PersistentDataType.BYTE,(byte)1);item.setItemMeta(meta);trackedItems.ensureInstanceId(item,ItemKind.RIFTLANDS_TICKET);return item;}
    public String useTicket(Player player,ItemStack held){ZoneRegion region=regionAt(player.getLocation());if(region==null||region.type()!=ZoneType.RIFTLANDS)return "not-rift";if(!combat.isTagged(player.getUniqueId()))return "not-combat";Location target=findSafeTicketLocation(player,region);if(target==null)return "no-safe";if(!player.teleport(target))return "no-safe";combat.clear(player.getUniqueId());held.setAmount(held.getAmount()-1);player.sendMessage(messages.get(player,"zones.ticket-success"));return "ok";}
    private Location findSafeTicketLocation(Player player,ZoneRegion region){ZoneConfig c=config(ZoneType.RIFTLANDS);List<Player> hostiles=player.getWorld().getPlayers().stream().filter(other->!other.equals(player)&&isHostile(player,other)).toList();Location best=null;double bestScore=-1D;for(int i=0;i<c.ticketCandidates();i++){int x=ThreadLocalRandom.current().nextInt(region.minX()+c.ticketBorder(),region.maxX()-c.ticketBorder()+1);int z=ThreadLocalRandom.current().nextInt(region.minZ()+c.ticketBorder(),region.maxZ()-c.ticketBorder()+1);Location candidate=safeGround(region,new Location(player.getWorld(),x,player.getLocation().getY(),z),c.ticketRouteAvoid());if(candidate==null)continue;double min=Double.MAX_VALUE;for(Player hostile:hostiles)min=Math.min(min,candidate.distanceSquared(hostile.getLocation()));if(min>bestScore){bestScore=min;best=candidate;}}return best;}
    private boolean isHostile(Player a,Player b){return !me.vertex.core.factions.FactionsHook.isSameFaction(a,b)&&!me.vertex.core.factions.FactionsHook.isAllyFaction(me.vertex.core.factions.FactionsHook.getFactionId(a),me.vertex.core.factions.FactionsHook.getFactionId(b));}

    // ---- event / scheduled tick -----------------------------------------
    private void tick(){long now=System.currentTimeMillis();tickCountdowns(now);tickFlights();tickSlowFalling();tickMobs();tickEvent(now);}
    private void tickCountdowns(long now){for(var entry:List.copyOf(entries.entrySet())){Player player=Bukkit.getPlayer(entry.getKey());Countdown countdown=entry.getValue();if(player==null||invalidCountdown(player,countdown)){if(player!=null)cancelEntry(player,"movement/combat");else entries.remove(entry.getKey());continue;}long remaining=Math.max(0L,countdown.until-now);if(remaining==0L){entries.remove(entry.getKey());if(!startFlight(player,countdown.type))player.sendMessage(messages.get(player,"zones.no-route"));}else if(remaining%1000L<1000L){player.sendMessage(messages.get(player,"zones.entry-countdown","zone",countdown.type.displayName(),"seconds",String.valueOf((remaining+999)/1000)));}}
        for(var entry:List.copyOf(exits.entrySet())){Player player=Bukkit.getPlayer(entry.getKey());Countdown countdown=entry.getValue();if(player==null||invalidCountdown(player,countdown)){if(player!=null)cancelExit(player,"movement/combat");else exits.remove(entry.getKey());continue;}long remaining=Math.max(0L,countdown.until-now);if(remaining==0L){exits.remove(entry.getKey());secureRiftSession(player);spawnDispatchBypass.add(player.getUniqueId());Bukkit.dispatchCommand(player,"spawn");}else if(remaining%1000L<1000L)player.sendMessage(messages.get(player,"zones.spawn-countdown","seconds",String.valueOf((remaining+999)/1000)));}}
    private boolean invalidCountdown(Player player,Countdown countdown){return combat.isTagged(player.getUniqueId())||!Objects.equals(player.getWorld(),countdown.origin.getWorld())||player.getLocation().distanceSquared(countdown.origin)>0.01D;}
    private void tickFlights(){for(Flight flight:List.copyOf(flights.values())){Player player=Bukkit.getPlayer(flight.playerId());if(player==null){flights.remove(flight.playerId());continue;}List<ZoneRoute.Waypoint> points=flight.route.waypoints();if(flight.index>=points.size()-1){releaseFlight(player.getUniqueId(),true);continue;}ZoneRoute.Waypoint a=points.get(flight.index),b=points.get(flight.index+1);World world=Bukkit.getWorld(a.world());if(world==null||!a.world().equals(b.world())){releaseFlight(player.getUniqueId(),true);continue;}double distance=Math.max(.001D,Math.sqrt(Math.pow(b.x()-a.x(),2)+Math.pow(b.y()-a.y(),2)+Math.pow(b.z()-a.z(),2)));double step=flight.progress+flight.route.speed()/20D/distance;int index=flight.index;while(step>=1D&&index<points.size()-1){step-=1D;index++;if(index>=points.size()-1)break;a=points.get(index);b=points.get(Math.min(index+1,points.size()-1));}if(index>=points.size()-1){releaseFlight(player.getUniqueId(),true);continue;}Location target=new Location(world,a.x()+(b.x()-a.x())*step,a.y()+(b.y()-a.y())*step,a.z()+(b.z()-a.z())*step,b.yaw(),b.pitch());ZoneRegion region=region(flight.route.regionId());if(region==null||!region.contains(target)){releaseFlight(player.getUniqueId(),true);continue;}player.teleport(target);flights.put(player.getUniqueId(),new Flight(player.getUniqueId(),flight.type,flight.route,index,step,flight.before));}}
    private void tickSlowFalling(){for(UUID uuid:List.copyOf(slowFalling)){Player player=Bukkit.getPlayer(uuid);if(player==null||player.isOnGround()){if(player!=null)player.removePotionEffect(PotionEffectType.SLOW_FALLING);slowFalling.remove(uuid);}}}
    private void tickMobs(){for(ZoneType type:ZoneType.values()){Map<String,List<Player>> byRegion=new HashMap<>();for(Player p:Bukkit.getOnlinePlayers()){ZoneRegion r=regionAt(p.getLocation());if(r!=null&&r.type()==type)byRegion.computeIfAbsent(r.id(),ignored->new ArrayList<>()).add(p);}for(var entry:byRegion.entrySet())maintainMobs(region(entry.getKey()),entry.getValue());for(ZoneRegion r:regions(type))if(!byRegion.containsKey(r.id()))despawnZoneMobs(r);}}
    private void maintainMobs(ZoneRegion region,List<Player> active){if(region==null)return;ZoneConfig c=config(region.type());List<List<Player>> clusters=clusters(active,c.clusterRadius());for(List<Player> cluster:clusters){Player anchor=cluster.getFirst();int cap=Math.min(c.maxLocalMobs(),c.baseLocalMobs()+Math.max(0,cluster.size()-1)*c.additionalPerPlayer());int nearby=(int)anchor.getWorld().getNearbyEntities(anchor.getLocation(),c.maxSpawnDistance(),c.maxSpawnDistance(),c.maxSpawnDistance(),entity->isZoneMob(entity)&&region.id().equals(entity.getPersistentDataContainer().get(mobRegionKey,PersistentDataType.STRING))).size();for(int count=nearby;count<Math.min(cap,nearby+3);count++){Location at=randomMobLocation(region,anchor,c);if(at==null)break;spawnMob(region,at);}}}
    private List<List<Player>> clusters(List<Player> active,double radius){List<List<Player>> result=new ArrayList<>();Set<UUID> used=new HashSet<>();for(Player root:active){if(!used.add(root.getUniqueId()))continue;List<Player> cluster=new ArrayList<>();cluster.add(root);for(Player other:active)if(used.add(other.getUniqueId())&&root.getLocation().distanceSquared(other.getLocation())<=radius*radius)cluster.add(other);result.add(cluster);}return result;}
    private Location randomMobLocation(ZoneRegion region,Player player,ZoneConfig config){for(int tries=0;tries<12;tries++){double angle=ThreadLocalRandom.current().nextDouble(Math.PI*2D),distance=ThreadLocalRandom.current().nextDouble(config.minSpawnDistance(),config.maxSpawnDistance());Location base=player.getLocation().clone().add(Math.cos(angle)*distance,0,Math.sin(angle)*distance);Location safe=safeGround(region,base,0);if(safe!=null&&safe.distanceSquared(player.getLocation())>=config.minSpawnDistance()*config.minSpawnDistance())return safe;}return null;}
    private Location safeGround(ZoneRegion region,Location near,int routeAvoid){if(near==null||near.getWorld()==null)return null;World world=near.getWorld();int x=near.getBlockX(),z=near.getBlockZ();int y=Math.min(region.maxY()-2,world.getHighestBlockYAt(x,z)+1);Location result=new Location(world,x+.5,y,z+.5);if(!region.contains(result)||!result.getBlock().isPassable()||!result.clone().add(0,1,0).getBlock().isPassable()||!result.clone().add(0,-1,0).getBlock().getType().isSolid())return null;if(routeAvoid>0)for(ZoneRoute route:routes(region))for(ZoneRoute.Waypoint point:route.waypoints())if(result.distanceSquared(point.location(world))<routeAvoid*(double)routeAvoid)return null;return result;}
    private void spawnMob(ZoneRegion region,Location at){List<MobDefinition> pool=config(region.type()).mobs();if(pool.isEmpty())return;double total=pool.stream().filter(definition -> definition.enabled).mapToDouble(definition -> definition.weight).sum();if(total<=0D)return;double roll=ThreadLocalRandom.current().nextDouble(total);MobDefinition selected=pool.getFirst();for(MobDefinition definition:pool)if(definition.enabled&&(roll-=definition.weight)<=0D){selected=definition;break;}Entity raw=at.getWorld().spawnEntity(at,selected.type);if(!(raw instanceof LivingEntity entity)){raw.remove();return;}zoneMobsByRegion.computeIfAbsent(region.id(),ignored->ConcurrentHashMap.newKeySet()).add(entity.getUniqueId());entity.setCanPickupItems(false);entity.getPersistentDataContainer().set(zoneMobKey,PersistentDataType.STRING,region.type().name());entity.getPersistentDataContainer().set(mobRegionKey,PersistentDataType.STRING,region.id());entity.getPersistentDataContainer().set(mobDefinitionKey,PersistentDataType.STRING,selected.id);double health=ThreadLocalRandom.current().nextDouble(selected.minHealth,Math.max(selected.minHealth+.001D,selected.maxHealth));if(entity.getAttribute(Attribute.MAX_HEALTH)!=null)entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(health);entity.setHealth(Math.min(health,entity.getMaxHealth()));MobProfile profile=selected.profiles.get(ThreadLocalRandom.current().nextInt(selected.profiles.size()));entity.customName(MessageFormatter.deserialize(profile.name));entity.setCustomNameVisible(true);if(entity.getAttribute(Attribute.ATTACK_DAMAGE)!=null)entity.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(profile.damage);}
    private void despawnZoneMobs(ZoneRegion region){Set<UUID> ids=zoneMobsByRegion.remove(region.id());if(ids==null)return;for(UUID id:ids){Entity entity=Bukkit.getEntity(id);if(entity!=null&&isZoneMob(entity))entity.remove();}}
    private void tickEvent(long now){long start=currentEventStart();boolean active=now>=start&&now<start+config(ZoneType.HAVEN).eventDurationMillis();if(active&&loadedScoreEvent!=start)restoreCurrentScores();if(active&&lastAnnouncedEvent!=start){lastAnnouncedEvent=start;for(Player player:Bukkit.getOnlinePlayers())player.sendMessage(messages.get(player,"zones.event-start"));}if(!active&&loadedScoreEvent==start&&lastFinishedEvent!=start){finishEvent(start);lastFinishedEvent=start;}if(now-lastBossRefresh>=config(ZoneType.HAVEN).bossRefreshMillis()){lastBossRefresh=now;updateBossBar(active,start,now);}}
    private long currentEventStart(){long cycle=Math.max(60_000L,config(ZoneType.HAVEN).eventCycleMillis());long now=System.currentTimeMillis();return cycleAnchor+Math.floorDiv(now-cycleAnchor,cycle)*cycle;}
    private boolean isEventActive(){long now=System.currentTimeMillis(),start=currentEventStart();return now>=start&&now<start+config(ZoneType.HAVEN).eventDurationMillis();}
    private void restoreCurrentScores(){long start=currentEventStart();scores.clear();try{for(ZoneStorage.ScoreRow row:storage.loadScores(start))scores.put(row.uuid(),new Score(row.uuid(),row.name(),row.score(),row.reachedAt()));loadedScoreEvent=start;}catch(SQLException e){plugin.getLogger().log(Level.WARNING,"Could not restore zone event scores",e);}}
    private void finishEvent(long start){List<Score> top=topScores();double[] rewards={config(ZoneType.HAVEN).firstBoost(),config(ZoneType.HAVEN).secondBoost(),config(ZoneType.HAVEN).thirdBoost()};for(PlayerState state:players.values()){state.winnerBoost=0D;state.winnerCycle=start;}List<ZoneStorage.WinnerRow> winners=new ArrayList<>();for(int i=0;i<Math.min(3,top.size());i++){Player online=Bukkit.getPlayer(top.get(i).uuid());PlayerState state=online!=null?state(online):state(top.get(i).uuid(),top.get(i).name());state.winnerBoost=rewards[i];state.winnerCycle=start;winners.add(new ZoneStorage.WinnerRow(top.get(i).uuid(),top.get(i).name(),rewards[i]));if(online!=null)online.sendMessage(messages.get(online,"zones.event-winner","place",String.valueOf(i+1),"boost",PERCENT.format(rewards[i])));}persist(()->storage.replaceWinnerBoosts(start,winners));for(Player p:Bukkit.getOnlinePlayers())p.sendMessage(messages.get(p,"zones.event-end"));}
    private List<Score> topScores(){return scores.values().stream().sorted(Comparator.comparingDouble(Score::score).reversed().thenComparingLong(Score::reachedAt).thenComparing(s->s.uuid().toString())).limit(3).toList();}
    private void updateBossBar(boolean active,long start,long now){if(!active){for(Player p:Bukkit.getOnlinePlayers())eventBar.removePlayer(p);return;}List<Score> top=topScores();String text="Mob Kill Event | "+formatDuration(start+config(ZoneType.HAVEN).eventDurationMillis()-now);for(int i=0;i<3;i++)text+=" | #"+(i+1)+" "+(i<top.size()?top.get(i).name()+": "+PERCENT.format(top.get(i).score()):"-");eventBar.setTitle(text);eventBar.setProgress(Math.max(0D,Math.min(1D,(start+config(ZoneType.HAVEN).eventDurationMillis()-now)/(double)config(ZoneType.HAVEN).eventDurationMillis())));for(Player p:Bukkit.getOnlinePlayers()){if(isIn(p,ZoneType.HAVEN)||isIn(p,ZoneType.RIFTLANDS))eventBar.addPlayer(p);else eventBar.removePlayer(p);}}

    // ---- persistence/config/inspection ----------------------------------
    public PlayerState state(Player player){return state(player.getUniqueId(),player.getName());}
    private PlayerState state(UUID uuid, String name){return players.computeIfAbsent(uuid,key->{try{ZoneStorage.PlayerRow row=storage.loadPlayer(key);return row==null?new PlayerState(key,name):PlayerState.from(row);}catch(SQLException e){plugin.getLogger().log(Level.WARNING,"Could not load zone player state",e);return new PlayerState(key,name);}});}
    private void persistPlayer(PlayerState state){persist(()->storage.upsertPlayer(state.toRow()));}
    private void persistLoot(ZoneType type,List<LootEntry> entries){List<ZoneStorage.LootRow> rows=new ArrayList<>();for(int i=0;i<entries.size();i++)rows.add(new ZoneStorage.LootRow(i,ItemStack.serializeItemsAsBytes(new ItemStack[]{entries.get(i).item()}),entries.get(i).chance()));persist(()->storage.replaceLoot(type,rows));}
    private void persist(SqlAction action){Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{try{action.run();}catch(Exception e){plugin.getLogger().log(Level.WARNING,"Failed to persist zone state",e);}});}
    @FunctionalInterface private interface SqlAction { void run() throws Exception; }
    public ZoneConfig config(ZoneType type){return configs.getOrDefault(type,ZoneConfig.defaults(type));}
    public List<LootEntry> loot(ZoneType type){return loot.getOrDefault(type,List.of()).stream().map(entry->new LootEntry(entry.item().clone(),entry.chance())).toList();}
    public void saveLoot(ZoneType type,List<LootEntry> entries){loot.put(type,new ArrayList<>(entries));persistLoot(type,entries);}
    public void normalizeLootItem(ZoneType type, ItemStack item) { if (item != null && !item.isEmpty() && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().get(chanceKey, PersistentDataType.DOUBLE) == null) setLootChance(item, config(type).defaultLootChance()); }
    public double lootChance(ItemStack item){if(item==null||!item.hasItemMeta())return config(ZoneType.HAVEN).defaultLootChance();Double value=item.getItemMeta().getPersistentDataContainer().get(chanceKey,PersistentDataType.DOUBLE);return value==null?config(ZoneType.HAVEN).defaultLootChance():Math.max(0D,Math.min(100D,value));}
    public void setLootChance(ItemStack item,double chance){if(item==null)return;ItemMeta meta=item.getItemMeta();meta.getPersistentDataContainer().set(chanceKey,PersistentDataType.DOUBLE,Math.max(0D,Math.min(100D,chance)));item.setItemMeta(meta);}
    public void resetSeason(){for(PlayerState state:players.values()){state.havenKills=0;state.riftKills=0;state.havenCooldown=0;state.riftCooldown=0;state.sessionId=null;persistPlayer(state);}persist(()->storage.resetSeason());}
    public List<Score> topScoresForDisplay(){return topScores();}
    public String eventTopName(int place) { List<Score> top=topScores(); return place >= 1 && place <= top.size() ? top.get(place-1).name() : "-"; }
    public double eventTopScore(int place) { List<Score> top=topScores(); return place >= 1 && place <= top.size() ? top.get(place-1).score() : 0D; }
    public boolean eventActive() { return isEventActive(); }
    public double eventScore(Player player) { Score score = scores.get(player.getUniqueId()); return score == null ? 0D : score.score(); }
    public int eventRank(Player player) { List<Score> ordered = scores.values().stream().sorted(Comparator.comparingDouble(Score::score).reversed().thenComparingLong(Score::reachedAt).thenComparing(s -> s.uuid().toString())).toList(); for (int i=0;i<ordered.size();i++) if (ordered.get(i).uuid().equals(player.getUniqueId())) return i+1; return 0; }
    public long eventRemainingSeconds(){long now=System.currentTimeMillis(),start=currentEventStart();return isEventActive()?Math.max(0,(start+config(ZoneType.HAVEN).eventDurationMillis()-now+999)/1000):(start+config(ZoneType.HAVEN).eventCycleMillis()-now+999)/1000;}
    public String currentZone(Player player){ZoneRegion r=regionAt(player.getLocation());return r==null?"None":r.type().displayName();}
    public long kills(Player player,ZoneType type){return state(player).kills(type);}
    public long nextMilestone(Player player,ZoneType type){long kills=kills(player,type);return config(type).milestones().keySet().stream().filter(value->value>kills).findFirst().orElse(0L);}
    public long cooldownRemaining(Player player,ZoneType type){return Math.max(0,(state(player).cooldown(type)-System.currentTimeMillis()+999)/1000);}
    public void forceStartEvent() { cycleAnchor = System.currentTimeMillis(); scores.clear(); loadedScoreEvent = cycleAnchor; lastFinishedEvent = Long.MIN_VALUE; persist(() -> storage.saveLong("cycle_anchor", cycleAnchor)); }
    public void forceStopEvent() { cycleAnchor = System.currentTimeMillis() - config(ZoneType.HAVEN).eventDurationMillis(); persist(() -> storage.saveLong("cycle_anchor", cycleAnchor)); updateBossBar(false, currentEventStart(), System.currentTimeMillis()); }

    private ZoneConfig readConfig(ZoneType type){String name=type.configKey()+".yml";File file=new File(plugin.getDataFolder(),name);if(!file.exists())plugin.saveResource(name,false);YamlConfiguration yaml=YamlConfiguration.loadConfiguration(file);return ZoneConfig.read(type,yaml,plugin);}
    private Optional<ZoneRoute> decodeRoute(ZoneStorage.RouteRow row){try{List<ZoneRoute.Waypoint> points=new ArrayList<>();for(String raw:row.waypoints().split(";")){if(raw.isBlank())continue;String[] p=raw.split("\\|",-1);if(p.length!=6)return Optional.empty();points.add(new ZoneRoute.Waypoint(new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8),Double.parseDouble(p[1]),Double.parseDouble(p[2]),Double.parseDouble(p[3]),Float.parseFloat(p[4]),Float.parseFloat(p[5])));}return points.size()<2?Optional.empty():Optional.of(new ZoneRoute(row.id(),row.regionId(),row.enabled(),row.speed(),row.autoDrop(),points));}catch(Exception e){return Optional.empty();}}
    private String encodeRoute(ZoneRoute route){StringBuilder out=new StringBuilder();for(ZoneRoute.Waypoint p:route.waypoints())out.append(Base64.getUrlEncoder().withoutPadding().encodeToString(p.world().getBytes(StandardCharsets.UTF_8))).append('|').append(p.x()).append('|').append(p.y()).append('|').append(p.z()).append('|').append(p.yaw()).append('|').append(p.pitch()).append(';');return out.toString();}
    private static String formatDuration(long millis){long seconds=Math.max(0,millis/1000L);return String.format(Locale.ROOT,"%02d:%02d",seconds/60,seconds%60);}

    public record LootEntry(ItemStack item,double chance){ public LootEntry{item=item.clone();chance=Math.max(0D,Math.min(100D,chance));} }
    public record Score(UUID uuid,String name,double score,long reachedAt) { }
    private record Countdown(ZoneType type,Location origin,long until) { }
    private record FlightState(boolean allowFlight,boolean flying,float flySpeed) { }
    private record Flight(UUID playerId,ZoneType type,ZoneRoute route,int index,double progress,FlightState before) { }
    private static final class Selection { final ZoneType type;final String regionId;final String routeId;Location first,second;final List<ZoneRoute.Waypoint> points=new ArrayList<>();private Selection(ZoneType type,String regionId,String routeId){this.type=type;this.regionId=regionId;this.routeId=routeId;}static Selection region(ZoneType type,String id){return new Selection(type,id,null);}static Selection route(ZoneType type,String region,String route){return new Selection(type,region,route);}void clear(){first=null;second=null;points.clear();} }
    public static final class PlayerState { final UUID uuid;String name;long havenKills,riftKills,havenCooldown,riftCooldown;String sessionId;double winnerBoost;long winnerCycle;PlayerState(UUID uuid,String name){this.uuid=uuid;this.name=name;}static PlayerState from(ZoneStorage.PlayerRow row){PlayerState s=new PlayerState(row.uuid(),row.name());s.havenKills=row.havenKills();s.riftKills=row.riftKills();s.havenCooldown=row.havenCooldown();s.riftCooldown=row.riftCooldown();s.sessionId=row.sessionId();s.winnerBoost=row.winnerBoost();s.winnerCycle=row.winnerCycle();return s;}long kills(ZoneType type){return type==ZoneType.HAVEN?havenKills:riftKills;}long cooldown(ZoneType type){return type==ZoneType.HAVEN?havenCooldown:riftCooldown;}void setCooldown(ZoneType type,long value){if(type==ZoneType.HAVEN)havenCooldown=value;else riftCooldown=value;}ZoneStorage.PlayerRow toRow(){return new ZoneStorage.PlayerRow(uuid,name,havenKills,riftKills,havenCooldown,riftCooldown,sessionId,winnerBoost,winnerCycle);} }

    /** Parsed configuration with strict safe fallbacks for every gameplay setting. */
    public record ZoneConfig(int countdownSeconds,int exitSeconds,long deathCooldownSeconds,double routeSpeed,int minSpawnDistance,int maxSpawnDistance,int baseLocalMobs,int additionalPerPlayer,int maxLocalMobs,double clusterRadius,long eventCycleMillis,long eventDurationMillis,long bossRefreshMillis,double havenScore,double riftScore,double firstBoost,double secondBoost,double thirdBoost,double defaultLootChance,double rareThreshold,double amplificationCap,Map<Long,Double> milestones,List<MobDefinition> mobs,Material entryMaterial,Integer entryModel,String entryName,List<String> entryLore,String entryTitle,int entrySize,Material ticketMaterial,Integer ticketModel,String ticketName,List<String> ticketLore,boolean ticketGlow,double ticketChance,boolean ticketInLoot,int ticketCandidates,int ticketBorder,int ticketRouteAvoid) {
        public ZoneConfig {
            countdownSeconds = Math.max(1, countdownSeconds); exitSeconds = Math.max(1, exitSeconds);
            deathCooldownSeconds = Math.max(0L, deathCooldownSeconds); routeSpeed = Math.max(.05D, Math.min(8D, routeSpeed));
            minSpawnDistance = Math.max(1, minSpawnDistance); maxSpawnDistance = Math.max(minSpawnDistance + 1, maxSpawnDistance);
            baseLocalMobs = Math.max(0, baseLocalMobs); additionalPerPlayer = Math.max(0, additionalPerPlayer); maxLocalMobs = Math.max(1, maxLocalMobs); clusterRadius = Math.max(4D, clusterRadius);
            eventCycleMillis = Math.max(60_000L, eventCycleMillis); eventDurationMillis = Math.max(10_000L, Math.min(eventCycleMillis, eventDurationMillis)); bossRefreshMillis = Math.max(1_000L, bossRefreshMillis);
            defaultLootChance = clamp(defaultLootChance,0D,100D); rareThreshold = clamp(rareThreshold,0D,100D); amplificationCap = Math.max(0D, amplificationCap);
            milestones = Map.copyOf(milestones == null ? Map.of() : milestones); mobs = List.copyOf(mobs == null ? List.of() : mobs);
            entryMaterial = entryMaterial == null || entryMaterial.isAir() ? Material.EMERALD_BLOCK : entryMaterial; entryLore = List.copyOf(entryLore == null ? List.of() : entryLore); entryTitle = entryTitle == null ? "Zones" : entryTitle;
            ticketMaterial = ticketMaterial == null || ticketMaterial.isAir() ? Material.PAPER : ticketMaterial; ticketLore = List.copyOf(ticketLore == null ? List.of() : ticketLore);
            ticketCandidates = Math.max(1, ticketCandidates); ticketBorder = Math.max(0, ticketBorder); ticketRouteAvoid = Math.max(0, ticketRouteAvoid);
        }
        static ZoneConfig defaults(ZoneType type){return new ZoneConfig(5,type==ZoneType.HAVEN?10:20,120,0.8,20,80,15,8,75,80,120*60_000L,5*60_000L,2_000L,1D,1.5D,5D,3D,1D,20D,5D,0D,new LinkedHashMap<>(),List.of(),Material.EMERALD_BLOCK,null,"<green>Enter "+type.displayName(),List.of("<gray>Confirm entry"),"<dark_gray>Enter "+type.displayName(),27,Material.PAPER,null,"<aqua>Riftlands Ticket",List.of("<gray>Use while in Riftlands combat."),true,.75D,true,40,8,50);}
        static ZoneConfig read(ZoneType type,YamlConfiguration y,Plugin plugin){ZoneConfig d=defaults(type);ConfigurationSection mobRoot=y.getConfigurationSection("mobs");List<MobDefinition> mobs=new ArrayList<>();if(mobRoot!=null)for(String id:mobRoot.getKeys(false)){ConfigurationSection section=mobRoot.getConfigurationSection(id);if(section!=null)MobDefinition.read(id,section,plugin).ifPresent(mobs::add);}Map<Long,Double> milestones=new LinkedHashMap<>();ConfigurationSection progression=y.getConfigurationSection("progression.milestones");if(progression!=null)for(String key:progression.getKeys(false))try{milestones.put(Long.parseLong(key),Math.max(0D,progression.getDouble(key)));}catch(NumberFormatException ignored){plugin.getLogger().warning(type.configKey()+".yml: invalid milestone '"+key+"'.");}if(milestones.isEmpty())milestones.putAll(d.milestones);Material entry=material(y.getString("entry-gui.confirm-item.material"),d.entryMaterial,plugin,type);Material ticket=material(y.getString("riftlands-ticket.item.material"),d.ticketMaterial,plugin,type);return new ZoneConfig(Math.max(1,y.getInt("entry.countdown-seconds",d.countdownSeconds)),Math.max(1,y.getInt("exit.spawn-channel-seconds",d.exitSeconds)),Math.max(0,y.getLong("reentry-cooldown.on-death-seconds",d.deathCooldownSeconds)),Math.max(.05D,y.getDouble("routes.speed",d.routeSpeed)),Math.max(1,y.getInt("mob-spawning.min-spawn-distance",d.minSpawnDistance)),Math.max(2,y.getInt("mob-spawning.max-spawn-distance",d.maxSpawnDistance)),Math.max(0,y.getInt("mob-spawning.base-local-mobs",d.baseLocalMobs)),Math.max(0,y.getInt("mob-spawning.additional-mobs-per-player",d.additionalPerPlayer)),Math.max(1,y.getInt("mob-spawning.max-local-mobs",d.maxLocalMobs)),Math.max(4D,y.getDouble("mob-spawning.cluster-radius",d.clusterRadius)),Math.max(60_000L,y.getLong("kill-event.cycle-minutes",120)*60_000L),Math.max(10_000L,y.getLong("kill-event.duration-minutes",5)*60_000L),Math.max(1_000L,y.getLong("kill-event.bossbar.refresh-seconds",2)*1_000L),y.getDouble("kill-event.scoring.haven",d.havenScore),y.getDouble("kill-event.scoring.riftlands",d.riftScore),y.getDouble("kill-event.rewards.first",d.firstBoost),y.getDouble("kill-event.rewards.second",d.secondBoost),y.getDouble("kill-event.rewards.third",d.thirdBoost),clamp(y.getDouble("loot-pool.default-item-chance",d.defaultLootChance),0,100),clamp(y.getDouble("loot-pool.rare-multi-drop-threshold-percent",d.rareThreshold),0,100),Math.max(0D,y.getDouble("loot-pool.amplification-cap-percent",d.amplificationCap)),Map.copyOf(milestones),List.copyOf(mobs),entry,y.contains("entry-gui.confirm-item.custom-model-data")?y.getInt("entry-gui.confirm-item.custom-model-data"):null,y.getString("entry-gui.confirm-item.name",d.entryName),y.getStringList("entry-gui.confirm-item.lore").isEmpty()?d.entryLore:y.getStringList("entry-gui.confirm-item.lore"),y.getString("entry-gui.title",d.entryTitle),validSize(y.getInt("entry-gui.size",d.entrySize)),ticket,y.contains("riftlands-ticket.item.custom-model-data")?y.getInt("riftlands-ticket.item.custom-model-data"):null,y.getString("riftlands-ticket.item.name",d.ticketName),y.getStringList("riftlands-ticket.item.lore").isEmpty()?d.ticketLore:y.getStringList("riftlands-ticket.item.lore"),y.getBoolean("riftlands-ticket.item.glow",d.ticketGlow),clamp(y.getDouble("riftlands-ticket.default-loot-chance",d.ticketChance),0,100),y.getBoolean("riftlands-ticket.include-in-default-loot",d.ticketInLoot),Math.max(1,y.getInt("riftlands-ticket.teleport.candidates",d.ticketCandidates)),Math.max(0,y.getInt("riftlands-ticket.teleport.border-distance",d.ticketBorder)),Math.max(0,y.getInt("riftlands-ticket.teleport.entry-route-avoid-radius",d.ticketRouteAvoid)));}
        private static Material material(String raw,Material fallback,Plugin plugin,ZoneType type){Material m=raw==null?null:Material.matchMaterial(raw);if(m==null||m.isAir()){if(raw!=null&&!raw.isBlank())plugin.getLogger().warning(type.configKey()+".yml has invalid material '"+raw+"'; using "+fallback);return fallback;}return m;}private static int validSize(int i){return i>=9&&i<=54&&i%9==0?i:27;}private static double clamp(double n,double min,double max){return Math.max(min,Math.min(max,n));}
    }
    public static final class MobDefinition { final String id;final EntityType type;final boolean enabled;final double weight,minHealth,maxHealth;final List<MobProfile> profiles;MobDefinition(String id,EntityType type,boolean enabled,double weight,double minHealth,double maxHealth,List<MobProfile> profiles){this.id=id;this.type=type;this.enabled=enabled;this.weight=Math.max(0D,weight);this.minHealth=Math.max(1D,minHealth);this.maxHealth=Math.max(this.minHealth,maxHealth);this.profiles=profiles.isEmpty()?List.of(new MobProfile(id,2D)):List.copyOf(profiles);}static Optional<MobDefinition> read(String id,ConfigurationSection s,Plugin p){try{EntityType type=EntityType.valueOf(s.getString("type","ZOMBIE").toUpperCase(Locale.ROOT));if(!type.isAlive()){p.getLogger().warning("Zone mob "+id+" is not living.");return Optional.empty();}List<String> names=s.getStringList("names");List<MobProfile> profiles=new ArrayList<>();ConfigurationSection profile=s.getConfigurationSection("profiles");if(names.isEmpty()&&profile!=null)names.addAll(profile.getKeys(false));if(names.isEmpty())names.add(id);for(String name:names)profiles.add(new MobProfile(name,profile==null?s.getDouble("damage",2D):profile.getDouble(name+".damage",s.getDouble("damage",2D))));return Optional.of(new MobDefinition(id,type,s.getBoolean("enabled",true),s.getDouble("spawn-weight",1D),s.getDouble("health.min",20D),s.getDouble("health.max",20D),profiles));}catch(IllegalArgumentException e){p.getLogger().warning("Zone mob "+id+" has invalid entity type.");return Optional.empty();}}
    }
    public record MobProfile(String name,double damage) { }
}
