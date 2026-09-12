package me.vertex.core.factions;

import me.vertex.core.claims.ChunkKey;
import me.vertex.core.factions.event.FactionClaimEvent;
import me.vertex.core.factions.event.FactionLifecycleEvent;
import me.vertex.core.factions.event.FactionUnclaimAllEvent;
import me.vertex.core.grace.DurationParser;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.ToLongFunction;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Vertex's authoritative faction service. It owns all faction state and has
 * no runtime dependency on FactionsUUID or any third-party faction API.
 */
public final class FactionService {
    public static final int NO_FACTION = Integer.MIN_VALUE;
    private static final Pattern DEFAULT_TAG = Pattern.compile("[A-Za-z0-9_]{3,16}");

    public enum Result {
        OK, NO_FACTION, ALREADY_IN_FACTION, NOT_LEADER, NOT_MEMBER, NOT_FOUND,
        INVALID_NAME, NAME_TAKEN, NOT_INVITED, BANNED, CLOSED, NO_PERMISSION, ALREADY_CLAIMED,
        CLAIM_LIMIT, INSUFFICIENT_POWER, NOT_CONNECTED, NOT_OWNER, SYSTEM_FACTION, INVALID_LOCATION,
        LIMIT_REACHED, COOLDOWN, LAST_LEADER, DATABASE_ERROR
    }

    private final Plugin plugin;
    private final FactionStorage storage;
    private final Map<Integer, FactionData> factions = new ConcurrentHashMap<>();
    private final Map<UUID, FactionMember> members = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Integer> claims = new ConcurrentHashMap<>();
    private final Map<FactionStorage.RelationKey, FactionRelation> relations = new ConcurrentHashMap<>();
    private final Map<FactionStorage.PermissionKey, Boolean> permissions = new ConcurrentHashMap<>();
    private final Map<FactionStorage.WarpKey, FactionWarp> warps = new ConcurrentHashMap<>();
    private final Map<UUID, FactionStorage.PlayerSettings> playerSettings = new ConcurrentHashMap<>();
    private final Map<FactionStorage.InviteKey, FactionStorage.Invite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, FactionPowerProfile> powerProfiles = new ConcurrentHashMap<>();
    private final Map<UUID, Long> createCooldowns = new ConcurrentHashMap<>();
    private final Map<Integer, Long> renameCooldowns = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();
    private CompletableFuture<Void> mutationTail = CompletableFuture.completedFuture(null);
    private final ConcurrentLinkedQueue<org.bukkit.event.Event> deferredShutdownEvents=new ConcurrentLinkedQueue<>();
    private volatile boolean shuttingDown;

    private volatile int maxMembers = 16;
    private volatile int maxClaims = 250;
    private volatile int maxWarps = 5;
    private volatile int maxClaimRadius = 5;
    private volatile boolean claimsMustConnect;
    private volatile boolean claimsMayOverclaim;
    private volatile int mapWidth = 41;
    private volatile int mapHeight = 9;
    private volatile long inviteMillis = 300_000L;
    private volatile boolean openJoin = true;
    private volatile double powerStartingPerMember = 100D;
    private volatile double powerMaxPerMember = 100D;
    private volatile double absolutePlayerPowerCap = 5_000D;
    private volatile long powerRegenerationIntervalMillis = 120_000L;
    private volatile long createAfterDisbandMillis = 60_000L;
    private volatile long renameCooldownMillis = 21_600_000L;
    private volatile java.util.function.IntUnaryOperator warpBonusProvider = ignored -> 0;
    private volatile BiPredicate<Integer, ChunkKey> baseClaimQuery = (ignoredFaction, ignoredChunk) -> false;
    private volatile ToLongFunction<ChunkKey> raidClaimExpiryQuery = ignored -> 0L;
    private volatile java.util.function.LongSupplier freshRaidExpiration = () -> 0L;
    private volatile BiPredicate<Integer, UUID> factionBanQuery = (ignoredFaction, ignoredPlayer) -> false;
    private volatile java.util.function.IntFunction<ShieldDisplay> shieldDisplayProvider =
            ignored -> new ShieldDisplay(false, 0L, 0L);
    private volatile java.util.function.Consumer<String> mutationPublisher = ignored -> { };
    private volatile Messages messages;

    public FactionService(Plugin plugin, FactionStorage storage) { this.plugin = plugin; this.storage = storage; }
    public void setMessages(Messages messages) { this.messages = messages; }

    public void init() throws SQLException { storage.init(); load(); }
    public void load() throws SQLException {
        reloadConfig();
        FactionStorage.LoadedState state = storage.load();
        factions.clear(); factions.putAll(state.factions());
        members.clear(); members.putAll(state.members());
        claims.clear(); claims.putAll(state.claims());
        relations.clear(); relations.putAll(state.relations());
        permissions.clear(); permissions.putAll(state.permissions());
        warps.clear(); warps.putAll(state.warps());
        playerSettings.clear(); playerSettings.putAll(state.players());
        invites.clear(); invites.putAll(state.invites());
        powerProfiles.clear(); powerProfiles.putAll(state.powerProfiles());
        createCooldowns.clear(); createCooldowns.putAll(state.createCooldowns());
        renameCooldowns.clear(); renameCooldowns.putAll(state.renameCooldowns());
        long nextRegeneration = System.currentTimeMillis() + powerRegenerationIntervalMillis;
        for (FactionMember member : members.values()) {
            if (!powerProfiles.containsKey(member.playerUuid())) {
                FactionPowerProfile migrated = new FactionPowerProfile(member.playerUuid(),
                        powerStartingPerMember, powerMaxPerMember, nextRegeneration);
                storage.savePower(migrated);
                powerProfiles.put(member.playerUuid(), migrated);
            }
        }
        for (FactionData faction : List.copyOf(factions.values())) {
            if (!faction.system()) recalculateFactionPower(faction.id(), true);
        }
        pruneExpiredInvites();
    }

    /**
     * Serializes native faction mutations away from the primary server
     * thread. Existing mutation methods remain synchronous for storage tests
     * and startup code; commands/events must enter through this boundary.
     */
    public <T> CompletableFuture<T> submitMutation(java.util.function.Supplier<T> operation) {
        return submitMutation(operation, true);
    }

