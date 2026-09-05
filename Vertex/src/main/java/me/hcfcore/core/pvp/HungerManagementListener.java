package me.hcfcore.core.pvp;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Freezes hunger entirely in configured worlds (e.g. spawn) by cancelling
 * every food-level change there -- players never need to eat while inside
 * them, regardless of whether the change would raise or lower the level.
 */
public final class HungerManagementListener implements Listener {

    private final Plugin plugin;
    private volatile Set<String> disabledWorlds = Set.of();

    public HungerManagementListener(Plugin plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        List<String> configured = plugin.getConfig().getStringList("pvp.disable-hunger-worlds");
        Set<String> worlds = new HashSet<>();
        for (String world : configured) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }
        disabledWorlds = worlds;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        World world = player.getWorld();
        if (disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT))) {
            event.setCancelled(true);
        }
    }
}
