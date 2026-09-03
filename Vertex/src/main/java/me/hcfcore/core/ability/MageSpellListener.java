package me.hcfcore.core.ability;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class MageSpellListener implements Listener {

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;

    public MageSpellListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                              Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSlownessSpellPlace(BlockPlaceEvent event) {
        if (AbilityGate.isAbility(plugin, event.getItemInHand(), "mage-slowness")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)) {
            return;
        }
        // Prevent mage spells from affecting teammates/allies
        if (FactionsHook.isSameFaction(attacker, victim)) {
            return;
        }
        ItemStack item = attacker.getInventory().getItemInMainHand();
        String abilityId = findSpell(item);
        if (abilityId == null) {
            return;
        }
        Ability ability = abilityManager.get(abilityId);
        User user = userManager.get(attacker.getUniqueId());
        if (ability == null || user == null
                || abilityManager.isOnGlobalCooldown(attacker.getUniqueId())
                || abilityManager.isOnCooldown(user, ability)) {
            return;
        }
        PotionEffectType type = PotionEffectType.getByName(ability.getString("effect-type", ""));
        if (type == null) {
            return;
        }
        abilityManager.markGlobalCooldown(attacker.getUniqueId());
        abilityManager.startCooldown(attacker, user, ability);
        victim.addPotionEffect(new PotionEffect(type,
                Math.max(1, ability.getInt("effect-duration-seconds", 5)) * 20,
                Math.max(0, ability.getInt("effect-amplifier", 0)), false, true));
        consume(attacker);
    }

    private String findSpell(ItemStack item) {
        for (String id : new String[]{"mage-wither", "mage-slowness", "mage-weakness", "mage-poison"}) {
            if (AbilityGate.isAbility(plugin, item, id)) {
                return id;
            }
        }
        return null;
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
