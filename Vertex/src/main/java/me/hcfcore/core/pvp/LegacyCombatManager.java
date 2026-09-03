package me.hcfcore.core.pvp;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class LegacyCombatManager implements Listener {

    private static final double VANILLA_ATTACK_SPEED = 4.0;

    private final Plugin plugin;
    private boolean enabled;
    private boolean disableSweepingAttacks;
    private double attackSpeed;
    private Set<String> worlds;

    public LegacyCombatManager(Plugin plugin) {
        this.plugin = plugin;
        reconfigure();
    }

    public void start() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    public void reconfigure() {
        enabled = plugin.getConfig().getBoolean("pvp.legacy-combat.enabled", true);
        disableSweepingAttacks = plugin.getConfig().getBoolean("pvp.legacy-combat.disable-sweeping-attacks", true);
        attackSpeed = Math.max(VANILLA_ATTACK_SPEED,
                plugin.getConfig().getDouble("pvp.legacy-combat.attack-speed", 1024.0));
        List<String> configuredWorlds = plugin.getConfig().getStringList("pvp.legacy-combat.worlds");
        worlds = new HashSet<>();
        for (String world : configuredWorlds) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                && disableSweepingAttacks
                && event.getEntity().getWorld() != null
                && isEnabledIn(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    private void apply(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attribute == null) {
            return;
        }
        attribute.setBaseValue(isEnabledIn(player.getWorld()) ? attackSpeed : VANILLA_ATTACK_SPEED);
    }

    private boolean isEnabledIn(World world) {
        return world != null && enabled
                && (worlds.isEmpty() || worlds.contains(world.getName().toLowerCase(Locale.ROOT)));
    }
}