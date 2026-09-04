package me.hcfcore.core.ability;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;
import java.util.UUID;

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

        Buff buff = Buff.fromIcon(clicked.getType());
        if (buff == null) {
            return;
        }

        Ability ability = abilityManager.get(ABILITY_ID);
        if (ability == null || !AbilityGate.isAbility(plugin, player.getInventory().getItemInMainHand(), ABILITY_ID)) {
            return;
        }

        // Checked before the shared gate, which consumes the item and starts
        // the item cooldown: a buff that's still on its own cooldown must
        // cost the player nothing.
        long buffRemaining = abilityManager.buffCooldownRemainingMillis(player.getUniqueId(), ability, buff.id());
        if (buffRemaining > 0L) {
            player.sendMessage(messages.get(player, "ability.on-cooldown",
                    "seconds", String.valueOf((buffRemaining + 999L) / 1000L)));
            return;
        }

        if (!AbilityGate.checkAndStart(plugin, abilityManager, userManager, messages, player, ability)) {
            return;
        }
        abilityManager.startBuffCooldown(player.getUniqueId(), ability, buff.id());

        int buffSeconds = ability.getInt("buff-seconds", 6);
        PotionEffect effect = new PotionEffect(buff.effectType(), buffSeconds * 20, buff.amplifier());

        for (Player member : FactionsHook.getOnlineFactionMembers(player)) {
            member.addPotionEffect(effect);
        }
        player.closeInventory();
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        abilityManager.clearExpiredBuffCooldowns(event.getPlayer().getUniqueId());
    }

    /**
     * The buffs offered by {@link PortableBardMenu}, keyed by the icon they
     * are shown as so the click handler and the menu can't drift apart.
     */
    private enum Buff {
        SPEED(Material.SUGAR, PotionEffectType.SPEED, 1),
        STRENGTH(Material.BLAZE_POWDER, PotionEffectType.STRENGTH, 0),
        RESISTANCE(Material.IRON_INGOT, PotionEffectType.RESISTANCE, 0),
        REGENERATION(Material.GHAST_TEAR, PotionEffectType.REGENERATION, 0),
        JUMP_BOOST(Material.FEATHER, PotionEffectType.JUMP_BOOST, 1);

        private final Material icon;
        private final PotionEffectType effectType;
        private final int amplifier;

        Buff(Material icon, PotionEffectType effectType, int amplifier) {
            this.icon = icon;
            this.effectType = effectType;
            this.amplifier = amplifier;
        }

        static Buff fromIcon(Material icon) {
            for (Buff buff : values()) {
                if (buff.icon == icon) {
                    return buff;
                }
            }
            return null;
        }

        PotionEffectType effectType() {
            return effectType;
        }

        int amplifier() {
            return amplifier;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
}
