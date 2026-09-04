package me.hcfcore.core.ability;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Set;


public final class RogueBackstabListener implements Listener {

    private static final String ABILITY_ID = "rogue-backstab";
    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;

    public RogueBackstabListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)
                || attacker.equals(victim)
                || !AbilityGate.isAbility(plugin, attacker.getInventory().getItemInMainHand(), ABILITY_ID)) {
            return;
        }
        // Prevent backstab from affecting teammates/allies
        if (FactionsHook.isSameFaction(attacker, victim)) {
            return;
        }
        Ability ability = abilityManager.get(ABILITY_ID);
        User user = userManager.get(attacker.getUniqueId());
        Set<String> disabledClaims = Set.copyOf(plugin.getConfig().getStringList("abilities.disabled-claim-names"));
        if (ability == null || user == null
                || abilityManager.isOnGlobalCooldown(attacker.getUniqueId())
                || abilityManager.isOnCooldown(user, ability)
                || !isBehind(attacker, victim)
                || FactionsHook.isDisabledClaim(attacker.getLocation(), disabledClaims)) {
            return;
        }

        abilityManager.markGlobalCooldown(attacker.getUniqueId());
        abilityManager.startCooldown(attacker, user, ability);
        consume(attacker);
        event.setDamage(Math.max(0.0, ability.getDouble("damage", 6.0)));
        attacker.sendMessage(MessageFormatter.deserialize("&e&lABILITES &7> <gold>Backstab landed for 3 hearts."));
        victim.sendMessage(MessageFormatter.deserialize("&e&lABILITES &7> <red>You were backstabbed!"));
    }

    private static boolean isBehind(Player attacker, Player victim) {
        org.bukkit.util.Vector victimFacing = victim.getLocation().getDirection().setY(0).normalize();
        org.bukkit.util.Vector victimToAttacker = attacker.getLocation().toVector()
                .subtract(victim.getLocation().toVector()).setY(0).normalize();
        return victimFacing.lengthSquared() > 0 && victimToAttacker.lengthSquared() > 0
                && victimFacing.dot(victimToAttacker) < -0.35;
    }

    private static void consume(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }
}
