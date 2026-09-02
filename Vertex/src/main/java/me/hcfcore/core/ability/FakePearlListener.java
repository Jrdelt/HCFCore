package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Launches a real EnderPearl (so the arc/sound look identical to a real
 * throw) but cancels the teleport it would normally trigger on landing.
 * Correlated per-player rather than per-projectile: a fake pearl is
 * "pending" for a player from the moment it's thrown until the next
 * ENDER_PEARL teleport fires for them, which is simple and reliable given
 * the ability's own cooldown rules out realistic overlap with a second,
 * real pearl throw in the same flight window.
 */
public final class FakePearlListener implements Listener {

    private static final String ABILITY_ID = "fake-pearl";

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;
    private final Set<UUID> pendingFakePearl = ConcurrentHashMap.newKeySet();

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

        player.launchProjectile(EnderPearl.class);
        pendingFakePearl.add(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        if (pendingFakePearl.remove(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
