package me.vertex.core.teleport;

import me.vertex.core.lang.Messages;
import me.vertex.core.network.NetworkLocation;
import me.vertex.core.network.NetworkManager;
import me.vertex.core.network.NetworkStorage;
import me.vertex.core.pvp.CombatManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** One authoritative movement/damage/combat-aware countdown for every Vertex teleport. */
public final class TeleportManager implements Listener {
    public record Target(NetworkLocation location, long revision, String displayName) { }

    private final Plugin plugin;
    private final NetworkManager network;
    private final CombatManager combat;
    private final Messages messages;
    private final Map<UUID, Pending> active = new ConcurrentHashMap<>();
    private final Set<UUID> preparing = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public TeleportManager(Plugin plugin, NetworkManager network, CombatManager combat, Messages messages) {
        this.plugin = plugin; this.network = network; this.combat = combat; this.messages = messages;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        active.clear(); preparing.clear();
    }

    public boolean request(Player player, String type, Supplier<Target> resolver, int seconds,
            long cooldownMillis, boolean allowRestartQueue) {
        return request(player, type, resolver, seconds, cooldownMillis, allowRestartQueue,
                ignored -> true, () -> { });
    }

    public boolean request(Player player, String type, Supplier<Target> resolver, int seconds,
            long cooldownMillis, boolean allowRestartQueue, Predicate<Target> finalValidator,
            Runnable invalidHandler) {
        UUID uuid = player.getUniqueId();
        if (active.containsKey(uuid) || !preparing.add(uuid)) {
            player.sendMessage(messages.get(player, "teleport.already-active"));
            return false;
        }
        if (blockedByCombat(player)) {
            preparing.remove(uuid);
            player.sendMessage(messages.get(player, "teleport.combat-blocked"));
            return false;
        }
        Target initial = resolver.get();
        if (initial == null || initial.location() == null) {
            preparing.remove(uuid);
            player.sendMessage(messages.get(player, "teleport.unavailable"));
            return false;
        }
        NetworkManager.TransferStatus unavailable = network.destinationStatus(initial.location());
        if (blocksStart(unavailable, allowRestartQueue)) {
            preparing.remove(uuid);
            sendDestinationFailure(player, unavailable, initial.location().shardId());
            return false;
        }
        network.cooldown(uuid, type).thenAccept(availableAt -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) { preparing.remove(uuid); return; }
            long remaining = Math.max(0L, availableAt - System.currentTimeMillis());
            if (!player.hasPermission("vertex.teleport.bypass") && remaining > 0L) {
                preparing.remove(uuid);
                player.sendMessage(messages.get(player, "teleport.cooldown", "time",
                        me.vertex.core.grace.DurationParser.format((remaining + 999L) / 1_000L)));
                return;
            }
            int duration = Math.max(0, seconds);
            long until = System.currentTimeMillis() + duration * 1_000L;
            active.put(uuid, new Pending(type, initial, resolver, player.getLocation().clone(), until,
                    duration, cooldownMillis, allowRestartQueue,
                    finalValidator == null ? ignored -> true : finalValidator,
                    invalidHandler == null ? () -> { } : invalidHandler));
            preparing.remove(uuid);
            if (duration == 0) complete(player, active.get(uuid));
            else player.sendMessage(messages.get(player, "teleport.countdown", "seconds", String.valueOf(duration),
                    "destination", initial.displayName()));
        }));
        return true;
    }

    public boolean hasActive(UUID player) { return active.containsKey(player) || preparing.contains(player); }
    public boolean canStart(Player player) {
        return player != null && !hasActive(player.getUniqueId()) && !blockedByCombat(player);
    }

    public void cancel(Player player, String key) {
        if (active.remove(player.getUniqueId()) != null) player.sendMessage(messages.get(player, key));
        preparing.remove(player.getUniqueId());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> entry : Map.copyOf(active).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Pending pending = entry.getValue();
            if (player == null || !player.isOnline()) { active.remove(entry.getKey(), pending); continue; }
            if (blockedByCombat(player)) { cancel(player, "teleport.cancelled-combat"); continue; }
            Target current = pending.resolver().get();
            if (!sameTarget(pending.initial(), current)) { cancel(player, "teleport.cancelled-changed"); continue; }
            NetworkManager.TransferStatus unavailable = network.destinationStatus(current.location());
            if (blocksStart(unavailable, pending.allowRestartQueue())) {
                active.remove(player.getUniqueId(), pending);
                sendDestinationFailure(player, unavailable, current.location().shardId());
                continue;
            }
            long remaining = Math.max(0L, (pending.untilMillis() - now + 999L) / 1_000L);
            if (remaining == 0L) { complete(player, pending); continue; }
            if (remaining < pending.lastAnnouncedSecond()) {
                active.put(entry.getKey(), pending.withLastAnnounced((int) remaining));
                player.sendMessage(messages.get(player, "teleport.countdown", "seconds", String.valueOf(remaining),
                        "destination", current.displayName()));
            }
        }
    }

    private void complete(Player player, Pending pending) {
        if (!active.remove(player.getUniqueId(), pending)) return;
        Target current = pending.resolver().get();
        if (!sameTarget(pending.initial(), current)) {
            player.sendMessage(messages.get(player, "teleport.cancelled-changed"));
            return;
        }
        if (!pending.finalValidator().test(current)) {
            pending.invalidHandler().run();
            return;
        }
        network.transfer(player, current.location(), pending.type(), pending.allowRestartQueue()).thenAccept(status ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (status == NetworkManager.TransferStatus.LOCAL_COMPLETE
                            || status == NetworkManager.TransferStatus.STARTED) {
                        long available = safeAdd(System.currentTimeMillis(), Math.max(0L, pending.cooldownMillis()));
                        if (pending.cooldownMillis() > 0L) network.saveCooldown(player.getUniqueId(), pending.type(), available);
                        if (status == NetworkManager.TransferStatus.LOCAL_COMPLETE && player.isOnline()) {
                            player.sendMessage(messages.get(player, "teleport.complete", "destination", current.displayName()));
                        }
                    }
                }));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending pending = active.get(event.getPlayer().getUniqueId());
        if (pending == null || event.getTo() == null) return;
        Location from = pending.origin(); Location to = event.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 0.01D) {
            cancel(event.getPlayer(), "teleport.cancelled-movement");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && active.containsKey(player.getUniqueId())) {
            cancel(player, "teleport.cancelled-damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
        preparing.remove(event.getPlayer().getUniqueId());
    }

    private boolean blockedByCombat(Player player) {
        return combat != null && combat.isTagged(player.getUniqueId())
                && !player.hasPermission("vertex.teleport.combat-bypass");
    }

    private void sendDestinationFailure(Player player, NetworkManager.TransferStatus status, String shard) {
        String key = switch (status) {
            case FULL -> "network.shard-full";
            case RESTARTING -> "network.shard-restarting";
            case DRAINING -> "network.shard-draining";
            case CRASH_RECOVERY -> "network.shard-recovery";
            case INVALID_DESTINATION -> "network.destination-invalid";
            default -> "network.shard-offline";
        };
        NetworkStorage.ShardRow target = network.shard(shard);
        long seconds = target == null || target.restartEta() <= 0L ? 0L
                : Math.max(0L, (target.restartEta() - System.currentTimeMillis() + 999L) / 1_000L);
        player.sendMessage(messages.get(player, key, "shard", shard,
                "time", seconds <= 0L ? messages.getRaw(player, "network.restart-time-unknown")
                        : me.vertex.core.grace.DurationParser.format(seconds)));
    }

    private static boolean blocksStart(NetworkManager.TransferStatus status, boolean allowRestartQueue) {
        return status != null && !(allowRestartQueue
                && (status == NetworkManager.TransferStatus.DRAINING
                || status == NetworkManager.TransferStatus.RESTARTING));
    }

    private static boolean sameTarget(Target first, Target second) {
        return first != null && second != null && first.revision() == second.revision()
                && first.location().equals(second.location());
    }

    private static long safeAdd(long left, long right) {
        try { return Math.addExact(left, right); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE; }
    }

    private record Pending(String type, Target initial, Supplier<Target> resolver, Location origin,
                           long untilMillis, int lastAnnouncedSecond, long cooldownMillis,
                           boolean allowRestartQueue, Predicate<Target> finalValidator,
                           Runnable invalidHandler) {
        Pending withLastAnnounced(int value) {
            return new Pending(type, initial, resolver, origin, untilMillis, value, cooldownMillis,
                    allowRestartQueue, finalValidator, invalidHandler);
        }
    }
}
