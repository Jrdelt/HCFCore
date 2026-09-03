package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passively records where every player last teleported FROM via a real
 * ender pearl (MONITOR + ignoreCancelled, so Fake Pearl's cancelled
 * teleports are never recorded), independent of which item -- or none --
 * is in their hand at the time. The ability item itself just recalls to
 * that recorded spot.
 */
public final class TimeWarpPearlListener implements Listener {

    private static final String ABILITY_ID = "time-warp-pearl";

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;
    private final Map<UUID, Location> lastPearlOrigin = new ConcurrentHashMap<>();

    public TimeWarpPearlListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                                  Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRealPearl(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            lastPearlOrigin.put(event.getPlayer().getUniqueId(), event.getFrom());
        }
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

        Location origin = lastPearlOrigin.get(player.getUniqueId());
        if (origin == null) {
            player.sendMessage(messages.get(player, "ability.timewarp-none"));
            return;
        }

        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability)) {
            return;
        }

        lastPearlOrigin.remove(player.getUniqueId());
        player.teleport(origin);
        player.sendMessage(messages.get(player, "ability.timewarp-warped"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPearlOrigin.remove(event.getPlayer().getUniqueId());
    }
}
