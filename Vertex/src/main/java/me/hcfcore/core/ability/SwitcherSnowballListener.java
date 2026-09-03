package me.hcfcore.core.ability;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanilla right-click-throw already works, so this only needs to tag the
 * resulting Snowball at launch (where cooldown/region checks also happen,
 * cancelling the launch on failure) and act on it when it lands.
 */
public final class SwitcherSnowballListener implements Listener {

    private static final String ABILITY_ID = "switcher-snowball";

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;
    private final Map<UUID, UUID> trackedSnowballs = new ConcurrentHashMap<>();

    public SwitcherSnowballListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                                     Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) {
            return;
        }
        if (!(snowball.getShooter() instanceof Player player)) {
            return;
        }
        if (!AbilityGate.isAbility(plugin, player.getInventory().getItemInMainHand(), ABILITY_ID)) {
            return;
        }

        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null) {
            return;
        }
        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability, false)) {
            event.setCancelled(true);
            return;
        }

        trackedSnowballs.put(snowball.getUniqueId(), player.getUniqueId());
    }

    @EventHandler
    public void onHit(ProjectileHitEvent event) {
        UUID throwerId = trackedSnowballs.remove(event.getEntity().getUniqueId());
        if (throwerId == null) {
            return;
        }
        if (!(event.getHitEntity() instanceof Player target)) {
            return;
        }
        Player thrower = Bukkit.getPlayer(throwerId);
        if (thrower == null || thrower.equals(target) || FactionsHook.isSameFaction(thrower, target)) {
            return;
        }

        Location throwerLocation = thrower.getLocation();
        Location targetLocation = target.getLocation();
        thrower.teleport(targetLocation);
        target.teleport(throwerLocation);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        trackedSnowballs.entrySet().removeIf(entry -> entry.getValue().equals(playerId));
    }
}
