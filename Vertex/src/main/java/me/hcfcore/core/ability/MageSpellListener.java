package me.hcfcore.core.ability;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.kit.ArmorClass;
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

import java.util.Set;

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
        if (ability == null || user == null) {
            return;
        }
        if (abilityManager.isOnGlobalCooldown(attacker.getUniqueId())) {
            long remaining = (abilityManager.globalCooldownRemainingMillis(attacker.getUniqueId()) + 999) / 1000;
            attacker.sendMessage(messages.get(attacker, "ability.on-global-cooldown", "seconds", String.valueOf(remaining)));
            return;
        }
        if (abilityManager.isOnCooldown(user, ability)) {
            long remaining = (abilityManager.remainingCooldownMillis(user, ability) + 999) / 1000;
            attacker.sendMessage(messages.get(attacker, "ability.on-cooldown", "seconds", String.valueOf(remaining)));
            return;
        }
        Set<String> disabledClaims = Set.copyOf(plugin.getConfig().getStringList("abilities.disabled-claim-names"));
        if (FactionsHook.isDisabledClaim(attacker.getLocation(), disabledClaims)) {
            attacker.sendMessage(messages.get(attacker, "ability.region-blocked"));
            return;
        }
        PotionEffectType type = PotionEffectType.getByName(ability.getString("effect-type", ""));
        if (type == null) {
            return;
        }
        abilityManager.markGlobalCooldown(attacker.getUniqueId());
        abilityManager.startCooldown(attacker, user, ability);
        int durationSeconds = Math.max(1, ability.getInt("effect-duration-seconds", 5));
        int amplifier = Math.max(0, ability.getInt("effect-amplifier", 0));
        // A mage actually wearing the kit's own mixed gold/chainmail set
        // gets double duration and double effect level (Level I -> II,
        // II -> IV, ...) -- same "rewards playing in your own class"
        // treatment Portable Bard already gets for its cooldown.
        if (ArmorClass.isMage(attacker)) {
            durationSeconds *= 2;
            amplifier = amplifier * 2 + 1;
        }
        victim.addPotionEffect(new PotionEffect(type, durationSeconds * 20, amplifier, false, true));
        consume(attacker);
        attacker.sendMessage(messages.get(attacker, "ability.mage-spell-cast"));
    }

    private String findSpell(ItemStack item) {
        for (String id : new String[]{"mage-wither", "mage-slowness", "mage-poison"}) {
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
