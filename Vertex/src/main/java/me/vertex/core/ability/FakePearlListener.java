package me.vertex.core.ability;

import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Launches a real EnderPearl (so the arc/sound look identical to a real
 * throw) but cancels the teleport it would normally trigger on landing.
 * Correlated per-*projectile*, not just per-player: {@code trackedEntities}
 * marks specifically which EnderPearl entity is fake, and only once THAT
 * exact entity's own {@link ProjectileHitEvent} fires do we arm
 * {@code pendingTeleportCancel} for its shooter -- consumed by the very
 * next ENDER_PEARL teleport, which (same-tick, single-threaded NMS
 * processing) is guaranteed to be caused by that same hit. A cheaper
 * per-player-only correlation was tried first and thrown out: it could
 * cancel a genuine real pearl's teleport if one happened to land while an
 * earlier fake pearl's correlation window (armed at throw time, open for
 * seconds) was still open.
 */
public final class FakePearlListener implements Listener {

    private static final String ABILITY_ID = "fake-pearl";

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;
    private final Set<UUID> trackedEntities = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingTeleportCancel = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> THROWING_FAKE_PEARL = ConcurrentHashMap.newKeySet();

    public FakePearlListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager, Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!AbilityGate.isAbility(plugin, event.getItem(), ABILITY_ID)) {
            return;
        }

        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        THROWING_FAKE_PEARL.add(playerId);
        EnderPearl pearl = player.launchProjectile(EnderPearl.class);
        trackedEntities.add(pearl.getUniqueId());
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> THROWING_FAKE_PEARL.remove(playerId), 5L);
        // Safety-net only: the entity should always hit (and get untracked
        // via onPearlHit) well before this -- covers it being removed some
        // other way first (e.g. an unloaded chunk) without ever hitting.
        plugin.getServer().getScheduler().runTaskLater(plugin,
            () -> trackedEntities.remove(pearl.getUniqueId()), 200L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPearlHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl) || !trackedEntities.remove(pearl.getUniqueId())) {
            return;
        }
        if (pearl.getShooter() instanceof Player player) {
            pendingTeleportCancel.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        if (pendingTeleportCancel.remove(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pendingTeleportCancel.remove(playerId);
        THROWING_FAKE_PEARL.remove(playerId);
    }

    static boolean isThrowingFakePearl(UUID playerId) {
        return THROWING_FAKE_PEARL.contains(playerId);
    }

    public static void clearAll() {
        THROWING_FAKE_PEARL.clear();
    }
}
