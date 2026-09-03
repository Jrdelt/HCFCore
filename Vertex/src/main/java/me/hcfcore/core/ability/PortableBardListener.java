package me.hcfcore.core.ability;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class PortableBardListener implements Listener {

    private static final String ABILITY_ID = "portable-bard";

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;

    public PortableBardListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                                 Messages messages) {
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

        PortableBardMenu.open(player, messages);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PortableBardMenu.Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PortableBardMenu.Holder)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }

        PotionEffectType effectType;
        int amplifier;
        switch (clicked.getType()) {
            case SUGAR -> {
                effectType = PotionEffectType.SPEED;
                amplifier = 1;
            }
            case BLAZE_POWDER -> {
                effectType = PotionEffectType.STRENGTH;
                amplifier = 0;
            }
            case IRON_INGOT -> {
                effectType = PotionEffectType.RESISTANCE;
                amplifier = 0;
            }
            case GHAST_TEAR -> {
                effectType = PotionEffectType.REGENERATION;
                amplifier = 0;
            }
            case FEATHER -> {
                effectType = PotionEffectType.JUMP_BOOST;
                amplifier = 1;
            }
            default -> {
                return;
            }
        }

        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null || !AbilityGate.isAbility(plugin, player.getInventory().getItemInMainHand(), ABILITY_ID)
            || !AbilityGate.checkAndStart(plugin, abilityManager, userManager,
                messages, player, ability)) {
            return;
        }
        int buffSeconds = ability == null ? 6 : ability.getInt("buff-seconds", 6);
        PotionEffect effect = new PotionEffect(effectType, buffSeconds * 20, amplifier);

        for (Player member : FactionsHook.getOnlineFactionMembers(player)) {
            member.addPotionEffect(effect);
        }
        player.closeInventory();
    }
}
