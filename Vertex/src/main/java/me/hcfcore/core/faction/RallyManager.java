package me.hcfcore.core.faction;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RallyManager {
    private final Plugin plugin;
    private final Messages messages;
    private final Map<Integer, RallyPoint> rallyPoints = new HashMap<>();
    private final Map<UUID, Component> bossBarCache = new HashMap<>();

    public RallyManager(Plugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        startUpdateTask();
    }

    public void setRally(int factionId, org.bukkit.Location location) {
        rallyPoints.put(factionId, new RallyPoint(factionId, location.clone(), System.currentTimeMillis()));
        bossBarCache.clear();
    }

    public void clearRally(int factionId) {
        rallyPoints.remove(factionId);
        bossBarCache.clear();
    }

    public RallyPoint getRally(int factionId) {
        return rallyPoints.get(factionId);
    }

    private void startUpdateTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateRallyDisplay, 0L, 5L);
    }

    private void updateRallyDisplay() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int factionId = FactionsHook.getFactionId(player);
            if (factionId == FactionsHook.NO_FACTION) {
                continue;
            }

            RallyPoint rally = rallyPoints.get(factionId);
            if (rally == null) {
                continue;
            }

            updatePlayerRallyDisplay(player, rally);
        }
    }

    private void updatePlayerRallyDisplay(Player player, RallyPoint rally) {
        double distance = rally.getDistance(player);
        int distanceInt = (int) Math.round(distance);

        Component arrow = Component.text("➜ ")
                .color(NamedTextColor.GREEN);

        Component text = arrow.append(Component.text("FACTION-RALLY: " + distanceInt + " Blocks Away!")
                .color(NamedTextColor.GREEN));

        player.sendActionBar(text);

        // Point compass towards rally
        player.setCompassTarget(rally.getLocation());
    }

    public void shutdown() {
        rallyPoints.clear();
        bossBarCache.clear();
    }
}
