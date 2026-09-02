package me.hcfcore.core.listener;

import me.hcfcore.core.ability.Ability;
import me.hcfcore.core.ability.AbilityManager;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import me.hcfcore.core.worldguard.WorldGuardHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Generic right-click-to-activate trigger for ability items. This is the
 * seam later work replaces per-ability -- for now every ability shares the
 * same "not implemented yet" payload, but the cooldown/global-cooldown/
 * region-block plumbing above it is real and already fully working.
 * Throw- or hit-triggered abilities (switcher-snowball, anti-blockup-bone)
 * will need their own listener later, calling the same AbilityManager /
 * WorldGuardHook checks used here.
 */
public final class AbilityUseListener implements Listener {

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final UserManager userManager;

    public AbilityUseListener(Plugin plugin, AbilityManager abilityManager, UserManager userManager) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
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
        if (item == null || !item.hasItemMeta()) {
            return;
        }

        NamespacedKey key = new NamespacedKey(plugin, AbilityManager.ABILITY_ID_KEY);
        String abilityId = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (abilityId == null) {
            return;
        }
        Ability ability = abilityManager.get(abilityId);
        if (ability == null) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (abilityManager.isOnGlobalCooldown(player.getUniqueId())) {
            long remaining = (abilityManager.globalCooldownRemainingMillis(player.getUniqueId()) + 999) / 1000;
            player.sendMessage(Component.text(
                    "You're on ability cooldown for another " + remaining + "s.", NamedTextColor.RED));
            return;
        }

        User user = userManager.get(player.getUniqueId());
        if (user != null && abilityManager.isOnCooldown(user, ability)) {
            long remaining = (abilityManager.remainingCooldownMillis(user, ability) + 999) / 1000;
            player.sendMessage(Component.text("You can use this again in " + remaining + "s.", NamedTextColor.RED));
            return;
        }

        Set<String> disabledRegions = Set.copyOf(plugin.getConfig().getStringList("abilities.disabled-regions"));
        if (WorldGuardHook.isInDisabledRegion(player, disabledRegions)) {
            player.sendMessage(Component.text("Abilities can't be used here.", NamedTextColor.RED));
            return;
        }

        abilityManager.markGlobalCooldown(player.getUniqueId());
        if (user != null) {
            abilityManager.startCooldown(player, user, ability);
        }
        player.sendMessage(Component.text("⚠ ", NamedTextColor.YELLOW)
                .append(LegacyComponentSerializer.legacyAmpersand().deserialize(ability.getDisplayName()))
                .append(Component.text(" isn't implemented yet.", NamedTextColor.YELLOW)));
    }
}