    private <T> CompletableFuture<T> submitMutation(java.util.function.Supplier<T> operation, boolean publish) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (mutationLock) {
            mutationTail = mutationTail.handle((ignored, error) -> null).thenRunAsync(() -> {
                try {
                    T value = operation.get();
                    if (publish) mutationPublisher.accept("changed");
                    result.complete(value);
                }
                catch (Throwable error) { result.completeExceptionally(error); }
            });
        }
        return result;
    }

    public void setMutationPublisher(java.util.function.Consumer<String> publisher) {
        mutationPublisher = publisher == null ? ignored -> { } : publisher;
    }

    /** Reloads a shared-database invalidation through the same serialized mutation lane. */
    public CompletableFuture<Boolean> refreshFromNetwork() {
        return submitMutation(() -> {
            try { load(); return true; }
            catch (SQLException error) { log("Could not refresh native faction network state", error); return false; }
        }, false);
    }

    public void awaitMutations() {
        CompletableFuture<Void> tail;
        synchronized (mutationLock) { tail = mutationTail; }
        try { tail.get(10, TimeUnit.SECONDS); }
        catch (Exception error) { plugin.getLogger().log(Level.WARNING,
                "Timed out waiting for native faction mutations during shutdown.", error); }
    }

    /** Drains queued mutations without deadlocking workers waiting on the Bukkit thread. */
    public void shutdown(){
        shuttingDown=true;
        awaitMutations();
        org.bukkit.event.Event event;
        while((event=deferredShutdownEvents.poll())!=null){
            try{Bukkit.getPluginManager().callEvent(event);}catch(Exception error){log("Could not dispatch a deferred faction event during shutdown",error);}
        }
    }

    private <T extends org.bukkit.event.Event> T callEvent(T event) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
            return event;
        }
        if(shuttingDown){deferredShutdownEvents.add(event);return event;}
        CompletableFuture<T> called = new CompletableFuture<>();
        AtomicBoolean claimed=new AtomicBoolean();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if(!claimed.compareAndSet(false,true))return;
            try { Bukkit.getPluginManager().callEvent(event); called.complete(event); }
            catch (Throwable error) { called.completeExceptionally(error); }
        });
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){
            if(shuttingDown&&claimed.compareAndSet(false,true)){deferredShutdownEvents.add(event);return event;}
            try{return called.get(100,TimeUnit.MILLISECONDS);}
            catch(java.util.concurrent.TimeoutException ignored){}
            catch(Exception error){throw new java.util.concurrent.CompletionException(error);}
        }
        throw new java.util.concurrent.CompletionException(new java.util.concurrent.TimeoutException(
                "Timed out waiting for the primary thread to dispatch a faction event"));
    }
    public void reloadConfig() {
        maxMembers = bounded(plugin.getConfig().getInt("factions.limits.members", 16), 1, 500);
        maxClaims = bounded(plugin.getConfig().getInt("factions.limits.claims", 250), 1, 50_000);
        maxWarps = bounded(plugin.getConfig().getInt("factions.limits.warps", 5), 0, 100);
        maxClaimRadius = bounded(plugin.getConfig().getInt("factions.limits.claim-radius", 5), 0, 25);
        claimsMustConnect = plugin.getConfig().getBoolean("factions.claims.require-connected", false);
        claimsMayOverclaim = plugin.getConfig().getBoolean("factions.claims.overclaiming.enabled",
                plugin.getConfig().getBoolean("factions.claims.allow-overclaim", false));
        int legacyDiameter = bounded(plugin.getConfig().getInt("factions.map.radius", 4), 1, 10) * 2 + 1;
        mapWidth = bounded(plugin.getConfig().getInt("factions.map.width", legacyDiameter), 1, 41);
        mapHeight = bounded(plugin.getConfig().getInt("factions.map.height", 20), 1, 21);
        inviteMillis = Math.max(10_000L, plugin.getConfig().getLong("factions.invite-expiry-seconds", 300L) * 1_000L);
        openJoin = plugin.getConfig().getBoolean("factions.open-join-enabled", true);
        powerMaxPerMember = positive(plugin.getConfig().getDouble("factions.power.default-max", 100D), 100D);
        powerStartingPerMember = Math.min(powerMaxPerMember, positive(plugin.getConfig().getDouble(
                "factions.power.default-current", powerMaxPerMember), powerMaxPerMember));
        absolutePlayerPowerCap = Math.max(powerMaxPerMember, positive(plugin.getConfig().getDouble(
                "factions.power.absolute-player-cap", 5_000D), 5_000D));
        powerRegenerationIntervalMillis = Math.max(1_000L, plugin.getConfig().getLong(
                "factions.power.regeneration-interval-seconds", 120L) * 1_000L);
        createAfterDisbandMillis = Math.max(0L, plugin.getConfig().getLong(
                "factions.lifecycle.create-after-disband-cooldown-seconds", 60L) * 1_000L);
        renameCooldownMillis = Math.max(0L, plugin.getConfig().getLong(
                "factions.lifecycle.rename-cooldown-seconds", 21_600L) * 1_000L);
    }

    public Optional<FactionData> faction(int id) { return Optional.ofNullable(factions.get(id)); }
    public Optional<FactionData> faction(String tag) {
        if (tag == null) return Optional.empty();
        return factions.values().stream().filter(f -> f.tag().equalsIgnoreCase(tag)).findFirst();
    }
    public Optional<FactionData> faction(Player player) { return player == null ? Optional.empty() : factionOf(player.getUniqueId()); }
    public Optional<FactionData> factionOf(UUID uuid) { FactionMember member=members.get(uuid); return member == null ? Optional.empty() : faction(member.factionId()); }
    public FactionMember member(UUID uuid) { return members.get(uuid); }
    public Optional<FactionMember> member(String playerName) {
        if (playerName == null || playerName.isBlank()) return Optional.empty();
        return members.values().stream().filter(member -> member.lastName().equalsIgnoreCase(playerName)).findFirst();
    }
    public int factionId(Player player) { FactionMember member = player == null ? null : members.get(player.getUniqueId()); return member == null ? NO_FACTION : member.factionId(); }
    public int factionIdAt(Location location) { return location == null || location.getWorld() == null ? NO_FACTION : claims.getOrDefault(ChunkKey.of(location), NO_FACTION); }
    public int factionIdAt(ChunkKey chunk) { return chunk == null ? NO_FACTION : claims.getOrDefault(chunk, NO_FACTION); }
    public String factionTagAt(Location location) { return faction(factionIdAt(location)).map(FactionData::tag).orElse(null); }
    public Collection<FactionData> factions() { return factions.values().stream().sorted(Comparator.comparing(FactionData::tag, String.CASE_INSENSITIVE_ORDER)).toList(); }
    public List<FactionMember> members(int factionId) { return members.values().stream().filter(member -> member.factionId() == factionId).sorted(Comparator.comparing(FactionMember::role, Comparator.comparingInt(FactionRole::weight).reversed()).thenComparing(FactionMember::lastName, String.CASE_INSENSITIVE_ORDER)).toList(); }
    public List<ChunkKey> claims(int factionId) { return claims.entrySet().stream().filter(entry -> entry.getValue() == factionId).map(Map.Entry::getKey).sorted(Comparator.comparing(ChunkKey::world).thenComparingInt(ChunkKey::x).thenComparingInt(ChunkKey::z)).toList(); }
    /** Immutable ownership snapshot used to reconcile Base/Raid metadata after an unclean shutdown. */
    public Map<ChunkKey, Integer> claimOwners() { return Map.copyOf(claims); }
    public int claimCount(int factionId) { return (int) claims.values().stream().filter(id -> id == factionId).count(); }
    public java.util.Map<String,String> cornerClaimOwners(String shardId, String worldName, int worldSize) {
        int edge = Math.max(1, worldSize / 32);
        java.util.Map<String,String> result = new java.util.LinkedHashMap<>();
        for (String corner : java.util.List.of("+X +Z", "+X -Z", "-X -Z", "-X +Z")) {
            int targetX = corner.startsWith("+") ? edge : -edge;
            int targetZ = corner.endsWith("+Z") ? edge : -edge;
            long best = Long.MAX_VALUE; int owner = NO_FACTION;
            for (java.util.Map.Entry<ChunkKey,Integer> entry : claims.entrySet()) {
                ChunkKey key = entry.getKey();
                if (!key.localWorld().equalsIgnoreCase(worldName)
                        || !key.shardId().equalsIgnoreCase(shardId)) continue;
                FactionData faction = factions.get(entry.getValue());
                if (faction == null || faction.system()) continue;
                long dx=(long)key.x()-targetX,dz=(long)key.z()-targetZ,distance=dx*dx+dz*dz;
                if(distance<best){best=distance;owner=entry.getValue();}
            }
            result.put(corner, faction(owner).map(FactionData::tag).orElse("None"));
        }
        return result;
    }
    public int serviceClaimRadiusLimit() { return maxClaimRadius; }
    public boolean claimsMayOverclaim() { return claimsMayOverclaim; }
    public int mapWidth() { return mapWidth; }
    public int mapHeight() { return mapHeight; }
    public void setClaimMapQueries(BiPredicate<Integer, ChunkKey> baseClaimQuery,
            ToLongFunction<ChunkKey> raidClaimExpiryQuery) {
        this.baseClaimQuery = baseClaimQuery == null ? (ignoredFaction, ignoredChunk) -> false : baseClaimQuery;
        this.raidClaimExpiryQuery = raidClaimExpiryQuery == null ? ignored -> 0L : raidClaimExpiryQuery;
    }
    public void setFreshRaidExpiration(java.util.function.LongSupplier provider) {
        freshRaidExpiration = provider == null ? () -> 0L : provider;
    }
    public void setFactionBanQuery(BiPredicate<Integer, UUID> query) {
        factionBanQuery = query == null ? (ignoredFaction, ignoredPlayer) -> false : query;
    }
    public void setShieldDisplayProvider(java.util.function.IntFunction<ShieldDisplay> provider) {
        shieldDisplayProvider = provider == null ? ignored -> new ShieldDisplay(false, 0L, 0L) : provider;
    }
    public ShieldDisplay shieldDisplay(int factionId) { return shieldDisplayProvider.apply(factionId); }
    public String systemTag(String type) { String safe = type == null ? "" : type.toLowerCase(Locale.ROOT); return plugin.getConfig().getString("factions.system-claims." + safe + "-tag", safe.equals("safezone") ? "SafeZone" : "WarZone"); }
    public void setWarpBonusProvider(java.util.function.IntUnaryOperator provider) { warpBonusProvider = provider == null ? ignored -> 0 : provider; }
    public int warpLimit(int factionId) { return Math.max(0, Math.min(100, maxWarps + Math.max(0, warpBonusProvider.applyAsInt(factionId)))); }

    public FactionPowerProfile powerProfile(UUID uuid) {
        if (uuid == null) return null;
        return powerProfiles.get(uuid);
    }

    public synchronized FactionPowerProfile ensurePowerProfile(UUID uuid) {
        if (uuid == null) return null;
        FactionPowerProfile existing = powerProfiles.get(uuid);
        if (existing != null) return existing;
        FactionPowerProfile created = new FactionPowerProfile(uuid, powerStartingPerMember, powerMaxPerMember,
                System.currentTimeMillis() + powerRegenerationIntervalMillis);
        try {
            storage.savePower(created);
            powerProfiles.put(uuid, created);
            return created;
        } catch (SQLException error) {
            log("Could not create player power profile", error);
            return null;
        }
    }

    public synchronized Result create(Player creator, String rawTag) {
        if (creator == null) return Result.NOT_FOUND;
        if (members.containsKey(creator.getUniqueId())) return Result.ALREADY_IN_FACTION;
        if (createAvailableInMillis(creator.getUniqueId()) > 0L) return Result.COOLDOWN;
        String tag = normalizeTag(rawTag);
        if (tag == null) return Result.INVALID_NAME;
        if (faction(tag).isPresent()) return Result.NAME_TAKEN;
        FactionPowerProfile profile = ensurePowerProfile(creator.getUniqueId());
        if (profile == null) return Result.DATABASE_ERROR;
        long now = System.currentTimeMillis();
        FactionData draft = new FactionData(-1, tag, "", false, false, profile.current(), profile.maximum(), now, null);
        FactionMember leader = new FactionMember(creator.getUniqueId(), -1, FactionRole.LEADER, creator.getName(), now);
        try {
            FactionStorage.CreateFactionOutcome outcome = storage.createFactionChecked(draft, leader, now);
            if (outcome.result() == FactionStorage.CreateFactionWriteResult.ALREADY_MEMBER) {
                return Result.ALREADY_IN_FACTION;
            }
            if (outcome.result() == FactionStorage.CreateFactionWriteResult.COOLDOWN) {
                return Result.COOLDOWN;
            }
            if (outcome.result() == FactionStorage.CreateFactionWriteResult.NAME_TAKEN) {
                return Result.NAME_TAKEN;
            }
            int id = outcome.factionId();
            FactionData faction = new FactionData(id, tag, "", false, false, profile.current(), profile.maximum(), now, null);
            FactionMember savedLeader = new FactionMember(creator.getUniqueId(), id, FactionRole.LEADER, creator.getName(), now);
            factions.put(id, faction); members.put(creator.getUniqueId(), savedLeader);
            callEvent(new FactionLifecycleEvent(FactionLifecycleEvent.Action.CREATE, faction, creator, null));
            return Result.OK;
        } catch (SQLException error) { log("Could not create faction", error); return Result.DATABASE_ERROR; }
    }

    public synchronized Result disband(Player actor) {
        FactionMember member = actor == null ? null : members.get(actor.getUniqueId());
        if (member == null) return Result.NO_FACTION;
        if (!member.role().atLeast(FactionRole.LEADER)) return Result.NOT_LEADER;
        FactionData faction = factions.get(member.factionId());
        if (faction == null || faction.system()) return Result.SYSTEM_FACTION;
        try {
            long createAvailableAt = safeAddMillis(System.currentTimeMillis(), createAfterDisbandMillis);
            FactionStorage.DisbandWriteResult deleted = storage.deleteFactionChecked(faction.id(),
                    actor.getUniqueId(), member.role(), createAvailableAt);
            if (deleted == FactionStorage.DisbandWriteResult.NOT_AUTHORIZED) return Result.NOT_LEADER;
            if (deleted != FactionStorage.DisbandWriteResult.OK) return Result.NO_FACTION;
            factions.remove(faction.id()); members.entrySet().removeIf(entry -> entry.getValue().factionId() == faction.id());
            claims.entrySet().removeIf(entry -> entry.getValue() == faction.id()); relations.keySet().removeIf(key -> key.factionId() == faction.id() || key.targetId() == faction.id());
            permissions.keySet().removeIf(key -> key.factionId() == faction.id()); warps.keySet().removeIf(key -> key.factionId() == faction.id()); invites.keySet().removeIf(key -> key.factionId() == faction.id());
            renameCooldowns.remove(faction.id());
            if (createAfterDisbandMillis > 0L) createCooldowns.put(actor.getUniqueId(), createAvailableAt);
            // External faction-bound data is released only after the core
            // faction row and every claim are durably gone.
            callEvent(new FactionUnclaimAllEvent(faction, actor));
            callEvent(new FactionLifecycleEvent(FactionLifecycleEvent.Action.DISBAND, faction, actor, faction.tag()));
            return Result.OK;
        } catch (SQLException error) { log("Could not disband faction", error); return Result.DATABASE_ERROR; }
    }

    /** Permission checks and --force confirmation live in /fa; this keeps cleanup identical to normal disband. */
    public synchronized Result forceDisband(int factionId, Player actor) {
        FactionData faction = factions.get(factionId);
        if (faction == null) return Result.NOT_FOUND;
        if (faction.system()) return Result.SYSTEM_FACTION;
        try {
            storage.deleteFaction(faction.id());
            factions.remove(faction.id());
            members.entrySet().removeIf(entry -> entry.getValue().factionId() == faction.id());
            claims.entrySet().removeIf(entry -> entry.getValue() == faction.id());
            relations.keySet().removeIf(key -> key.factionId() == faction.id() || key.targetId() == faction.id());
            permissions.keySet().removeIf(key -> key.factionId() == faction.id());
            warps.keySet().removeIf(key -> key.factionId() == faction.id());
            invites.keySet().removeIf(key -> key.factionId() == faction.id());
            renameCooldowns.remove(faction.id());
            callEvent(new FactionUnclaimAllEvent(faction, actor));
            callEvent(new FactionLifecycleEvent(FactionLifecycleEvent.Action.DISBAND, faction, actor, faction.tag()));
            return Result.OK;
        } catch (SQLException error) {
            log("Could not force-disband faction", error);
            return Result.DATABASE_ERROR;
        }
    }

    public synchronized Result rename(Player actor, String rawTag) {
        FactionMember member = actor == null ? null : members.get(actor.getUniqueId());
        if (member == null) return Result.NO_FACTION;
        if (!member.role().atLeast(FactionRole.LEADER)) return Result.NOT_LEADER;
        FactionData faction = factions.get(member.factionId()); String tag = normalizeTag(rawTag);
        if (faction == null) return Result.NO_FACTION; if (faction.system()) return Result.SYSTEM_FACTION;
        if (tag == null) return Result.INVALID_NAME;
        if (faction(tag).filter(other -> other.id() != faction.id()).isPresent()) return Result.NAME_TAKEN;
        if (renameAvailableInMillis(faction.id()) > 0L) return Result.COOLDOWN;
        FactionData renamed = faction.withTag(tag);
        long now = System.currentTimeMillis();
        long availableAt = safeAddMillis(now, renameCooldownMillis);
        try {
            FactionStorage.RenameWriteResult result = storage.renameFactionChecked(actor.getUniqueId(),
                    member.role(), faction.id(), tag, now, availableAt);
            if (result == FactionStorage.RenameWriteResult.NAME_TAKEN) return Result.NAME_TAKEN;
            if (result == FactionStorage.RenameWriteResult.COOLDOWN) return Result.COOLDOWN;
            if (result == FactionStorage.RenameWriteResult.SYSTEM_FACTION) return Result.SYSTEM_FACTION;
            if (result == FactionStorage.RenameWriteResult.NOT_AUTHORIZED) return Result.NOT_LEADER;
            if (result != FactionStorage.RenameWriteResult.OK) return Result.NO_FACTION;
            factions.put(renamed.id(), renamed);
            renameCooldowns.put(renamed.id(), availableAt);
            callEvent(new FactionLifecycleEvent(FactionLifecycleEvent.Action.RENAME, renamed, actor, faction.tag())); return Result.OK; }
        catch (SQLException error) { log("Could not rename faction", error); return Result.DATABASE_ERROR; }
    }

    public synchronized Result setOpen(Player actor, boolean open) {
        FactionMember member = actor == null ? null : members.get(actor.getUniqueId());
        if (member == null) return Result.NO_FACTION;
        if (!member.role().atLeast(FactionRole.LEADER)) return Result.NOT_LEADER;
        FactionData faction = factions.get(member.factionId());
        if (faction == null || faction.system()) return Result.SYSTEM_FACTION;
        FactionData changed = faction.withOpen(open);
        try {
            FactionStorage.FactionFieldWriteResult saved = storage.updateOpenChecked(actor.getUniqueId(),
                    changed.id(), member.role(), open);
            if (saved == FactionStorage.FactionFieldWriteResult.NOT_AUTHORIZED) return Result.NOT_LEADER;
            if (saved != FactionStorage.FactionFieldWriteResult.OK) return Result.NO_FACTION;
            factions.put(changed.id(), changed);
            return Result.OK;
        }
        catch (SQLException error) { log("Could not change faction join state", error); return Result.DATABASE_ERROR; }
    }

    public synchronized Result setDescription(Player actor, String description) {
        FactionMember member = actor == null ? null : members.get(actor.getUniqueId());
        if (member == null) return Result.NO_FACTION;
        if (!hasAction(member, "description")) return Result.NO_PERMISSION;
        FactionData faction = factions.get(member.factionId());
        if (faction == null || faction.system()) return Result.SYSTEM_FACTION;
        String safe = description == null ? "" : description.trim();
        if (safe.length() > 512) safe = safe.substring(0, 512);
        FactionData changed = faction.withDescription(safe);
        try {
            FactionStorage.FactionFieldWriteResult saved = storage.updateDescriptionChecked(
                    actor.getUniqueId(), changed.id(), member.role(),
                    configuredActionDefault(member.role(), "description"), safe);
            if (saved == FactionStorage.FactionFieldWriteResult.NOT_AUTHORIZED) {
                return Result.NO_PERMISSION;
            }
            if (saved != FactionStorage.FactionFieldWriteResult.OK) return Result.NO_FACTION;
            factions.put(changed.id(), changed);
            return Result.OK;
        }
        catch (SQLException error) { log("Could not change faction description", error); return Result.DATABASE_ERROR; }
    }

    public synchronized Result invite(Player actor, UUID target) {
        FactionMember member=actor == null ? null : members.get(actor.getUniqueId()); if(member==null) return Result.NO_FACTION;
        if(!hasAction(member, "invite")) return Result.NO_PERMISSION; if(target==null || members.containsKey(target)) return Result.ALREADY_IN_FACTION;if(factionBanQuery.test(member.factionId(),target))return Result.BANNED;
        FactionStorage.Invite invite=new FactionStorage.Invite(member.factionId(),target,actor.getUniqueId(),System.currentTimeMillis()+inviteMillis);
        try {
            FactionStorage.InviteWriteResult saved=storage.saveInviteChecked(invite,member.role(),
                    configuredActionDefault(member.role(),"invite"));
            Result failure=switch(saved){case OK->null;case ALREADY_MEMBER->Result.ALREADY_IN_FACTION;
                case BANNED->Result.BANNED;case NOT_AUTHORIZED->Result.NO_PERMISSION;
                case FACTION_CHANGED->Result.NO_FACTION;};
            if(failure!=null)return failure;
            invites.put(new FactionStorage.InviteKey(invite.factionId(),target),invite);return Result.OK;
        }
        catch(SQLException error){log("Could not save faction invite",error);return Result.DATABASE_ERROR;}
    }
    public synchronized Result join(Player player, FactionData target) {
        if(player==null||target==null)return Result.NOT_FOUND; if(members.containsKey(player.getUniqueId()))return Result.ALREADY_IN_FACTION; if(target.system())return Result.SYSTEM_FACTION;
        if(factionBanQuery.test(target.id(),player.getUniqueId()))return Result.BANNED;
        if(members(target.id()).size()>=maxMembers)return Result.LIMIT_REACHED;
        FactionStorage.Invite invite=invites.get(new FactionStorage.InviteKey(target.id(),player.getUniqueId()));
        if((!openJoin || !target.open()) && (invite==null||invite.expiresAtMillis()<System.currentTimeMillis()))return Result.NOT_INVITED;
        FactionPowerProfile profile = ensurePowerProfile(player.getUniqueId());
        if (profile == null) return Result.DATABASE_ERROR;
        FactionMember joining=new FactionMember(player.getUniqueId(),target.id(),FactionRole.RECRUIT,player.getName(),System.currentTimeMillis());
        try {
            FactionStorage.JoinOutcome outcome = storage.joinFactionChecked(joining, openJoin,
                    maxMembers, System.currentTimeMillis());
            Result rejected = switch (outcome.result()) {
                case OK -> null;
                case ALREADY_MEMBER -> Result.ALREADY_IN_FACTION;
                case NOT_FOUND -> Result.NOT_FOUND;
                case MEMBER_LIMIT -> Result.LIMIT_REACHED;
                case BANNED -> Result.BANNED;
                case NOT_INVITED -> Result.NOT_INVITED;
            };
            if (rejected != null) return rejected;
            members.put(joining.playerUuid(), joining);
            applyFactionTotals(outcome.totals());
            invites.remove(new FactionStorage.InviteKey(target.id(),player.getUniqueId()));
            return Result.OK;
        }
        catch(SQLException error){log("Could not join faction",error);return Result.DATABASE_ERROR;}
    }
    public synchronized Result leave(Player player) {
        FactionMember member = player == null ? null : members.get(player.getUniqueId());
        if (member == null) return Result.NO_FACTION;
        if (member.role() != FactionRole.LEADER) return removeMember(member.playerUuid());
        try {
            FactionStorage.LeaderDepartureOutcome outcome = storage.removeLeaderAndTransferChecked(
                    member.playerUuid(), member.factionId());
            if (outcome.result() == FactionStorage.LeaderDepartureResult.NO_SUCCESSOR) return Result.LAST_LEADER;
            if (outcome.result() != FactionStorage.LeaderDepartureResult.OK) return Result.NOT_MEMBER;
            members.remove(member.playerUuid());
            members.put(outcome.successor().playerUuid(), outcome.successor());
            applyFactionTotals(outcome.totals());
            return Result.OK;
        } catch (SQLException error) {
            log("Could not transfer leadership while the leader left", error);
            return Result.DATABASE_ERROR;
        }
    }
    public synchronized Result kick(Player actor, UUID target) {
        FactionMember actorMember = actor == null ? null : members.get(actor.getUniqueId());
        FactionMember targetMember = target == null ? null : members.get(target);
        if (actorMember == null) return Result.NO_FACTION;
        if (targetMember == null || targetMember.factionId() != actorMember.factionId()) {
            return Result.NOT_MEMBER;
        }
        if (!hasAction(actorMember, "kick")
                || targetMember.role().weight() >= actorMember.role().weight()) {
            return Result.NO_PERMISSION;
        }
        try {
            FactionStorage.MemberMutationOutcome outcome = storage.removeFactionMemberAuthorized(
                    actor.getUniqueId(), actorMember.role(),
                    configuredActionDefault(actorMember.role(), "kick"), target,
                    actorMember.factionId());
            if (outcome.result() == FactionStorage.MemberWriteResult.NOT_AUTHORIZED) {
                return Result.NO_PERMISSION;
            }
            if (outcome.result() == FactionStorage.MemberWriteResult.MEMBER_CHANGED) {
                return Result.NOT_MEMBER;
            }
            if (outcome.result() == FactionStorage.MemberWriteResult.LEADER) {
                return Result.LAST_LEADER;
            }
            members.remove(target, targetMember);
            applyFactionTotals(outcome.totals());
            return Result.OK;
        } catch (SQLException error) {
            log("Could not kick faction member", error);
            return Result.DATABASE_ERROR;
        }
    }
    private Result removeMember(UUID uuid){
        FactionMember member=members.get(uuid);
        if(member==null)return Result.NOT_MEMBER;
        if(!factions.containsKey(member.factionId()))return Result.NO_FACTION;
        try{
            FactionStorage.MemberMutationOutcome outcome = storage.removeFactionMemberChecked(uuid,
                    member.factionId());
            if (outcome.result() == FactionStorage.MemberWriteResult.MEMBER_CHANGED) return Result.NOT_MEMBER;
            if (outcome.result() == FactionStorage.MemberWriteResult.LEADER) return Result.LAST_LEADER;
            if (outcome.result() == FactionStorage.MemberWriteResult.NOT_AUTHORIZED) {
                return Result.NO_PERMISSION;
            }
            members.remove(uuid, member);
            applyFactionTotals(outcome.totals());
            return Result.OK;
        }
        catch(SQLException error){log("Could not remove faction member",error);return Result.DATABASE_ERROR;}
    }
    public synchronized Result setRole(Player actor, UUID target, FactionRole next) {
        FactionMember source=actor==null?null:members.get(actor.getUniqueId());
        FactionMember targetMember=target==null?null:members.get(target);
        if(source==null)return Result.NO_FACTION;
        if(targetMember==null||targetMember.factionId()!=source.factionId())return Result.NOT_MEMBER;
        if(next==null||!hasAction(source,next.weight()>targetMember.role().weight()?"promote":"demote")
                ||targetMember.role().weight()>=source.role().weight()
                ||next.weight()>=source.role().weight())return Result.NO_PERMISSION;
        try{
            String action = next.weight() > targetMember.role().weight() ? "promote" : "demote";
            FactionStorage.RoleWriteResult result=storage.setRoleAuthorized(actor.getUniqueId(),
                    source.role(), configuredActionDefault(source.role(), action), action, target,
                    source.factionId(), targetMember.role(), next);
            if(result==FactionStorage.RoleWriteResult.ROLE_LIMIT)return Result.LIMIT_REACHED;
            if(result==FactionStorage.RoleWriteResult.NOT_AUTHORIZED)return Result.NO_PERMISSION;
            if(result!=FactionStorage.RoleWriteResult.OK)return Result.NOT_MEMBER;
            members.put(target,targetMember.withRole(next));
            return Result.OK;
        }catch(SQLException error){log("Could not change faction role",error);return Result.DATABASE_ERROR;}
    }
    public synchronized Result transferLeadership(Player actor, UUID target) {
        FactionMember leader=actor==null?null:members.get(actor.getUniqueId());
        FactionMember targetMember=target==null?null:members.get(target);
        if(leader==null)return Result.NO_FACTION;
        if(leader.role()!=FactionRole.LEADER)return Result.NOT_LEADER;
        if(targetMember==null||targetMember.factionId()!=leader.factionId()||targetMember.role()==FactionRole.LEADER)return Result.NOT_MEMBER;
        FactionMember newLeader=targetMember.withRole(FactionRole.LEADER);
        FactionMember former=leader.withRole(FactionRole.COLEADER);
        List<FactionMember> changed=new ArrayList<>();changed.add(former);changed.add(newLeader);
        for(FactionMember candidate:members(leader.factionId()))if(candidate.role()==FactionRole.COLEADER
                &&!candidate.playerUuid().equals(targetMember.playerUuid()))changed.add(candidate.withRole(FactionRole.ADMIN));
        try{
            if(storage.transferLeadershipChecked(leader.playerUuid(),targetMember.playerUuid(),
                    leader.factionId())!=FactionStorage.LeadershipWriteResult.OK)return Result.NOT_MEMBER;
            for(FactionMember member:changed)members.put(member.playerUuid(),member);
            return Result.OK;
        }
        catch(SQLException error){log("Could not transfer faction leadership",error);return Result.DATABASE_ERROR;}
    }

    public synchronized Result claim(Player actor, ChunkKey key, boolean allowOverclaim) {
        FactionMember member=actor==null?null:members.get(actor.getUniqueId());if(member==null)return Result.NO_FACTION;if(!hasAction(member,"claim"))return Result.NO_PERMISSION;FactionData faction=factions.get(member.factionId());if(faction==null)return Result.NO_FACTION;int owner=claims.getOrDefault(key,NO_FACTION);if(owner==faction.id())return Result.ALREADY_CLAIMED;
        if(owner!=NO_FACTION){FactionData defender=factions.get(owner);if(!allowOverclaim||!claimsMayOverclaim||defender==null||defender.system()||claimCount(owner)<=Math.floor(defender.power()+0.000001D))return Result.NOT_OWNER;}
        if(owner==NO_FACTION&&claimCount(faction.id())>=maxClaims)return Result.CLAIM_LIMIT;
        if(owner==NO_FACTION&&faction.power()+0.000001D<claimCount(faction.id())+1D)return Result.INSUFFICIENT_POWER;
        if(owner==NO_FACTION&&claimsMustConnect&&claimCount(faction.id())>0&&!hasAdjacentClaim(key,faction.id()))return Result.NOT_CONNECTED;
        try{
            long raidExpiresAt=freshRaidExpiration.getAsLong();
            FactionStorage.ClaimWriteResult saved=storage.claimAuthorized(actor.getUniqueId(),
                    member.role(), configuredActionDefault(member.role(), "claim"), key, faction.id(),
                    owner==NO_FACTION?null:owner, maxClaims,allowOverclaim&&claimsMayOverclaim,raidExpiresAt);
            Result failure=switch(saved){case OK->null;case CLAIM_LIMIT->Result.CLAIM_LIMIT;
                case INSUFFICIENT_POWER->Result.INSUFFICIENT_POWER;
                case NOT_AUTHORIZED->Result.NO_PERMISSION;
                case OWNER_CHANGED,OVERCLAIM_DENIED->Result.NOT_OWNER;};
            if(failure!=null)return failure;
            claims.put(key,faction.id());callEvent(new FactionClaimEvent(FactionClaimEvent.Action.CLAIM,
                    faction,actor,key,owner==NO_FACTION?null:owner,raidExpiresAt));return Result.OK;
        }catch(Exception error){log("Could not save faction claim",asException(error));return Result.DATABASE_ERROR;}
    }
    /** Staff-only claim path for configured system factions such as SafeZone and WarZone. */
    public synchronized Result claimSystem(Player actor, String tag, ChunkKey key) {
        if (actor == null || !actor.hasPermission("vertex.factions.admin")) return Result.NO_PERMISSION;
        if (key == null || tag == null || tag.isBlank()) return Result.INVALID_NAME;
        FactionData faction = ensureSystemFaction(tag);
        int owner = claims.getOrDefault(key, NO_FACTION);
        if (owner == faction.id()) return Result.ALREADY_CLAIMED;
        try { if(!storage.saveClaimChecked(key,faction.id(),0L))return Result.NOT_FOUND;claims.put(key, faction.id()); callEvent(new FactionClaimEvent(FactionClaimEvent.Action.CLAIM, faction, actor, key, owner==NO_FACTION?null:owner, 0L)); return Result.OK; }
        catch (Exception error) { log("Could not save system faction claim", asException(error)); return Result.DATABASE_ERROR; }
    }

    /** Admin claim path that preserves claim events/Base-Raid metadata while bypassing normal limits. */
    public synchronized Result forceClaim(int factionId, Player actor, ChunkKey key) {
        FactionData faction = factions.get(factionId);
        if (faction == null || key == null) return Result.NOT_FOUND;
        try {
            long raidExpiresAt=faction.system()?0L:freshRaidExpiration.getAsLong();
            int owner=claims.getOrDefault(key,NO_FACTION);
            if(!storage.saveClaimChecked(key,factionId,raidExpiresAt))return Result.NOT_FOUND;
            claims.put(key, factionId);
            callEvent(new FactionClaimEvent(FactionClaimEvent.Action.CLAIM, faction, actor, key,
                    owner==NO_FACTION?null:owner,raidExpiresAt));
            return Result.OK;
        } catch (Exception error) {
            log("Could not force faction claim", asException(error));
            return Result.DATABASE_ERROR;
        }
    }

    public synchronized Result forceSetRole(UUID target, FactionRole role) {
        FactionMember member = target == null ? null : members.get(target);
        if (member == null || role == null) return Result.NOT_MEMBER;
        if (role == FactionRole.LEADER) return Result.NO_PERMISSION;
        if (role == FactionRole.COLEADER && members(member.factionId()).stream().anyMatch(candidate ->
                candidate.role() == FactionRole.COLEADER && !candidate.playerUuid().equals(target))) {
            return Result.LIMIT_REACHED;
        }
        try {
            FactionStorage.RoleWriteResult result = storage.setRoleChecked(target, member.factionId(),
                    member.role(), role);
            if (result == FactionStorage.RoleWriteResult.ROLE_LIMIT) return Result.LIMIT_REACHED;
            if (result != FactionStorage.RoleWriteResult.OK) return Result.NOT_MEMBER;
            members.put(target, member.withRole(role));
            return Result.OK;
        } catch (SQLException error) {
            log("Could not force faction role", error);
            return Result.DATABASE_ERROR;
        }
    }

    public synchronized Result forceRemoveMember(UUID target) { return removeMember(target); }
    public synchronized Result unclaim(Player actor, ChunkKey key) {
        FactionMember member = actor == null ? null : members.get(actor.getUniqueId());
        if (member == null) return Result.NO_FACTION;
        if (!hasAction(member, "unclaim")) return Result.NO_PERMISSION;
        if (claims.getOrDefault(key, NO_FACTION) != member.factionId()) return Result.NOT_OWNER;
        FactionData faction = factions.get(member.factionId());
        FactionClaimEvent event = callEvent(new FactionClaimEvent(
                FactionClaimEvent.Action.UNCLAIM, faction, actor, key));
        if (event.isCancelled()) return Result.NO_PERMISSION;
        try {
            FactionStorage.ClaimDeleteResult deleted = storage.deleteClaimChecked(
                    actor.getUniqueId(), member.role(),
                    configuredActionDefault(member.role(), "unclaim"), key, member.factionId(), true);
            if (deleted == FactionStorage.ClaimDeleteResult.NOT_AUTHORIZED) {
                return Result.NO_PERMISSION;
            }
            if (deleted != FactionStorage.ClaimDeleteResult.OK) return Result.NOT_OWNER;
            claims.remove(key, member.factionId());
            callEvent(new FactionClaimEvent(FactionClaimEvent.Action.UNCLAIMED,
                    faction, actor, key));
            return Result.OK;
        } catch (Exception error) {
            log("Could not remove faction claim", asException(error));
            return Result.DATABASE_ERROR;
        }
    }
    public synchronized Result unclaimAll(Player actor) {
        FactionMember member = actor == null ? null : members.get(actor.getUniqueId());
        if (member == null) return Result.NO_FACTION;
        if (!hasAction(member, "unclaim")) return Result.NO_PERMISSION;
        FactionData faction = factions.get(member.factionId());
        try {
            FactionStorage.ClaimDeleteResult deleted = storage.deleteClaimsForFactionChecked(
                    actor.getUniqueId(), member.role(),
                    configuredActionDefault(member.role(), "unclaim"), member.factionId(), true);
            if (deleted == FactionStorage.ClaimDeleteResult.NOT_AUTHORIZED) {
                return Result.NO_PERMISSION;
            }
            if (deleted != FactionStorage.ClaimDeleteResult.OK) return Result.NO_FACTION;
            claims.entrySet().removeIf(entry -> entry.getValue() == member.factionId());
            callEvent(new FactionUnclaimAllEvent(faction, actor));
            return Result.OK;
        } catch (Exception error) {
            log("Could not remove faction claims", asException(error));
            return Result.DATABASE_ERROR;
        }
    }

    /**
     * Internal lifecycle path for expiring raid claims and staff cleanup.
     * Unlike a player-run unclaim, an expiry cannot be vetoed; listeners get
     * an {@code EXPIRE} event first so they can safely release claim-bound
     * objects before the durable land row is removed.
     */
    public synchronized boolean forceUnclaim(ChunkKey key) {
        return forceUnclaim(key, null);
    }

    /** Expiry-safe removal that refuses to delete land transferred to another faction. */
    public synchronized boolean forceUnclaim(ChunkKey key, Integer expectedOwnerId) {
        if (key == null) return false;
        Integer ownerId = claims.get(key);
        if (ownerId == null) return false;
        if (expectedOwnerId != null && ownerId.intValue() != expectedOwnerId.intValue()) return false;
        FactionData owner = factions.get(ownerId);
        try { if(!storage.deleteClaim(key,ownerId,true))return false;claims.remove(key,ownerId);if (owner != null) callEvent(new FactionClaimEvent(FactionClaimEvent.Action.EXPIRE, owner, null, key)); return true; }
        catch (Exception error) { log("Could not force-remove faction claim", asException(error)); return false; }
    }

    /** Updates only this shard's relation cache after an external atomic write. */
    synchronized void applyMutualRelationCache(int left, int right, FactionRelation relation) {
        FactionStorage.RelationKey forward = new FactionStorage.RelationKey(left, right);
        FactionStorage.RelationKey reverse = new FactionStorage.RelationKey(right, left);
        if (relation == null || relation == FactionRelation.NEUTRAL) {
            relations.remove(forward);
            relations.remove(reverse);
        } else {
            relations.put(forward, relation);
            relations.put(reverse, relation);
        }
    }

    public synchronized void invalidateInvite(int factionId, UUID player) {
        try {
            storage.deleteInvite(factionId, player);
            invites.remove(new FactionStorage.InviteKey(factionId, player));
        } catch (SQLException error) {
            log("Could not invalidate faction invite", error);
        }
    }
    /** Cache-only companion for callers that deleted the invite in their own SQL transaction. */
    synchronized void invalidateInviteCache(int factionId, UUID player) {
        invites.remove(new FactionStorage.InviteKey(factionId, player));
    }
    public FactionRelation relation(int left,int right) { if(left==NO_FACTION||right==NO_FACTION||left==right)return FactionRelation.NEUTRAL;FactionRelation a=relations.getOrDefault(new FactionStorage.RelationKey(left,right),FactionRelation.NEUTRAL);FactionRelation b=relations.getOrDefault(new FactionStorage.RelationKey(right,left),FactionRelation.NEUTRAL);if(a==FactionRelation.ENEMY||b==FactionRelation.ENEMY)return FactionRelation.ENEMY;if(a==FactionRelation.ALLY&&b==FactionRelation.ALLY)return FactionRelation.ALLY;return FactionRelation.NEUTRAL; }
    public boolean isAlly(int left,int right){return relation(left,right)==FactionRelation.ALLY;} public boolean isEnemy(int left,int right){return relation(left,right)==FactionRelation.ENEMY;}

    public synchronized Result setHome(Player player, Location location) {
        FactionMember member=player==null?null:members.get(player.getUniqueId());
        if(member==null)return Result.NO_FACTION;
        if(!hasAction(member,"sethome"))return Result.NO_PERMISSION;
        int owner=factionIdAt(location);
        boolean own=owner==member.factionId();
        boolean permittedAlly=owner!=NO_FACTION&&isAlly(member.factionId(),owner)
                &&actionAllowed(owner,"ally","sethome");
        if(!own&&!permittedAlly)return Result.NOT_OWNER;
        FactionData faction=factions.get(member.factionId());FactionData changed=faction.withHome(home(location));
        try{
            FactionStorage.HomeWriteResult saved=storage.updateHomeChecked(player.getUniqueId(),
                    changed.id(),member.role(),configuredActionDefault(member.role(),"sethome"),
                    changed.home());
            if(saved==FactionStorage.HomeWriteResult.NOT_AUTHORIZED)return Result.NO_PERMISSION;
            if(saved==FactionStorage.HomeWriteResult.NOT_OWNER)return Result.NOT_OWNER;
            if(saved!=FactionStorage.HomeWriteResult.OK)return Result.NO_FACTION;
            factions.put(changed.id(),changed);return Result.OK;
        }
        catch(SQLException error){log("Could not save faction home",error);return Result.DATABASE_ERROR;}
    }
    public synchronized FactionData.Home homeRecord(int factionId){
        FactionData faction=factions.get(factionId);if(faction==null||faction.home()==null)return null;
        FactionData.Home stored=faction.home();
        int owner=factionIdAt(ChunkKey.at(stored.shardId(),stored.world(),((int)Math.floor(stored.x()))>>4,
                ((int)Math.floor(stored.z()))>>4));
        if(owner==factionId)return stored;
        if(owner!=NO_FACTION&&isAlly(factionId,owner)&&actionAllowed(owner,"ally","sethome"))return stored;
        FactionData cleared=faction.withHome(null);
        try{if(storage.updateHome(factionId,null))factions.put(factionId,cleared);}
        catch(SQLException error){log("Could not invalidate illegal ally faction home",error);}
        return null;
    }
    public synchronized Location home(int factionId){FactionData.Home stored=homeRecord(factionId);return location(stored);}
    public synchronized Result setWarp(Player player,String rawName,Location location){FactionMember member=player==null?null:members.get(player.getUniqueId());if(member==null)return Result.NO_FACTION;if(!hasAction(member,"setwarp"))return Result.NO_PERMISSION;if(factionIdAt(location)!=member.factionId())return Result.NOT_OWNER;String name=normalizeWarp(rawName);if(name==null)return Result.INVALID_NAME;FactionStorage.WarpKey key=new FactionStorage.WarpKey(member.factionId(),name);if(!warps.containsKey(key)&&warps(member.factionId()).size()>=warpLimit(member.factionId()))return Result.LIMIT_REACHED;FactionWarp warp=new FactionWarp(member.factionId(),name,home(location));try{FactionStorage.WarpWriteResult saved=storage.saveWarpChecked(player.getUniqueId(),warp,warpLimit(member.factionId()),member.role(),configuredActionDefault(member.role(),"setwarp"));Result failure=switch(saved){case OK->null;case NOT_AUTHORIZED->Result.NO_PERMISSION;case NOT_OWNER->Result.NOT_OWNER;case LIMIT_REACHED->Result.LIMIT_REACHED;case FACTION_CHANGED->Result.NO_FACTION;};if(failure!=null)return failure;warps.put(key,warp);return Result.OK;}catch(SQLException error){log("Could not save faction warp",error);return Result.DATABASE_ERROR;}}
    public synchronized Result deleteWarp(Player player,String rawName){
        FactionMember member=player==null?null:members.get(player.getUniqueId());
        if(member==null)return Result.NO_FACTION;
        if(!hasAction(member,"setwarp"))return Result.NO_PERMISSION;
        String name=normalizeWarp(rawName);
        FactionStorage.WarpKey key=new FactionStorage.WarpKey(member.factionId(),name==null?"":name);
        if(!warps.containsKey(key))return Result.NOT_FOUND;
        try{
            FactionStorage.WarpDeleteResult deleted=storage.deleteWarpChecked(player.getUniqueId(),
                    member.factionId(),member.role(),configuredActionDefault(member.role(),"setwarp"),
                    key.name());
            if(deleted==FactionStorage.WarpDeleteResult.NOT_AUTHORIZED)return Result.NO_PERMISSION;
            if(deleted==FactionStorage.WarpDeleteResult.NOT_FOUND)return Result.NOT_FOUND;
            if(deleted!=FactionStorage.WarpDeleteResult.OK)return Result.NO_FACTION;
            warps.remove(key);return Result.OK;
        }catch(SQLException error){log("Could not delete faction warp",error);return Result.DATABASE_ERROR;}
    }
    public FactionData.Home warpRecord(int factionId,String rawName){FactionWarp warp=warps.get(new FactionStorage.WarpKey(factionId,normalizeWarp(rawName)));return warp==null?null:warp.location();}
    public Location warp(int factionId,String rawName){return location(warpRecord(factionId,rawName));} public List<FactionWarp> warps(int factionId){return warps.values().stream().filter(warp->warp.factionId()==factionId).sorted(Comparator.comparing(FactionWarp::name)).toList();}

    public boolean hasAction(FactionMember member,String action){return member!=null&&hasAction(member.factionId(),member.role(),action);} public boolean hasAction(int factionId,FactionRole role,String action){if(role==FactionRole.LEADER)return true;String safe=normalizeAction(action);Boolean saved=permissions.get(new FactionStorage.PermissionKey(factionId,role.permissionBucket(),safe));if(saved!=null)return saved;if((safe.equals("place-blocks")||safe.equals("break-blocks"))&&(saved=permissions.get(new FactionStorage.PermissionKey(factionId,role.permissionBucket(),"build")))!=null)return saved;return configuredActionDefault(role,safe);}
    public synchronized Result setAction(Player actor,String role,String action,boolean allowed){FactionMember member=actor==null?null:members.get(actor.getUniqueId());if(member==null)return Result.NO_FACTION;String normalizedRole=role==null?"":role.trim().toLowerCase(Locale.ROOT);if(normalizedRole.equals("ally")){if(member.role()!=FactionRole.LEADER&&member.role()!=FactionRole.COLEADER)return Result.NO_PERMISSION;return savePermission(actor.getUniqueId(),member.factionId(),"ally",action,allowed);}FactionRole target=FactionRole.parse(role,FactionRole.MEMBER);boolean permitted=member.role()==FactionRole.LEADER&&target.weight()<FactionRole.LEADER.weight()||member.role()==FactionRole.COLEADER&&target.weight()<=FactionRole.ADMIN.weight()||member.role()==FactionRole.ADMIN&&(target==FactionRole.MEMBER||target==FactionRole.RECRUIT);if(!permitted)return Result.NO_PERMISSION;return savePermission(actor.getUniqueId(),member.factionId(),target.permissionBucket(),action,allowed);}
    private Result savePermission(UUID actor,int factionId,String bucket,String action,boolean allowed){String safe=normalizeAction(action);try{FactionStorage.PermissionWriteResult saved=storage.savePermissionChecked(actor,factionId,bucket,safe,allowed);if(saved==FactionStorage.PermissionWriteResult.NOT_AUTHORIZED)return Result.NO_PERMISSION;if(saved!=FactionStorage.PermissionWriteResult.OK)return Result.NO_FACTION;permissions.put(new FactionStorage.PermissionKey(factionId,bucket,safe),allowed);return Result.OK;}catch(SQLException error){log("Could not save faction permission",error);return Result.DATABASE_ERROR;}}
    public boolean actionAllowed(int factionId,String role,String action){if(role!=null&&role.equalsIgnoreCase("ally")){String safe=normalizeAction(action);return permissions.getOrDefault(new FactionStorage.PermissionKey(factionId,"ally",safe),false);}return hasAction(factionId,FactionRole.parse(role,FactionRole.MEMBER),action);}

    public boolean canBuild(Player player,Location location){return canPlace(player,location)&&canBreak(player,location);}
    public boolean canPlace(Player player,Location location){return territoryAction(player,location,"place-blocks","place-blocks");}
    public boolean canBreak(Player player,Location location){return territoryAction(player,location,"break-blocks","break-blocks");}
    private boolean territoryAction(Player player,Location location,String memberAction,String allyAction){if(player==null||location==null)return false;if(player.hasPermission("vertex.factions.bypass"))return true;int owner=factionIdAt(location);if(owner==NO_FACTION)return true;FactionMember member=members.get(player.getUniqueId());if(member==null)return false;if(member.factionId()==owner)return hasAction(member,memberAction);return isAlly(member.factionId(),owner)&&actionAllowed(owner,"ally",allyAction);}
    public boolean canUseContainers(Player player,Location location){if(player==null||location==null)return false;if(player.hasPermission("vertex.factions.bypass"))return true;int owner=factionIdAt(location);if(owner==NO_FACTION)return true;FactionMember member=members.get(player.getUniqueId());if(member==null)return false;return member.factionId()==owner?hasAction(member,"containers"):isAlly(member.factionId(),owner)&&actionAllowed(owner,"ally","containers");}
    public boolean canUseDoors(Player player,Location location){if(player==null||location==null)return false;if(player.hasPermission("vertex.factions.bypass"))return true;int owner=factionIdAt(location);if(owner==NO_FACTION)return true;FactionMember member=members.get(player.getUniqueId());if(member==null)return false;if(member.factionId()==owner)return hasAction(member,"doors");if(!isAlly(member.factionId(),owner))return false;Material type=location.getBlock().getType();String action=type.name().endsWith("_BUTTON")?"buttons":type==Material.LEVER?"levers":"doors";return actionAllowed(owner,"ally",action);}
    public boolean canPvp(Player attacker,Player victim){
        if(attacker==null||victim==null)return true;
        int a=factionId(attacker),b=factionId(victim);
        if(a!=NO_FACTION&&a==b)return plugin.getConfig().getBoolean("factions.pvp.friendly-fire",false);
        if(a!=NO_FACTION&&b!=NO_FACTION&&isAlly(a,b)){
            int territory=factionIdAt(victim.getLocation());
            if(territory==a)return actionAllowed(a,"ally","pvp");
            if(territory==b)return actionAllowed(b,"ally","pvp");
            return plugin.getConfig().getBoolean("factions.pvp.allies-can-pvp",false);
        }
        return true;
    }

    public boolean adjustPersonalPower(UUID uuid, double change) {
        if (uuid == null || !Double.isFinite(change) || change == 0D) return false;
        synchronized (this) {
            FactionPowerProfile old = ensurePowerProfile(uuid);
            if (old == null) return false;
            long next = System.currentTimeMillis() + powerRegenerationIntervalMillis;
            FactionPowerProfile changed = old.withCurrent(old.current() + change, next);
            return persistPersonalPower(changed);
        }
    }

    /** Starts a fresh online-only regeneration interval on login/transfer arrival. */
    public boolean startPowerRegenerationSession(UUID uuid) {
        if (uuid == null) return false;
        synchronized (this) {
            FactionPowerProfile old = ensurePowerProfile(uuid);
            if (old == null) return false;
            FactionPowerProfile changed = old.withCurrent(old.current(),
                    System.currentTimeMillis() + powerRegenerationIntervalMillis);
            return persistPersonalPower(changed);
        }
    }

    /** Changes only current power, preserving the latest maximum. */
    public boolean setPersonalPowerCurrent(UUID uuid, double current) {
        if (uuid == null || !Double.isFinite(current)) return false;
        synchronized (this) {
            FactionPowerProfile old = ensurePowerProfile(uuid);
            if (old == null) return false;
            return persistPersonalPower(new FactionPowerProfile(uuid, current, old.maximum(),
                    System.currentTimeMillis() + powerRegenerationIntervalMillis));
        }
    }

    /** Changes only max power, preserving and safely clamping the latest current value. */
    public boolean setPersonalPowerMaximum(UUID uuid, double maximum) {
        if (uuid == null || !Double.isFinite(maximum)) return false;
        synchronized (this) {
            FactionPowerProfile old = ensurePowerProfile(uuid);
            if (old == null) return false;
            double next = Math.min(absolutePlayerPowerCap, Math.max(0D, maximum));
            return persistPersonalPower(new FactionPowerProfile(uuid, old.current(), next,
                    System.currentTimeMillis() + powerRegenerationIntervalMillis));
        }
    }

    /** Returns the amount actually applied; zero means the profile could not be saved or was capped. */
    public double increaseMaximumPower(UUID uuid, double requested) {
        if (uuid == null || !Double.isFinite(requested) || requested <= 0D) return 0D;
        synchronized (this) {
            FactionPowerProfile old = ensurePowerProfile(uuid);
            if (old == null) return 0D;
            double applied = Math.min(requested, Math.max(0D, absolutePlayerPowerCap - old.maximum()));
            if (applied <= 0D) return 0D;
            FactionPowerProfile changed = old.withMaximum(old.maximum() + applied);
            return persistPersonalPower(changed) ? applied : 0D;
        }
    }

    public long millisecondsUntilPowerRegeneration(UUID uuid) {
        FactionPowerProfile profile = powerProfiles.get(uuid);
        if (profile == null || profile.current() >= profile.maximum()) return -1L;
        return Math.max(0L, profile.nextRegenerationAtMillis() - System.currentTimeMillis());
    }

    public long powerRegenerationIntervalMillis() { return powerRegenerationIntervalMillis; }
    public long createAvailableInMillis(UUID uuid) {
        return Math.max(0L, createCooldowns.getOrDefault(uuid, 0L) - System.currentTimeMillis());
    }
    public long renameAvailableInMillis(int factionId) {
        return Math.max(0L, renameCooldowns.getOrDefault(factionId, 0L) - System.currentTimeMillis());
    }
    public double powerCap(int factionId) { return faction(factionId).map(FactionData::powerMax).orElse(0D); }

    private boolean persistPersonalPower(FactionPowerProfile profile) {
        try {
            FactionStorage.FactionTotals totals = storage.savePowerAndAggregate(profile);
            powerProfiles.put(profile.playerUuid(), profile);
            applyFactionTotals(totals);
            return true;
        } catch (SQLException error) {
            log("Could not update player power", error);
            return false;
        }
    }

    private void applyFactionTotals(FactionStorage.FactionTotals totals) {
        if (totals == null) return;
        factions.computeIfPresent(totals.factionId(), (ignored, faction) ->
                faction.withPower(totals.current(), totals.maximum()));
    }

    private FactionData aggregateWith(int factionId, FactionPowerProfile replacement) {
        FactionData faction = factions.get(factionId);
        if (faction == null) return null;
        double current = 0D;
        double maximum = 0D;
        for (FactionMember member : members(factionId)) {
            FactionPowerProfile profile = replacement != null && member.playerUuid().equals(replacement.playerUuid())
                    ? replacement : powerProfiles.get(member.playerUuid());
            if (profile != null) { current += profile.current(); maximum += profile.maximum(); }
        }
        return faction.withPower(current, maximum);
    }

    private void recalculateFactionPower(int factionId, boolean persist) throws SQLException {
        FactionData faction = factions.get(factionId);
        if (faction == null || faction.system()) return;
        FactionData changed = aggregateWith(factionId, null);
        if (changed == null) return;
        factions.put(factionId, changed);
        if (persist && (Math.abs(changed.power() - faction.power()) > 0.000001D
                || Math.abs(changed.powerMax() - faction.powerMax()) > 0.000001D)) {
            FactionStorage.FactionTotals totals=storage.recalculateFactionPowerChecked(factionId);
            applyFactionTotals(totals);
        }
    }
    public FactionStorage.PlayerSettings settings(UUID uuid){return playerSettings.getOrDefault(uuid,FactionStorage.PlayerSettings.DEFAULT);} public synchronized FactionStorage.PlayerSettings setSettings(UUID uuid,FactionStorage.PlayerSettings value){try{storage.savePlayerSettings(uuid,value);playerSettings.put(uuid,value);return value;}catch(SQLException error){log("Could not save faction player settings",error);return settings(uuid);}}
    public boolean toggleAutoclaim(UUID uuid){FactionStorage.PlayerSettings old=settings(uuid);return setSettings(uuid,new FactionStorage.PlayerSettings(old.chatMode(),!old.autoclaim(),old.mapEnabled())).autoclaim();} public boolean toggleMap(UUID uuid){FactionStorage.PlayerSettings old=settings(uuid);return setSettings(uuid,new FactionStorage.PlayerSettings(old.chatMode(),old.autoclaim(),!old.mapEnabled())).mapEnabled();} public void setMap(UUID uuid,boolean enabled){FactionStorage.PlayerSettings old=settings(uuid);setSettings(uuid,new FactionStorage.PlayerSettings(old.chatMode(),old.autoclaim(),enabled));} public String setChat(UUID uuid,boolean faction){return setChatMode(uuid,faction?"FACTION":"PUBLIC");} public String setChatMode(UUID uuid,String mode){FactionStorage.PlayerSettings old=settings(uuid);String normalized="ALLY".equalsIgnoreCase(mode)?"ALLY":"FACTION".equalsIgnoreCase(mode)?"FACTION":"PUBLIC";return setSettings(uuid,new FactionStorage.PlayerSettings(normalized,old.autoclaim(),old.mapEnabled())).chatMode();} public String toggleChat(UUID uuid){return setChat(uuid,!settings(uuid).chatMode().equals("FACTION"));}
    public void sendFactionChat(Player sender,String message){FactionMember member=sender==null?null:members.get(sender.getUniqueId());if(member==null||messages==null)return;for(Player online:Bukkit.getOnlinePlayers())if(factionId(online)==member.factionId())online.sendMessage(messages.get(online,"native-factions.chat-format","faction",faction(member.factionId()).map(FactionData::tag).orElse("Faction"),"player",sender.getName(),"message",message));}
    public void sendAllyChat(Player sender,String message){FactionMember member=sender==null?null:members.get(sender.getUniqueId());if(member==null||messages==null)return;String tag=faction(member.factionId()).map(FactionData::tag).orElse("Faction");for(Player online:Bukkit.getOnlinePlayers()){int target=factionId(online);if(target==member.factionId()||isAlly(member.factionId(),target))online.sendMessage(messages.get(online,"native-factions.ally-chat-format","faction",tag,"player",sender.getName(),"message",message));}}
    public Component map(Player viewer) {
        Component output = messages == null
                ? Component.text("Faction Map (" + mapWidth + "x" + mapHeight + ")", NamedTextColor.GOLD)
                : messages.get(viewer, "native-factions.map-header", "width", String.valueOf(mapWidth),
                        "height", String.valueOf(mapHeight));
        output = output.append(Component.newline());
        ChunkKey centre = ChunkKey.of(viewer.getLocation());
        int viewerFaction = factionId(viewer);
        int startX = centre.x() - mapWidth / 2;
        int startZ = centre.z() - mapHeight / 2;
        for (int row = 0; row < mapHeight; row++) {
            int z = startZ + row;
            for (int column = 0; column < mapWidth; column++) {
                int x = startX + column;
                ChunkKey chunk = new ChunkKey(centre.world(), x, z);
                int owner = claims.getOrDefault(chunk, NO_FACTION);
                boolean current = x == centre.x() && z == centre.z();
                String symbol = current ? "✚" : owner == NO_FACTION ? "-"
                        : owner == viewerFaction ? "+" : isAlly(viewerFaction, owner) ? "A" : "E";
                NamedTextColor color = current ? NamedTextColor.GREEN : owner == NO_FACTION ? NamedTextColor.DARK_GRAY
                        : owner == viewerFaction ? NamedTextColor.GREEN
                        : isAlly(viewerFaction, owner) ? NamedTextColor.AQUA : NamedTextColor.RED;
                Component cell = Component.text(symbol, color);
                if (owner != NO_FACTION) cell = cell.hoverEvent(HoverEvent.showText(claimHover(viewer, owner, chunk)));
                output = output.append(cell);
            }
            if (row + 1 < mapHeight) output = output.append(Component.newline());
        }
        return output;
    }

    private Component claimHover(Player viewer, int owner, ChunkKey chunk) {
        FactionData faction = factions.get(owner);
        String tag = faction == null ? "Unknown" : faction.tag();
        String x = String.valueOf(chunk.x());
        String z = String.valueOf(chunk.z());
        if (faction != null && faction.system()) {
            return messages.get(viewer, "native-factions.map-hover-system", "faction", tag, "x", x, "z", z);
        }
        if (baseClaimQuery.test(owner, chunk)) {
            return messages.get(viewer, "native-factions.map-hover-base", "faction", tag, "x", x, "z", z);
        }
        long expiresAt = raidClaimExpiryQuery.applyAsLong(chunk);
        if (expiresAt <= 0L) {
            return messages.get(viewer, "native-factions.map-hover-raid-unknown", "faction", tag, "x", x, "z", z);
        }
        long remaining = Math.max(0L, (expiresAt - System.currentTimeMillis() + 999L) / 1_000L);
        return messages.get(viewer, "native-factions.map-hover-raid", "faction", tag,
                "expires", DurationParser.format(remaining), "x", x, "z", z);
    }

    public FactionData ensureSystemFaction(String tag){Optional<FactionData> existing=faction(tag);if(existing.isPresent())return existing.get();long now=System.currentTimeMillis();FactionData draft=new FactionData(-1,tag,"System faction",false,true,0D,0D,now,null);try{int id=storage.createSystemFaction(draft);FactionData created=new FactionData(id,tag,"System faction",false,true,0D,0D,now,null);factions.put(id,created);return created;}catch(SQLException error){throw new IllegalStateException("Could not create system faction "+tag,error);}}
    public void updateOnlineName(Player player){FactionMember member=members.get(player.getUniqueId());if(member==null||member.lastName().equals(player.getName()))return;FactionMember changed=member.withName(player.getName());try{if(storage.updateMemberName(changed.playerUuid(),changed.factionId(),changed.lastName()))members.put(changed.playerUuid(),changed);else members.remove(changed.playerUuid(),member);}catch(SQLException error){log("Could not update faction member name",error);}}
    public void pruneExpiredInvites(){long now=System.currentTimeMillis();for(Map.Entry<FactionStorage.InviteKey,FactionStorage.Invite> entry:List.copyOf(invites.entrySet()))if(entry.getValue().expiresAtMillis()<now){try{storage.deleteInvite(entry.getKey().factionId(),entry.getKey().playerUuid());invites.remove(entry.getKey());}catch(SQLException error){log("Could not remove expired faction invite",error);}}}

    private String normalizeTag(String raw){if(raw==null)return null;String value=raw.trim();String configured=plugin.getConfig().getString("factions.tag-pattern", "[A-Za-z0-9_]{3,16}");Pattern pattern;try{pattern=Pattern.compile(configured);}catch(Exception ignored){pattern=DEFAULT_TAG;}return pattern.matcher(value).matches()?value:null;}
    private String normalizeWarp(String raw){if(raw==null)return null;String value=raw.trim().toLowerCase(Locale.ROOT);return value.matches("[a-z0-9_-]{1,32}")?value:null;}
    private static String normalizeAction(String raw){return raw==null?"":raw.trim().toLowerCase(Locale.ROOT).replace(':','-');}
    private boolean hasAdjacentClaim(ChunkKey key,int factionId){return claims.getOrDefault(new ChunkKey(key.world(),key.x()+1,key.z()),NO_FACTION)==factionId||claims.getOrDefault(new ChunkKey(key.world(),key.x()-1,key.z()),NO_FACTION)==factionId||claims.getOrDefault(new ChunkKey(key.world(),key.x(),key.z()+1),NO_FACTION)==factionId||claims.getOrDefault(new ChunkKey(key.world(),key.x(),key.z()-1),NO_FACTION)==factionId;}
    private boolean defaultAction(FactionRole role, String action) {
        List<String> basicBuild = List.of("build", "place-blocks", "break-blocks", "containers", "doors", "home");
        return switch (role) {
            case LEADER -> true;
            case COLEADER -> !action.equals("disband") && !action.equals("leader");
            case ADMIN -> basicBuild.contains(action) || List.of("invite", "kick", "promote", "demote",
                    "relation", "ally-request", "enemy-declare", "neutral-request", "claim", "unclaim",
                    "territory", "rally-set", "rally-clear", "bank-deposit", "bank-withdraw", "xp-deposit",
                    "xp-withdraw", "tnt-deposit", "tnt-withdraw", "tnt-fill", "vault-use", "spawner-gui",
                    "spawner-place", "spawner-add", "spawner-remove", "collector-open", "collector-break",
                    "collector-withdraw", "collector-sell", "chunkbuster-use").contains(action);
            case MODERATOR -> basicBuild.contains(action) || List.of("invite", "kick", "claim", "unclaim",
                    "territory", "rally-set", "rally-clear", "bank-deposit", "spawner-gui", "spawner-place",
                    "spawner-add", "spawner-remove", "collector-open", "collector-break", "collector-withdraw",
                    "collector-sell", "chunkbuster-use").contains(action);
            case MEMBER -> basicBuild.contains(action) || List.of("rally-set", "rally-clear", "bank-deposit",
                    "spawner-gui", "spawner-place", "spawner-add", "spawner-remove", "collector-open",
                    "collector-break", "collector-sell").contains(action);
            case RECRUIT -> List.of("home", "collector-open").contains(action);
        };
    }

    /** Config fallback only; durable per-faction overrides are checked separately. */
    boolean configuredActionDefault(FactionRole role, String action) {
        if (role == FactionRole.LEADER) return true;
        String safe = normalizeAction(action);
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(
                "factions.permissions.defaults." + role.permissionBucket());
        if (section != null && section.contains(safe)) return section.getBoolean(safe);
        if (section != null && (safe.equals("place-blocks") || safe.equals("break-blocks"))
                && section.contains("build")) return section.getBoolean("build");
        return defaultAction(role, safe);
    }

    /** Configured fallback used by storage transactions that re-check live role permissions. */
    public boolean configuredActionDefaultFor(FactionRole role, String action) {
        return role != null && configuredActionDefault(role, action);
    }
    private double defaultPower(){return powerStartingPerMember;} private double defaultPowerMax(){return powerMaxPerMember;}
    private static int bounded(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    private static double positive(double value,double fallback){return Double.isFinite(value)&&value>=0D?value:fallback;}
    private static long safeAddMillis(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }
    private FactionData.Home home(Location location){return new FactionData.Home(localShardId(),location.getWorld().getName(),location.getX(),location.getY(),location.getZ(),location.getYaw(),location.getPitch());}
    private Location location(FactionData.Home home){if(home==null||!home.shardId().isBlank()&&!home.shardId().equalsIgnoreCase(localShardId()))return null;World world=Bukkit.getWorld(home.world());return world==null?null:new Location(world,home.x(),home.y(),home.z(),home.yaw(),home.pitch());}
    private String localShardId(){return plugin.getConfig().getBoolean("network.enabled",false)?plugin.getConfig().getString("network.shard-id",""):"";}
    private void log(String message,Exception error){plugin.getLogger().log(Level.WARNING,message,error);}
    public record ShieldDisplay(boolean active, long remainingSeconds, long nextSeconds) { }
    private static Exception asException(Throwable error){return error instanceof Exception exception?exception:new Exception(error);}
}
