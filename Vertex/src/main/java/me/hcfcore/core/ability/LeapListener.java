package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public final class LeapListener implements Listener {

    private static final String ABILITY_ID = "leap";

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;

    public LeapListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager, Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!AbilityGate.isAbility(plugin, event.getItem(), ABILITY_ID)) {
            return;
        }

        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability)) {
            return;
        }

        double forwardMultiplier = ability.getDouble("forward-multiplier", 1.4);
        double yMultiplier = ability.getDouble("y-multiplier", 0.6);

        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Vector boost = direction.multiply(forwardMultiplier).setY(direction.getY() * forwardMultiplier + yMultiplier);
        player.setVelocity(boost);
    }
}
