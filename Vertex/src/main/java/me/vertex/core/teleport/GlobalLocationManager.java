package me.vertex.core.teleport;

import me.vertex.core.lang.Messages;
import me.vertex.core.network.NetworkLocation;
import me.vertex.core.network.NetworkManager;
import me.vertex.core.network.NetworkStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Shared Spawn and public Warp definitions, including shard identity and revisions. */
public final class GlobalLocationManager implements Listener {
    public static final String SPAWN_NAME = "primary";
    private final Plugin plugin;
    private final NetworkManager network;
    private final Messages messages;
    private final Map<String, NetworkStorage.LocationRow> warps = new ConcurrentHashMap<>();
    private volatile NetworkStorage.LocationRow spawn;

    public GlobalLocationManager(Plugin plugin, NetworkManager network, Messages messages) {
        this.plugin = plugin; this.network = network; this.messages = messages;
    }

    public void load() throws Exception {
        spawn = network.storage().location("spawn", SPAWN_NAME).orElse(null);
        warps.clear();
        for (NetworkStorage.LocationRow row : network.storage().locations("warp")) warps.put(row.name(), row);
    }

    public void refreshAsync() {
        CompletableFuture.runAsync(() -> {
            try { load(); }
            catch (Exception error) { plugin.getLogger().warning("Could not refresh shared locations: " + error.getMessage()); }
        });
    }

    public TeleportManager.Target spawnTarget(Player player) {
        return target(spawn, messages.getRaw(player, "spawn.display"));
    }
    public TeleportManager.Target warpTarget(String name) {
        String key = normalize(name);
        NetworkStorage.LocationRow row = warps.get(key);
        return target(row, row == null ? name : row.name());
    }
    public List<NetworkStorage.LocationRow> warps() {
        return warps.values().stream().sorted(java.util.Comparator.comparing(NetworkStorage.LocationRow::name,
                String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public CompletableFuture<Boolean> setSpawn(Location location) {
        return save("spawn", SPAWN_NAME, location, "Primary network spawn");
    }

    public CompletableFuture<Boolean> setWarp(String name, Location location, String description) {
        String safe = normalize(name);
        if (!safe.matches("[a-z0-9_-]{1,32}")) return CompletableFuture.completedFuture(false);
        return save("warp", safe, location, description == null ? "" : description);
    }

    public CompletableFuture<Boolean> deleteWarp(String name) {
        String safe = normalize(name);
        return CompletableFuture.supplyAsync(() -> {
            try {
                network.storage().deleteLocation("warp", safe);
                warps.remove(safe);
                network.publishInvalidation("global-locations", "warp-delete:" + safe);
                return true;
            } catch (Exception error) {
                plugin.getLogger().warning("Could not delete public warp: " + error.getMessage());
                return false;
            }
        });
    }

    private CompletableFuture<Boolean> save(String type, String name, Location location, String description) {
        if (location == null || location.getWorld() == null) return CompletableFuture.completedFuture(false);
        NetworkLocation target = NetworkLocation.local(network.shardId(), location);
        return CompletableFuture.supplyAsync(() -> {
            try {
                network.storage().saveLocation(type, name, target, description);
                NetworkStorage.LocationRow row = network.storage().location(type, name).orElseThrow();
                if (type.equals("spawn")) spawn = row; else warps.put(name, row);
                network.publishInvalidation("global-locations", type + ":" + name);
                return true;
            } catch (Exception error) {
                plugin.getLogger().warning("Could not save shared " + type + ": " + error.getMessage());
                return false;
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFirstJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;
        network.firstJoinSpawnAllowed(player.getUniqueId()).thenAccept(allowed -> {
            if (!allowed) return;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                TeleportManager.Target target = spawnTarget(player);
                if (player.isOnline() && target != null) {
                    NetworkManager.TransferStatus status = network.destinationStatus(target.location());
                    if (status == null) network.transfer(player, target.location(), "first-join-spawn", false)
                            .thenAccept(result -> {
                                if (result != NetworkManager.TransferStatus.STARTED
                                        && result != NetworkManager.TransferStatus.LOCAL_COMPLETE) {
                                    Bukkit.getScheduler().runTask(plugin,
                                            () -> network.routeToFallback(player));
                                }
                            });
                    else network.routeToFallback(player);
                } else if (player.isOnline()) {
                    player.sendMessage(messages.get(player, "spawn.not-set"));
                }
            }, 2L);
        });
    }

    private static TeleportManager.Target target(NetworkStorage.LocationRow row, String display) {
        return row == null ? null : new TeleportManager.Target(row.location(), row.revision(), display);
    }
    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
