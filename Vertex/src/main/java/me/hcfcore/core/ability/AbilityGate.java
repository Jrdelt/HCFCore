package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import me.hcfcore.core.worldguard.WorldGuardHook;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Set;

/**
 * Shared cooldown/global-cooldown/region gate for the right-click-triggered
 * abilities, so each one doesn't duplicate the same checked block. Hit- or
 * throw-triggered abilities (Anti-Blockup Bone, Switcher Snowball) do their
 * own silent version of this, since they can't message the player on every
 * miss the way a direct right-click can.
 */
public final class AbilityGate {

    private AbilityGate() {
    }

    public static boolean isAbility(Plugin plugin, ItemStack item, String abilityId) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        NamespacedKey key = new NamespacedKey(plugin, AbilityManager.ABILITY_ID_KEY);
        String taggedId = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return abilityId.equals(taggedId);
    }

    /**
     * Checks global cooldown, per-item cooldown, and disabled regions in
     * that order, messaging the player and returning false on the first
     * failure. On success, starts both cooldowns before returning true.
     */
    public static boolean checkAndStart(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                                         Messages messages, Player player, Ability ability) {
        return checkAndStart(plugin, abilityManager, userManager, messages, player, ability, true);
    }

    public static boolean checkAndStart(Plugin plugin, AbilityManager abilityManager, UserManager userManager,
                                         Messages messages, Player player, Ability ability, boolean consumeItem) {
        if (abilityManager.isOnGlobalCooldown(player.getUniqueId())) {
            long remaining = (abilityManager.globalCooldownRemainingMillis(player.getUniqueId()) + 999) / 1000;
            player.sendMessage(messages.getChat(player, "ability.on-global-cooldown", "seconds", String.valueOf(remaining)));
            return false;
        }

        User user = userManager.get(player.getUniqueId());
        if (user == null) {
            player.sendMessage(messages.getChat(player, "general.data-unavailable"));
            return false;
        }
        if (abilityManager.isOnCooldown(user, ability)) {
            long remaining = (abilityManager.remainingCooldownMillis(user, ability) + 999) / 1000;
            player.sendMessage(messages.getChat(player, "ability.on-cooldown", "seconds", String.valueOf(remaining)));
            return false;
        }

        Set<String> disabledRegions = Set.copyOf(plugin.getConfig().getStringList("abilities.disabled-regions"));
        if (WorldGuardHook.isInDisabledRegion(player, disabledRegions)) {
            player.sendMessage(messages.getChat(player, "ability.region-blocked"));
            return false;
        }

        abilityManager.markGlobalCooldown(player.getUniqueId());
        abilityManager.startCooldown(player, user, ability);
        if (consumeItem) {
            consumeMainHand(player);
        }
        return true;
    }

    private static void consumeMainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }
}
