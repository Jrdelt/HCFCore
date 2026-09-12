package me.vertex.core.teleport;

import me.vertex.core.factions.FactionService;
import me.vertex.core.mine.MineManager;
import me.vertex.core.network.NetworkManager;
import me.vertex.core.zone.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes deaths in public-danger areas to the configured shared Spawn.
 *
 * <p>Faction and SafeZone claims deliberately retain their normal respawn
 * behavior.  Wilderness, WarZone, any configured mine, Haven, and Riftlands
 * always return a player to the primary network Spawn.
 */
public final class DeathSpawnListener implements Listener {
    private final Plugin plugin;
    private final GlobalLocationManager locations;
    private final NetworkManager network;
    private final MineManager mines;
    private final ZoneManager zones;
    private final FactionService factions;
    private final Map<UUID, Location> deaths = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> pendingNetworkSpawn = ConcurrentHashMap.newKeySet();

    public DeathSpawnListener(Plugin plugin, GlobalLocationManager locations, NetworkManager network,
            MineManager mines, ZoneManager zones, FactionService factions) {
        this.plugin = plugin;
        this.locations = locations;
        this.network = network;
        this.mines = mines;
        this.zones = zones;
        this.factions = factions;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        deaths.put(event.getEntity().getUniqueId(), event.getEntity().getLocation().clone());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Location death = deaths.remove(player.getUniqueId());
        if (!shouldRouteToSpawn(death)) {
            return;
        }

        TeleportManager.Target target = locations.spawnTarget(player);
        if (target == null) {
            plugin.getLogger().warning("Could not route " + player.getName()
                    + " to Spawn after death: shared Spawn is not configured.");
            return;
        }

        Location local = target.location().resolveLocal(network.shardId());
        if (local != null) {
            event.setRespawnLocation(local);
            return;
        }

        // Bukkit requires a local respawn location before the player can be
        // handed to another shard.  Use a neutral world spawn for one tick,
        // then make the durable network transfer after the respawn completes.
        World fallbackWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (fallbackWorld == null || !pendingNetworkSpawn.add(player.getUniqueId())) {
            return;
        }
        event.setRespawnLocation(fallbackWorld.getSpawnLocation());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingNetworkSpawn.remove(player.getUniqueId());
            if (player.isOnline()) {
                network.transfer(player, target.location(), "death-spawn", false);
            }
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        deaths.remove(player);
        pendingNetworkSpawn.remove(player);
    }

    private boolean shouldRouteToSpawn(Location location) {
        if (location == null) {
            return false;
        }
        if (zones.isInAnyZone(location) || mines.regionAt(location) != null) {
            return true;
        }
        String owner = factions.factionTagAt(location);
        return owner == null || owner.equalsIgnoreCase(factions.systemTag("warzone"));
    }
}
