package me.hcfcore.core.ability;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.kit.ArmorClass;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * Right-clicking the master Portable Bard item opens a menu; picking a buff
 * there hands the player that one buff item instead of applying it directly.
 * The master item has no cooldown of its own -- it's free to reopen at any
 * time -- since each buff item is its own standalone ability with its own
 * cooldown, only spent once the player actually right-clicks it to use it.
 */
public final class PortableBardListener implements Listener {

    private static final String ABILITY_ID = "portable-bard";
    private static final List<String> BUFF_IDS = List.of(
            "bard-buff-speed",
            "bard-buff-strength",
            "bard-buff-resistance",
            "bard-buff-regeneration",
            "bard-buff-jump-boost");

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
        ItemStack item = event.getItem();
        if (AbilityGate.isAbility(plugin, item, ABILITY_ID)) {
            onMasterItem(event);
            return;
        }
        for (String buffId : BUFF_IDS) {
            if (AbilityGate.isAbility(plugin, item, buffId)) {
                onBuffItem(event, buffId);
                return;
            }
        }
    }

    /**
     * No cooldown, no zone check -- opening the menu and receiving a buff
     * item doesn't do anything by itself; the buff item it hands out is its
     * own ability with its own cooldown/zone-check, applied when used.
     */
    private void onMasterItem(PlayerInteractEvent event) {
        if (abilityManager.get(ABILITY_ID) == null) {
            return;
        }
        event.setCancelled(true);
        PortableBardMenu.open(event.getPlayer(), messages);
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

        // getInventory() above is the view's *top* inventory, so it matches
        // for clicks in the player's own inventory too. Buffs are matched by
        // icon material, so without this a feather or sugar in the player's
        // own hotbar would fire a buff when clicked.
        if (event.getClickedInventory() == null
                || !(event.getClickedInventory().getHolder() instanceof PortableBardMenu.Holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        String buffId = buffIdForIcon(clicked.getType());
        if (buffId == null) {
            return;
        }
        Ability buffAbility = abilityManager.get(buffId);
        if (buffAbility == null) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        for (ItemStack dropped : inventory.addItem(abilityManager.createItem(buffAbility)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        }
        player.closeInventory();
    }

    private static String buffIdForIcon(Material icon) {
        return switch (icon) {
            case SUGAR -> "bard-buff-speed";
            case BLAZE_POWDER -> "bard-buff-strength";
            case IRON_INGOT -> "bard-buff-resistance";
            case GHAST_TEAR -> "bard-buff-regeneration";
            case FEATHER -> "bard-buff-jump-boost";
            default -> null;
        };
    }

    private void onBuffItem(PlayerInteractEvent event, String buffId) {
        Ability ability = abilityManager.get(buffId);
        if (ability == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability)) {
            return;
        }

        PotionEffectType type = PotionEffectType.getByName(ability.getString("effect-type", ""));
        if (type == null) {
            return;
        }
        int seconds = Math.max(1, ability.getInt("buff-seconds", 6));
        int amplifier = Math.max(0, ability.getInt("effect-amplifier", 0));
        // A bard actually wearing the full gold set doubles both the buff
        // duration and its effect level (Level I -> II, II -> IV, ...);
        // using a buff item outside the bard kit instead halves both back
        // down, never below Level I / 1 second.
        if (ArmorClass.isBard(player)) {
            seconds *= 2;
            amplifier = amplifier * 2 + 1;
        } else {
            seconds = Math.max(1, seconds / 2);
            amplifier = Math.max(0, (amplifier + 1) / 2 - 1);
        }
        PotionEffect effect = new PotionEffect(type, seconds * 20, amplifier);

        double radius = Math.max(0, plugin.getConfig().getDouble("abilities.bard-share-radius-blocks", 30));
        double radiusSquared = radius * radius;
        int recipients = 0;
        for (Player member : FactionsHook.getOnlineFactionMembers(player)) {
            if (!member.getWorld().equals(player.getWorld())
                    || member.getLocation().distanceSquared(player.getLocation()) > radiusSquared) {
                continue;
            }
            member.addPotionEffect(effect);
            recipients++;
        }
        player.sendMessage(messages.get(player, "ability.bard-shared", "count", String.valueOf(recipients)));
    }
}
