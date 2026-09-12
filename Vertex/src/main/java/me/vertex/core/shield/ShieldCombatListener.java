package me.vertex.core.shield;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Optionally disables PvP in any claim owned by a Shielded faction.
 */
public final class ShieldCombatListener implements Listener {

    private final ShieldManager shield;
    private final Messages messages;

    public ShieldCombatListener(ShieldManager shield, Messages messages) {
        this.shield = shield;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttackingPlayer(event);
        if (attacker == null) {
            return;
        }
        if (!shield.isPvpProtected(victim.getLocation())) {
            return;
        }
        event.setCancelled(true);
        attacker.sendMessage(messages.get(attacker, "shield.combat-blocked"));
    }

    private Player resolveAttackingPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
