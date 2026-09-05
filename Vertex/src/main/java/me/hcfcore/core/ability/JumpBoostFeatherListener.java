package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class JumpBoostFeatherListener implements Listener {

    private static final String ABILITY_ID = "jump-boost-feather";
    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;

    public JumpBoostFeatherListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                                     Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !AbilityGate.isAbility(plugin, event.getItem(), ABILITY_ID)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null || !AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability)) {
            return;
        }
        int durationSeconds = Math.max(1, ability.getInt("jump-duration-seconds", 8));
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, durationSeconds * 20, 4, false, false));
    }
}
