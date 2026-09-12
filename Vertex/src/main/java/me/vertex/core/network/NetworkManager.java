package me.vertex.core.network;

import me.vertex.core.lang.Messages;
import me.vertex.core.grace.DurationParser;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Durable Velocity handoffs and shard health. SQL event polling is the
 * invalidation layer, which keeps deployment dependency-free while still
 * allowing Redis to replace it later behind this service boundary.
 */
public final class NetworkManager implements Listener {
    public enum TransferStatus {
        STARTED, LOCAL_COMPLETE, QUEUED, DISABLED, BUSY, FULL, OFFLINE, DRAINING,
        RESTARTING, CRASH_RECOVERY, INVALID_DESTINATION, SAVE_FAILED
    }

    private final Plugin plugin;
    private final NetworkStorage storage;
    private final Messages messages;
    private final CombatManager combat;
    private final Map<UUID, String> departing = new ConcurrentHashMap<>();
    /** Blocks state changes from immediately before snapshot capture until SQL accepts/rejects it. */
    private final Set<UUID> preparingTransfers = ConcurrentHashMap.newKeySet();
    /** Destination-side handoffs currently applying a snapshot/teleport. */
    private final Map<UUID, String> incomingTransfers = new ConcurrentHashMap<>();
    /** Resolves true only for an ordinary join that may receive first-join Spawn handling. */
    private final Map<UUID, CompletableFuture<Boolean>> joinSpawnAdmissions = new ConcurrentHashMap<>();
    private final Set<UUID> evacuationDepartures = ConcurrentHashMap.newKeySet();
    private final Map<String, Runnable> invalidationHandlers = new ConcurrentHashMap<>();
    private final Map<String, java.util.function.Predicate<NetworkLocation>> destinationValidators = new ConcurrentHashMap<>();
    private final Map<String, DestinationRepair> destinationRepairs = new ConcurrentHashMap<>();
    private final Map<String, java.util.function.BiConsumer<Player, NetworkLocation>> arrivalHandlers = new ConcurrentHashMap<>();
    private final Map<String, NetworkStorage.ShardRow> shardCache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTransferAlert = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Object shardStateWriteLock = new Object();
    private CompletableFuture<Void> shardStateWriteTail = CompletableFuture.completedFuture(null);
    private final AtomicLong plannedRestartGeneration = new AtomicLong();
    private volatile boolean enabled;
    private volatile String shardId;
    private volatile String role;
    private volatile String spawnShard;
    private volatile String hubShard;
    private volatile int maxPlayers;
    private volatile long heartbeatStaleMillis;
    private volatile long queueTimeoutMillis;
    private volatile long transferAlertMillis;
    private volatile long transferAlertRepeatMillis;
    private volatile ShardState state = ShardState.OFFLINE;
    private volatile long restartEta;
    private volatile long lastEventId;
    private volatile long lastPruneAt;
    private BukkitTask tickTask;

    public NetworkManager(Plugin plugin, Database database, Messages messages, CombatManager combat) {
        this.plugin = plugin;
        this.storage = new NetworkStorage(database);
        this.messages = messages;
        this.combat = combat;
        reloadConfig(database.dialect());
    }

    public void init() throws Exception {
        storage.init();
        lastEventId = storage.latestEventId();
        Optional<NetworkStorage.ShardRow> previous = storage.shard(shardId);
        for (NetworkStorage.ShardRow shard : storage.shards()) shardCache.put(shard.shardId(), shard);
        long now = System.currentTimeMillis();
        if (!enabled) {
            state = ShardState.ONLINE;
            return;
        }
        // A clean stop writes OFFLINE; a planned restart writes RESTARTING.
        // Finding ONLINE/DRAINING here means the previous JVM disappeared
        // without completing shutdown, even if its last heartbeat is recent.
        if (previous.isPresent() && (previous.get().state() == ShardState.CRASH_RECOVERY
                || previous.get().state() == ShardState.ONLINE
                || previous.get().state() == ShardState.DRAINING)) {
            state = ShardState.CRASH_RECOVERY;
            restartEta = 0L;
        } else {
            state = ShardState.ONLINE;
            restartEta = 0L;
        }
        state = storage.heartbeat(shardId, role, state, maxPlayers,
                Bukkit.getOnlinePlayers().size(), restartEta, "startup");
    }

