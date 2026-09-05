package me.hcfcore.core.ability;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.kit.ArmorClass;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.UUID;

/**
 * Right-clicking the master Portable Bard item hands the player one of each
 * buff item instead of opening a GUI; right-clicking a buff item is what
 * actually applies that buff to the whole faction. Each buff item is its
 * own ability (its own cooldown, material, effect config in abilities.yml)
 * rather than a special GUI-only construct.
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

    @EventHandler(ignoreCancelled = true)
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

    private void onMasterItem(PlayerInteractEvent event) {
        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability)) {
            return;
        }

        List<ItemStack> items = BUFF_IDS.stream()
                .map(abilityManager::get)
                .filter(java.util.Objects::nonNull)
                .map(abilityManager::createItem)
                .toList();
        PlayerInventory inventory = player.getInventory();
        for (ItemStack dropped : inventory.addItem(items.toArray(new ItemStack[0])).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), dropped);
        }
        player.sendMessage(messages.get(player, "ability.bard-buffs-given"));
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
        for (Player member : FactionsHook.getOnlineFactionMembers(player)) {
            member.addPotionEffect(effect);
        }
        player.sendMessage(messages.get(player, "ability.bard-shared"));
    }

    /**
     * Gearing into the gold set shortens an outstanding out-of-class
     * Portable Bard cooldown -- see
     * {@link AbilityManager#shortenBardCooldownForKitSwap}.
     *
     * <p>Checked a tick later because the armor slot this event reports on
     * isn't necessarily settled in the inventory yet, and the check reads
     * the whole set rather than the one changed piece.
     */
    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player online = plugin.getServer().getPlayer(playerId);
            if (online == null) {
                return;
            }
            User user = userManager.get(playerId);
            if (user == null) {
                return;
            }
            long remaining = abilityManager.shortenBardCooldownForKitSwap(online, user);
            if (remaining >= 0L) {
                online.sendMessage(messages.get(online, "ability.bard-cooldown-shortened",
                        "seconds", String.valueOf(remaining)));
            }
        });
    }
}
