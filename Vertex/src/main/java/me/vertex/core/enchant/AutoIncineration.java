package me.vertex.core.enchant;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The shared "is Auto-Incineration currently active for this player" check
 * used by every trigger point (identification, external pickup) plus the
 * enabled-state toggle itself -- one place, so permission loss/regain and
 * the enabled preference behave identically everywhere instead of being
 * re-implemented per call site.
 */
public final class AutoIncineration {

    public static final String PERMISSION = "vertex.enchant.auto-incinerate";
    private static final String ENABLED_KEY = "auto-incinerate-enabled";

    private final RunePreferenceManager preferences;

    public AutoIncineration(RunePreferenceManager preferences) {
        this.preferences = preferences;
    }

    public boolean isEnabled(UUID uuid) {
        return Boolean.parseBoolean(preferences.get(uuid, RunePreferenceManager.GLOBAL, ENABLED_KEY, "false"));
    }

    /** Permission loss makes processing inert immediately without touching the saved preference or filters -- checked live, never cached. */
    public boolean isActive(Player player) {
        return player.hasPermission(PERMISSION) && isEnabled(player.getUniqueId());
    }

    public void setEnabled(UUID uuid, boolean enabled) {
        preferences.set(uuid, RunePreferenceManager.GLOBAL, ENABLED_KEY, String.valueOf(enabled));
    }

    public boolean isEligibleAndUnprotected(org.bukkit.inventory.ItemStack item, EnchantManager manager, UUID uuid) {
        if (item == null || item.getType().isAir() || !manager.isEnchantItem(item)) {
            return false;
        }
        return !IncineratorEligibility.isProtected(item, manager, preferences, uuid);
    }
}