    public void start() {
        if (!enabled) return;
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scheduleMaintenance, 20L, 20L);
    }

    public void reloadConfig(Database.Dialect dialect) {
        NetworkDeployment deployment = NetworkDeployment.read(plugin.getConfig());
        if (deployment.dialect() != dialect) throw new IllegalArgumentException("Configured storage does not match the open database");
        if (shardId != null && (enabled != deployment.enabled() || !shardId.equals(deployment.shardId()))) {
            throw new IllegalArgumentException("Network deployment identity can only change at startup");
        }
        enabled = deployment.enabled();
        shardId = deployment.shardId();
        role = normalize(plugin.getConfig().getString("network.role", "base"));
        spawnShard = normalize(plugin.getConfig().getString("network.spawn-shard", "spawn"));
        hubShard = normalize(plugin.getConfig().getString("network.hub-shard", "hub"));
        maxPlayers = bounded(plugin.getConfig().getInt("network.max-players", 100), 1, 10_000);
        heartbeatStaleMillis = seconds("network.heartbeat-stale-seconds", 20L, 5L) * 1_000L;
        queueTimeoutMillis = seconds("network.queue-timeout-seconds", 300L, 5L) * 1_000L;
        transferAlertMillis = seconds("network.transfer-alert-seconds", 30L, 5L) * 1_000L;
        transferAlertRepeatMillis = seconds("network.transfer-alert-repeat-seconds", 60L, 5L) * 1_000L;
    }

    public boolean enabled() { return enabled; }
    public String shardId() { return shardId; }
    public ShardState state() { return state; }
    public NetworkStorage storage() { return storage; }
    public NetworkStorage.ShardRow shard(String id) { return shardCache.get(normalize(id)); }
    public CompletableFuture<Boolean> firstJoinSpawnAllowed(UUID player) {
        if (!enabled) return CompletableFuture.completedFuture(true);
        CompletableFuture<Boolean> decision = joinSpawnAdmissions.get(player);
        return decision == null ? CompletableFuture.completedFuture(false) : decision;
    }
    public TransferStatus destinationStatus(NetworkLocation destination) {
        if (destination == null) return TransferStatus.INVALID_DESTINATION;
        if (destination.shardId().equalsIgnoreCase(shardId)) {
            if (destination.resolveLocal(shardId) == null) return TransferStatus.INVALID_DESTINATION;
            return enabled ? statusForState(state) : null;
        }
        if (!enabled) return TransferStatus.OFFLINE;
        return availability(shardCache.get(destination.shardId()));
    }

    public CompletableFuture<Long> cooldown(UUID player, String type) {
        CompletableFuture<Long> result = CompletableFuture.supplyAsync(() -> {
            try { return storage.cooldown(player, type); }
            catch (Exception error) { log("Could not load teleport cooldown", error); return Long.MAX_VALUE; }
        });
        track(result);
        return result;
    }

    public CompletableFuture<Boolean> saveCooldown(UUID player, String type, long availableAt) {
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
            try { storage.saveCooldown(player, type, availableAt); return true; }
            catch (Exception error) { log("Could not save teleport cooldown", error); return false; }
        });
        track(result);
        return result;
    }
    public boolean isTrustedDeparture(UUID player) {
        return player != null && (departing.containsKey(player) || incomingTransfers.containsKey(player)
                || evacuationDepartures.contains(player));
    }

    public void registerInvalidation(String topic, Runnable handler) {
        if (topic != null && handler != null) invalidationHandlers.put(normalize(topic), handler);
    }

    public void registerDestinationValidator(String reason, java.util.function.Predicate<NetworkLocation> validator) {
        if (reason != null && validator != null) destinationValidators.put(safeReason(reason), validator);
    }

    /** Allows a destination backend to reroll a still-PREPARED location before loading player state. */
    public void registerDestinationRepair(String reason, DestinationRepair repair) {
        if (reason != null && repair != null) destinationRepairs.put(safeReason(reason), repair);
    }

    public void registerArrivalHandler(String reason, java.util.function.BiConsumer<Player, NetworkLocation> handler) {
        if (reason != null && handler != null) arrivalHandlers.put(safeReason(reason), handler);
    }

    public void publishInvalidation(String topic, String payload) {
        if (!enabled || topic == null) return;
        track(CompletableFuture.runAsync(() -> {
            try { storage.publish(shardId, normalize(topic), payload == null ? "" : payload); }
            catch (Exception error) { log("Could not publish network invalidation", error); }
        }));
    }

    /** Must be called on the server thread so ItemStacks are captured safely. */
    public CompletableFuture<TransferStatus> transfer(Player player, NetworkLocation destination,
            String reason, boolean allowQueue) {
        if (player == null || destination == null) return CompletableFuture.completedFuture(TransferStatus.INVALID_DESTINATION);
        UUID playerId = player.getUniqueId();
        if (preparingTransfers.contains(playerId) || departing.containsKey(playerId)
                || incomingTransfers.containsKey(playerId)) {
            return CompletableFuture.completedFuture(TransferStatus.BUSY);
        }
        if (destination.shardId().equalsIgnoreCase(shardId)) {
            Location local = destination.resolveLocal(shardId);
            if (local == null) return CompletableFuture.completedFuture(TransferStatus.INVALID_DESTINATION);
            TransferStatus localState = enabled ? statusForState(state) : null;
            if (localState != null) return CompletableFuture.completedFuture(localState);
            return player.teleportAsync(local).thenApply(done -> done
                    ? TransferStatus.LOCAL_COMPLETE : TransferStatus.INVALID_DESTINATION);
        }
        if (!enabled) return CompletableFuture.completedFuture(TransferStatus.DISABLED);
        if (!preparingTransfers.add(playerId)) {
            return CompletableFuture.completedFuture(TransferStatus.BUSY);
        }
        final PlayerStateSnapshot snapshot;
        try { snapshot = PlayerStateSnapshot.capture(player); }
        catch (RuntimeException error) {
            preparingTransfers.remove(playerId);
            return CompletableFuture.completedFuture(TransferStatus.SAVE_FAILED);
        }

        CompletableFuture<TransferStatus> result = CompletableFuture.supplyAsync(() -> prepare(playerId,
                destination, safeReason(reason), allowQueue, snapshot));
        track(result);
        result.whenComplete((ignored, error) -> preparingTransfers.remove(playerId));
        result.thenAccept(status -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (status == TransferStatus.STARTED) {
                connect(player, destination.shardId());
                watchDeparture(player,departing.get(player.getUniqueId()));
            }
            else sendFailure(player, status, destination.shardId());
        }));
        return result;
    }

    private TransferStatus prepare(UUID player, NetworkLocation destination, String reason,
            boolean allowQueue, PlayerStateSnapshot snapshot) {
        try {
            if (storage.unresolved(player).isPresent() || storage.queued(player).isPresent()) return TransferStatus.BUSY;
            NetworkStorage.ShardRow target = storage.shard(destination.shardId()).orElse(null);
            TransferStatus availability = availability(target);
            if ((availability == TransferStatus.RESTARTING || availability == TransferStatus.DRAINING)
                    && allowQueue) {
                return storage.queue(player, shardId, destination, reason,
                        safeAdd(System.currentTimeMillis(), queueTimeoutMillis))
                        ? TransferStatus.QUEUED : TransferStatus.BUSY;
            }
            if (availability != null) return availability;
            byte[] encoded = snapshot.encode();
            long now = System.currentTimeMillis();
            String id = UUID.randomUUID().toString();
            if(!storage.createHandoff(new NetworkStorage.Handoff(id, player, shardId, destination,
                    reason, "PREPARED", encoded, now, now, null)))return TransferStatus.BUSY;
            departing.put(player, id);
            return TransferStatus.STARTED;
        } catch (Exception error) {
            log("Could not prepare cross-shard transfer", error);
            return TransferStatus.SAVE_FAILED;
        }
    }

    private TransferStatus availability(NetworkStorage.ShardRow target) {
        if (target == null || System.currentTimeMillis() - target.heartbeatAt() > heartbeatStaleMillis) return TransferStatus.OFFLINE;
        if (target.currentPlayers() >= target.maxPlayers()) return TransferStatus.FULL;
        return switch (target.state()) {
            case ONLINE -> null;
            case DRAINING -> TransferStatus.DRAINING;
            case RESTARTING -> TransferStatus.RESTARTING;
            case OFFLINE -> TransferStatus.OFFLINE;
            case CRASH_RECOVERY -> TransferStatus.CRASH_RECOVERY;
        };
    }

    private static TransferStatus statusForState(ShardState current) {
        return switch (current) {
            case ONLINE -> null;
            case DRAINING -> TransferStatus.DRAINING;
            case RESTARTING -> TransferStatus.RESTARTING;
            case OFFLINE -> TransferStatus.OFFLINE;
            case CRASH_RECOVERY -> TransferStatus.CRASH_RECOVERY;
        };
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        CompletableFuture<Boolean> spawnAdmission = new CompletableFuture<>();
        CompletableFuture<Boolean> replaced = joinSpawnAdmissions.put(player.getUniqueId(), spawnAdmission);
        if (replaced != null) replaced.complete(false);
        CompletableFuture<JoinState> lookup = CompletableFuture.supplyAsync(() -> {
            try {
                NetworkStorage.Handoff incoming = storage.incoming(player.getUniqueId(), shardId).orElse(null);
                NetworkStorage.Handoff unresolved = incoming == null
                        ? storage.unresolved(player.getUniqueId()).orElse(null) : null;
                return new JoinState(incoming, unresolved, false);
            } catch (Exception error) {
                log("Could not load incoming transfer", error);
                return new JoinState(null, null, true);
            }
        });
        track(lookup);
        lookup.thenAccept(join -> Bukkit.getScheduler().runTask(plugin, () -> {
            boolean ordinary = !join.failed() && join.incoming() == null && join.unresolved() == null
                    && state != ShardState.CRASH_RECOVERY;
            spawnAdmission.complete(ordinary);
            if (join.failed()) routeFallback(player, "transfer state unavailable");
            else if (join.incoming() != null) acceptIncoming(player, join.incoming());
            else if (join.unresolved() != null) recoverOrphan(player, join.unresolved());
            else if (state == ShardState.CRASH_RECOVERY) routeFallback(player, "crash recovery");
        }));
    }

    /** Restores the source-authoritative snapshot when a broken handoff reaches Spawn. */
    private void recoverOrphan(Player player, NetworkStorage.Handoff handoff) {
        if (!shardId.equalsIgnoreCase(spawnShard) && !role.equalsIgnoreCase("spawn")) {
            routeFallback(player, "unresolved handoff");
            return;
        }
        final PlayerStateSnapshot snapshot;
        try { snapshot=PlayerStateSnapshot.decode(handoff.snapshot()); }
        catch(Exception error){log("Could not decode source-authoritative recovery snapshot",error);player.sendMessage(messages.get(player,"network.transfer-recovery-failed"));return;}
        CompletableFuture<Boolean> recovery = CompletableFuture.supplyAsync(() -> {
            try { return storage.transition(handoff.id(),handoff.state(),"RECOVERING","restoring source snapshot at Spawn"); }
            catch (Exception error) { log("Could not lock unresolved transfer recovery", error); return false; }
        });
        track(recovery);
        recovery.thenAccept(locked -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!locked || !player.isOnline()) return;
            try {
                snapshot.apply(player);
                player.sendMessage(messages.get(player, "network.transfer-recovered"));
                CompletableFuture<Boolean> complete=CompletableFuture.supplyAsync(()->{
                    try{return storage.transition(handoff.id(),"RECOVERING","ABORTED","restored source snapshot at Spawn");}
                    catch(Exception error){log("Could not finalize transfer recovery",error);return false;}
                });
                track(complete);
            } catch (Exception error) {
                log("Could not restore source-authoritative player snapshot", error);
                player.sendMessage(messages.get(player, "network.transfer-recovery-failed"));
                track(CompletableFuture.runAsync(()->{try{storage.markRecoveryRequired(handoff.id(),"snapshot apply failed");}catch(Exception saveError){log("Could not retain failed recovery",saveError);}}));
            }
        }));
    }

    private void acceptIncoming(Player player, NetworkStorage.Handoff handoff) {
        if (!player.isOnline()) return;
        if (state != ShardState.ONLINE || Bukkit.getOnlinePlayers().size() > maxPlayers || handoff == null) {
            if (state != ShardState.ONLINE) routeFallback(player, "shard-unavailable");
            else if (Bukkit.getOnlinePlayers().size() > maxPlayers) routeFallback(player, "shard-full");
            return;
        }
        Location destination = handoff.destination().resolveLocal(shardId);
        java.util.function.Predicate<NetworkLocation> validator = destinationValidators.get(handoff.reason());
        if (destination == null) {
            failIncoming(player, handoff, "destination world unavailable");
            return;
        }
        if (validator != null && !validator.test(handoff.destination())) {
            DestinationRepair repair = destinationRepairs.get(handoff.reason());
            if (repair == null) {
                failIncoming(player, handoff, "destination validation failed");
                return;
            }
            repair.repair(player, handoff.destination(), replacement -> {
                if (replacement == null || !replacement.shardId().equalsIgnoreCase(shardId)) {
                    failIncoming(player, handoff, "destination reroll failed");
                    return;
                }
                CompletableFuture<Boolean> update = CompletableFuture.supplyAsync(() -> {
                    try { return storage.replacePreparedDestination(handoff.id(), replacement); }
                    catch (Exception error) { log("Could not persist destination reroll", error); return false; }
                });
                track(update);
                update.thenAccept(saved -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (!saved) {
                        failIncoming(player, handoff, "destination reroll was stale");
                        return;
                    }
                    NetworkStorage.Handoff repaired = new NetworkStorage.Handoff(handoff.id(), handoff.playerUuid(),
                            handoff.sourceShard(), replacement, handoff.reason(), handoff.state(), handoff.snapshot(),
                            handoff.createdAt(), System.currentTimeMillis(), null);
                    acceptIncoming(player, repaired);
                }));
            });
            return;
        }
        CompletableFuture<Boolean> loading = CompletableFuture.supplyAsync(() -> {
            try { return storage.transition(handoff.id(), "PREPARED", "LOADING", null); }
            catch (Exception error) { log("Could not lock incoming transfer", error); return false; }
        });
        track(loading);
        loading.thenAccept(locked -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!locked) {
                if (player.isOnline()) routeFallback(player, "handoff no longer available");
                return;
            }
            incomingTransfers.put(player.getUniqueId(), handoff.id());
            if (!player.isOnline()) {
                abortIncoming(player.getUniqueId(), handoff.id(), "player disconnected during handoff");
                return;
            }
            try {
                PlayerStateSnapshot.decode(handoff.snapshot()).apply(player);
            } catch (Exception error) {
                incomingTransfers.remove(player.getUniqueId(), handoff.id());
                failIncoming(player, handoff, "snapshot decode failed");
                return;
            }
            player.teleportAsync(destination).thenAccept(teleported -> {
                CompletableFuture<Boolean> completion = CompletableFuture.supplyAsync(() -> {
                    try {
                        return storage.transition(handoff.id(), "LOADING", teleported ? "ACKED" : "FAILED",
                                teleported ? null : "destination teleport failed");
                    } catch (Exception error) {
                        log("Could not finalize transfer " + handoff.id(), error);
                        return false;
                    }
                });
                track(completion);
                completion.thenAccept(committed -> Bukkit.getScheduler().runTask(plugin, () -> {
                    incomingTransfers.remove(player.getUniqueId(), handoff.id());
                    if (teleported && committed && player.isOnline()) {
                        java.util.function.BiConsumer<Player, NetworkLocation> handler = arrivalHandlers.get(handoff.reason());
                        if (handler != null) handler.accept(player, handoff.destination());
                    } else if (player.isOnline()) {
                        routeFallback(player, committed ? "destination-failed" : "handoff acknowledgement failed");
                    }
                }));
            });
        }));
    }

    private void abortIncoming(UUID player, String handoffId, String reason) {
        incomingTransfers.remove(player, handoffId);
        track(CompletableFuture.runAsync(() -> {
            try { storage.markRecoveryRequired(handoffId, reason); }
            catch (Exception error) { log("Could not mark incoming transfer for recovery", error); }
        }));
    }

    private void failIncoming(Player player, NetworkStorage.Handoff handoff, String reason) {
        if (handoff != null) track(CompletableFuture.runAsync(() -> {
            try { storage.markRecoveryRequired(handoff.id(), reason); }
            catch (Exception error) { log("Could not preserve failed incoming transfer", error); }
        }));
        routeFallback(player, reason);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        CompletableFuture<Boolean> admission = joinSpawnAdmissions.remove(playerId);
        if (admission != null) admission.complete(false);
        preparingTransfers.remove(playerId);
        String incoming = incomingTransfers.get(playerId);
        if (incoming != null) abortIncoming(playerId, incoming, "player disconnected during handoff");
        String transfer = departing.get(playerId);
        if (transfer == null) { evacuationDepartures.remove(playerId);return; }
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            // Keep the durable snapshot unresolved. Destination ACK, Spawn
            // recovery, or an explicit staff decision is the only authority
            // allowed to release its one-transfer lock.
            departing.remove(playerId, transfer);
            evacuationDepartures.remove(playerId);
        }, 300L);
    }

    public void beginPlannedDrain(long etaMillis) {
        if (!enabled || state == ShardState.CRASH_RECOVERY) return;
        long generation = plannedRestartGeneration.incrementAndGet();
        transitionAndEvacuate(ShardState.DRAINING, etaMillis, generation);
    }

    public void beginPlannedRestart() {
        if (!enabled || state == ShardState.CRASH_RECOVERY) return;
        long generation = plannedRestartGeneration.incrementAndGet();
        transitionAndEvacuate(ShardState.RESTARTING, restartEta, generation);
    }

    public void cancelPlannedRestart() {
        if (enabled) setState(ShardState.ONLINE, 0L, "restart-cancelled");
    }

    public CompletableFuture<Boolean> setState(ShardState next, long etaMillis, String actor) {
        plannedRestartGeneration.incrementAndGet();
        return writeState(next, etaMillis, actor);
    }

    /** Serializes shard-state writes so an older async transition cannot win after a newer admin decision. */
    private CompletableFuture<Boolean> writeState(ShardState next, long etaMillis, String actor) {
        if (!enabled || next == null) return CompletableFuture.completedFuture(false);
        int onlineCount = Bukkit.getOnlinePlayers().size();
        CompletableFuture<Boolean> result;
        synchronized (shardStateWriteLock) {
            result = shardStateWriteTail.handle((ignored, error) -> null).thenApplyAsync(ignored -> {
                try {
                    storage.setShardState(shardId, next, etaMillis, actor);
                    state = next;
                    restartEta = etaMillis;
                    shardCache.put(shardId, new NetworkStorage.ShardRow(shardId, role, next, maxPlayers,
                            onlineCount, System.currentTimeMillis(), etaMillis, actor));
                    storage.publish(shardId, "shard-state", shardId + ":" + next.name());
                    return true;
                } catch (Exception error) {
                    log("Could not change shard state", error);
                    return false;
                }
            });
            shardStateWriteTail = result.handle((ignored, error) -> null);
        }
        track(result);
        return result;
    }

    private void transitionLocal(ShardState next, long etaMillis, String actor) {
        setState(next, etaMillis, actor);
    }

    /** Never starts evacuation until the shared shard state is durably closed to new arrivals. */
    private void transitionAndEvacuate(ShardState next, long etaMillis, long generation) {
        writeState(next, etaMillis, "planned-restart").thenAccept(saved ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (saved && state == next && plannedRestartGeneration.get() == generation) {
                        evacuatePlayers();
                    }
                }));
    }

    private void evacuatePlayers() {
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            evacuationDepartures.add(player.getUniqueId());
            if (combat != null) combat.clear(player.getUniqueId());
            final PlayerStateSnapshot snapshot;
            try{snapshot=PlayerStateSnapshot.capture(player);}
            catch(RuntimeException error){player.kick(messages.get(player,"network.evacuation-unavailable"));continue;}
            UUID playerId=player.getUniqueId();
            track(CompletableFuture.supplyAsync(() -> prepareEvacuation(playerId,snapshot)).thenAccept(plan ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (plan == null || plan.targetShard() == null) player.kick(messages.get(player, "network.evacuation-unavailable"));
                        else {connect(player, plan.targetShard());watchDeparture(player,departing.get(player.getUniqueId()));}
                    })));
        }
    }

    /** Persists evacuation state before Velocity removes the player from this shard. */
    private EvacuationPlan prepareEvacuation(UUID player,PlayerStateSnapshot snapshot){
        try{
            NetworkStorage.LocationRow spawn=storage.location("spawn","primary").orElse(null);
            NetworkStorage.ShardRow spawnState=spawn==null?null:storage.shard(spawn.location().shardId()).orElse(null);
            String target=spawn!=null&&availability(spawnState)==null?spawn.location().shardId():fallbackShard();
            if(target==null)return null;
            if(spawn==null){
                plugin.getLogger().warning("Evacuating "+player+" without a durable Spawn handoff because network Spawn is not configured.");
                return new EvacuationPlan(target,false);
            }
            long now=System.currentTimeMillis();String id=UUID.randomUUID().toString();
            boolean locked=storage.createHandoff(new NetworkStorage.Handoff(id,player,shardId,
                    spawn.location(),"planned-restart","PREPARED",snapshot.encode(),now,now,
                    target.equalsIgnoreCase(spawn.location().shardId())?null:"parked while Spawn unavailable"));
            if(!locked)return null;
            departing.put(player,id);
            return new EvacuationPlan(target,true);
        }catch(Exception error){log("Could not persist planned-restart evacuation",error);return null;}
    }

    private String fallbackShard() {
        try {
            for (String candidate : List.of(spawnShard, hubShard)) {
                NetworkStorage.ShardRow row = storage.shard(candidate).orElse(null);
                // Hub intentionally need not run Vertex or publish gameplay
                // heartbeats; Velocity remains the authority for that fallback.
                if (candidate.equalsIgnoreCase(hubShard) && row == null) return candidate;
                if (availability(row) == null) return candidate;
            }
        } catch (Exception error) { log("Could not resolve network fallback", error); }
        return null;
    }

    private void routeFallback(Player player, String reason) {
        track(CompletableFuture.supplyAsync(this::fallbackShard).thenAccept(target ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    evacuationDepartures.add(player.getUniqueId());
                    if (combat != null) combat.clear(player.getUniqueId());
                    if (target == null || target.equals(shardId)) {
                        player.kick(messages.get(player, "network.fallback-unavailable"));
                    } else connect(player, target);
        })));
    }

    /** First-join/crash-recovery path; ordinary player commands must not substitute Hub. */
    public void routeToFallback(Player player) {
        if (player != null && player.isOnline()) routeFallback(player, "join fallback");
    }

    private void scheduleMaintenance() {
        if (!enabled) return;
        int onlineCount = Bukkit.getOnlinePlayers().size();
        track(CompletableFuture.runAsync(() -> maintenance(onlineCount)));
    }

    private void maintenance(int onlineCount) {
        try {
            state = storage.heartbeat(shardId, role, state, maxPlayers, onlineCount, restartEta, "heartbeat");
            shardCache.put(shardId, storage.shard(shardId).orElseThrow());
            for (NetworkStorage.ShardRow shard : storage.shards()) shardCache.put(shard.shardId(), shard);
            markStaleShards();
            pollEvents();
            processQueues();
            alertStaleTransfers();
            long now = System.currentTimeMillis();
            if (now - lastPruneAt >= TimeUnit.MINUTES.toMillis(5)) {
                storage.prune(now - TimeUnit.MINUTES.toMillis(30), now - TimeUnit.HOURS.toMillis(6));
                lastPruneAt = now;
            }
        } catch (Exception error) { log("Network maintenance failed", error); }
    }

    private void markStaleShards() throws Exception {
        long now = System.currentTimeMillis();
        for (NetworkStorage.ShardRow shard : storage.shards()) {
            if (shard.shardId().equals(shardId)) continue;
            if ((shard.state() == ShardState.ONLINE || shard.state() == ShardState.DRAINING)
                    && now - shard.heartbeatAt() > heartbeatStaleMillis) {
                storage.setShardState(shard.shardId(), ShardState.CRASH_RECOVERY, 0L, "heartbeat-timeout");
                shardCache.put(shard.shardId(), new NetworkStorage.ShardRow(shard.shardId(), shard.role(),
                        ShardState.CRASH_RECOVERY, shard.maxPlayers(), shard.currentPlayers(), now, 0L,
                        "heartbeat-timeout"));
                storage.publish(shardId, "shard-state", shard.shardId() + ":CRASH_RECOVERY");
            }
        }
    }

    private void pollEvents() throws Exception {
        for (NetworkStorage.EventRow event : storage.eventsAfter(lastEventId, 500)) {
            lastEventId = Math.max(lastEventId, event.id());
            if (event.sourceShard().equals(shardId)) continue;
            Runnable handler = invalidationHandlers.get(normalize(event.topic()));
            if (handler != null) Bukkit.getScheduler().runTask(plugin, handler);
            // Queue owners are notified by processQueues on their source shard.
        }
    }

    private void processQueues() throws Exception {
        long now = System.currentTimeMillis();
        for (NetworkStorage.QueuedTransfer queued : storage.queuedFrom(shardId)) {
            if (queued.expiresAt() <= now) {
                storage.deleteQueue(queued.playerUuid());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(queued.playerUuid());
                    if (player != null) player.sendMessage(messages.get(player, "network.queue-expired"));
                });
                continue;
            }
            NetworkStorage.ShardRow target = storage.shard(queued.destination().shardId()).orElse(null);
            if (target != null && target.state() == ShardState.CRASH_RECOVERY) {
                storage.deleteQueue(queued.playerUuid());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(queued.playerUuid());
                    if (player != null) player.sendMessage(messages.get(player,
                            "network.queue-cancelled-recovery", "shard", queued.destination().shardId()));
                });
                                continue;
            }
            if (availability(target) == null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player player = Bukkit.getPlayer(queued.playerUuid());
                    if (player != null && player.isOnline()) {
                        CompletableFuture<Void> resume = CompletableFuture.runAsync(() -> {
                            try { storage.deleteQueue(queued.playerUuid()); }
                            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
                        }).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (player.isOnline()) transfer(player, queued.destination(), queued.reason(), false);
                        }));
                        track(resume);
                    }
                });
            }
        }
    }

    private void alertStaleTransfers() throws Exception {
        long now = System.currentTimeMillis();
        for (NetworkStorage.Handoff handoff : storage.staleHandoffs(now - transferAlertMillis)) {
            long last = lastTransferAlert.getOrDefault(handoff.id(), 0L);
            if (now - last < transferAlertRepeatMillis) continue;
            lastTransferAlert.put(handoff.id(), now);
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Player staff : Bukkit.getOnlinePlayers()) {
                    if (staff.hasPermission("vertex.network.transfer.alerts")) staff.sendMessage(messages.get(staff,
                            "network.transfer-stuck", "id", handoff.id(), "player", handoff.playerUuid().toString(),
                            "source", handoff.sourceShard(), "destination", handoff.destination().shardId()));
                }
            });
        }
    }

    public List<NetworkStorage.ShardRow> shards() {
        try { return storage.shards(); }
        catch (Exception error) { log("Could not inspect shards", error); return List.of(); }
    }

    public List<NetworkStorage.Handoff> unresolvedTransfers() {
        try { return storage.unresolvedHandoffs(); }
        catch (Exception error) { log("Could not inspect transfers", error); return List.of(); }
    }

    /** Fails closed when shared storage cannot prove the network is idle. */
    public boolean hasActiveTransfers() {
        if (!enabled) return false;
        try { return storage.hasActiveTransfers(); }
        catch (Exception error) { log("Could not verify active network transfers", error); return true; }
    }

    public CompletableFuture<Boolean> resolveTransfer(String id, boolean acknowledge, String actor) {
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
            try { return storage.resolveHandoff(id, acknowledge ? "ACKED" : "ABORTED", "manually resolved by " + actor); }
            catch (Exception error) { log("Could not resolve transfer", error); return false; }
        });
        track(result);
        return result;
    }

    private void sendFailure(Player player, TransferStatus status, String destination) {
        String key = switch (status) {
            case QUEUED -> "network.transfer-queued";
            case BUSY -> "network.transfer-busy";
            case FULL -> "network.shard-full";
            case RESTARTING -> "network.shard-restarting";
            case DRAINING -> "network.shard-draining";
            case CRASH_RECOVERY -> "network.shard-recovery";
            case OFFLINE, DISABLED -> "network.shard-offline";
            case INVALID_DESTINATION -> "network.destination-invalid";
            case SAVE_FAILED -> "network.transfer-save-failed";
            default -> null;
        };
        if (key != null) {
            NetworkStorage.ShardRow target = shardCache.get(normalize(destination));
            long seconds = target == null || target.restartEta() <= 0L ? 0L
                    : Math.max(0L, (target.restartEta() - System.currentTimeMillis() + 999L) / 1_000L);
            player.sendMessage(messages.get(player, key, "shard", destination,
                    "time", seconds <= 0L ? messages.getRaw(player, "network.restart-time-unknown")
                            : DurationParser.format(seconds)));
        }
    }

    private void connect(Player player, String target) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF("Connect");
                out.writeUTF(target);
            }
            player.sendPluginMessage(plugin, "BungeeCord", bytes.toByteArray());
        } catch (IOException error) {
            player.sendMessage(messages.get(player, "network.transfer-save-failed", "shard", target));
        }
    }

    /** A failed proxy connect must not leave a player editing a captured snapshot. */
    private void watchDeparture(Player player,String transferId){
        if(transferId==null)return;
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(!player.isOnline()||!departing.containsKey(player.getUniqueId())
                    ||!transferId.equals(departing.get(player.getUniqueId())))return;
            track(CompletableFuture.runAsync(()->{try{storage.markRecoveryRequired(transferId,
                    "source remained online after proxy transfer request");}catch(Exception error){log("Could not preserve timed-out departure",error);}}));
            routeFallback(player,"proxy transfer timeout");
        },200L);
    }

    private boolean frozen(Player player){return player!=null&&(preparingTransfers.contains(player.getUniqueId())
            ||departing.containsKey(player.getUniqueId())||incomingTransfers.containsKey(player.getUniqueId())
            ||evacuationDepartures.contains(player.getUniqueId()));}
    private void cancelFrozen(Player player,Cancellable event){if(frozen(player))event.setCancelled(true);}

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenInventoryClick(InventoryClickEvent event){if(event.getWhoClicked() instanceof Player player)cancelFrozen(player,event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenInventoryDrag(InventoryDragEvent event){if(event.getWhoClicked() instanceof Player player)cancelFrozen(player,event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenInventoryOpen(InventoryOpenEvent event){if(event.getPlayer() instanceof Player player)cancelFrozen(player,event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenInteract(PlayerInteractEvent event){cancelFrozen(event.getPlayer(),event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenDrop(PlayerDropItemEvent event){cancelFrozen(event.getPlayer(),event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenSwap(PlayerSwapHandItemsEvent event){cancelFrozen(event.getPlayer(),event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenConsume(PlayerItemConsumeEvent event){cancelFrozen(event.getPlayer(),event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenBreak(BlockBreakEvent event){cancelFrozen(event.getPlayer(),event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenPlace(BlockPlaceEvent event){cancelFrozen(event.getPlayer(),event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenPickup(EntityPickupItemEvent event){if(event.getEntity() instanceof Player player)cancelFrozen(player,event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenDamage(EntityDamageEvent event){if(event.getEntity() instanceof Player player)cancelFrozen(player,event);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenFood(FoodLevelChangeEvent event){if(event.getEntity() instanceof Player player)cancelFrozen(player,event);}
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onFrozenExperience(PlayerExpChangeEvent event){if(frozen(event.getPlayer()))event.setAmount(0);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)
    public void onFrozenCommand(PlayerCommandPreprocessEvent event){
        if(!frozen(event.getPlayer()))return;event.setCancelled(true);
        event.getPlayer().sendMessage(messages.get(event.getPlayer(),"network.transfer-busy"));
    }

    public void shutdown() {
        if (tickTask != null) tickTask.cancel();
        if (enabled) {
            try {
                ShardState finalState = state == ShardState.RESTARTING ? ShardState.RESTARTING : ShardState.OFFLINE;
                storage.heartbeat(shardId, role, finalState, maxPlayers, 0, restartEta, "shutdown");
            } catch (Exception error) { log("Could not publish clean shard shutdown", error); }
        }
        try { CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS); }
        catch (Exception error) { log("Timed out waiting for network writes", error); }
        preparingTransfers.clear();
    }

    private void track(CompletableFuture<?> future) {
        pending.add(future);
        future.whenComplete((ignored, error) -> pending.remove(future));
    }

    private long seconds(String path, long fallback, long minimum) {
        return Math.max(minimum, plugin.getConfig().getLong(path, fallback));
    }
    private static int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String normalize(String raw) { return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT); }
    private static String safeReason(String raw) {
        String value = normalize(raw).replaceAll("[^a-z0-9_-]", "");
        return value.isBlank() ? "teleport" : value.substring(0, Math.min(64, value.length()));
    }
    private static long safeAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }
    @FunctionalInterface
    public interface DestinationRepair {
        void repair(Player player, NetworkLocation invalid, java.util.function.Consumer<NetworkLocation> result);
    }
    private record JoinState(NetworkStorage.Handoff incoming, NetworkStorage.Handoff unresolved, boolean failed) {}
    private record EvacuationPlan(String targetShard,boolean snapshotPreserved){}
    private void log(String message, Exception error) { plugin.getLogger().log(Level.WARNING, message, error); }
}
