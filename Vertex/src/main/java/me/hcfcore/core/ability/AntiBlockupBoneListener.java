package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import me.hcfcore.core.worldguard.WorldGuardHook;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Hit-triggered, so it can't message the player on every miss the way a
 * direct right-click ability can -- checks are silent until the hit that
 * actually reaches the threshold.
 */
public final class AntiBlockupBoneListener implements Listener {

    private static final String ABILITY_ID = "anti-blockup-bone";

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;
    private final BlockupTracker tracker = new BlockupTracker();

    public AntiBlockupBoneListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                                    Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)
                || !AbilityGate.isAbility(plugin, attacker.getInventory().getItemInMainHand(), ABILITY_ID)) {
            return;
        }

        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null) {
            return;
        }

        if (abilityManager.isOnGlobalCooldown(attacker.getUniqueId())) {
            return;
        }
        User user = userManager.get(attacker.getUniqueId());
        if (user == null && userManager.hasFailedLoad(attacker.getUniqueId())) {
            return;
        }
        if (user != null && abilityManager.isOnCooldown(user, ability)) {
            return;
        }
        Set<String> disabledRegions = Set.copyOf(plugin.getConfig().getStringList("abilities.disabled-regions"));
        if (WorldGuardHook.isInDisabledRegion(attacker, disabledRegions)) {
            return;
        }

        int hitsRequired = Math.max(1, ability.getInt("hits-required", 3));
        long denySeconds = Math.max(1, ability.getInt("deny-seconds", 15));

        if (!tracker.recordHit(victim.getUniqueId(), hitsRequired, denySeconds)) {
            return;
        }

        abilityManager.markGlobalCooldown(attacker.getUniqueId());
        if (user != null) {
            abilityManager.startCooldown(attacker, user, ability);
        }
        ItemStack item = attacker.getInventory().getItemInMainHand();
        if (item.getAmount() <= 1) {
            attacker.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
        attacker.sendMessage(messages.get(attacker, "ability.bone-attacker",
                "player", victim.getName(), "seconds", String.valueOf(denySeconds)));
        victim.sendMessage(messages.get(victim, "ability.bone-victim", "seconds", String.valueOf(denySeconds)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (tracker.isDenied(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(messages.get(event.getPlayer(), "ability.bone-place-denied"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tracker.forget(event.getPlayer().getUniqueId());
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
