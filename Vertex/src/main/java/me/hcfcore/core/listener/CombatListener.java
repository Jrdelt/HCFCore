package me.hcfcore.core.listener;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.pvp.CombatManager;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

public final class CombatListener implements Listener {

    private final Plugin plugin;
    private final CombatManager combatManager;
    private final Messages messages;
    private volatile List<String> blockedCommands = List.of();

    public CombatListener(Plugin plugin, CombatManager combatManager, Messages messages) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.messages = messages;
        reloadConfig();
    }

    public void reloadConfig() {
        blockedCommands = List.copyOf(plugin.getConfig().getStringList("pvp.blocked-commands-in-combat"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }

        combatManager.tag(attacker, victim);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            combatManager.recordClick(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (event.getEntity().getKiller() instanceof Player killer) {
            combatManager.applyPostKillCooldown(killer.getUniqueId());
        }
        // clearOwnTag, not clear() -- clear() would cascade and wipe out
        // the killer's post-kill cooldown just set above, since the killer
        // was the victim's mutual opponent.
        combatManager.clearOwnTag(event.getEntity().getUniqueId());
    }

    /**
     * Blocks commands like /kit or /f home while tagged, so a player can't
     * duck out of a fight into a kit swap or a faction teleport. Matches by
     * leading tokens: a one-word entry ("kit") blocks that whole command
     * tree, a multi-word entry ("f home") blocks only that exact
     * subcommand, leaving the rest of "/f" untouched.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (blockedCommands.isEmpty() || !combatManager.isTagged(event.getPlayer().getUniqueId())) {
            return;
        }
        String[] typed = event.getMessage().substring(1).toLowerCase(Locale.ROOT).split("\\s+");
        for (String blocked : blockedCommands) {
            String[] blockedTokens = blocked.toLowerCase(Locale.ROOT).trim().split("\\s+");
            if (matches(typed, blockedTokens)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(messages.get(event.getPlayer(), "combat.command-blocked"));
                return;
            }
        }
    }

    private static boolean matches(String[] typed, String[] blockedTokens) {
        if (typed.length < blockedTokens.length) {
            return false;
        }
        for (int i = 0; i < blockedTokens.length; i++) {
            if (!typed[i].equals(blockedTokens[i])) {
                return false;
            }
        }
        return true;
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
