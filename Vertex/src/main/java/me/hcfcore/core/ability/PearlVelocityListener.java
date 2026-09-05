package me.hcfcore.core.ability;

import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.Plugin;

/**
 * Gives a thrown ender pearl extra velocity, but only when the thrower is
 * actively falling or aiming downward -- a flat or upward throw is left
 * alone so this can't be used to snipe someone from across the map.
 */
public final class PearlVelocityListener implements Listener {

    private final Plugin plugin;

    public PearlVelocityListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player player)) {
            return;
        }
        if (FakePearlListener.isThrowingFakePearl(player.getUniqueId())) {
            return;
        }
        boolean falling = player.getVelocity().getY() < 0;
        boolean lookingDown = player.getLocation().getPitch() > 0;
        if (!falling && !lookingDown) {
            return;
        }
        double multiplier = Math.max(1.0, plugin.getConfig().getDouble("pvp.pearl-velocity-multiplier", 1.35));
        pearl.setVelocity(pearl.getVelocity().multiply(multiplier));
    }
}
