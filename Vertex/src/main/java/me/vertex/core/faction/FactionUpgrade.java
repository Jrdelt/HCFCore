package me.vertex.core.faction;

import org.bukkit.Material;

/**
 * Upgrades owned by a faction, rather than by an individual player. The
 * config key is deliberately stable: it is also the value persisted in SQL.
 */
public enum FactionUpgrade {
    CLAIM_DAMAGE("claim-damage", Material.DIAMOND_SWORD),
    CLAIM_PROTECTION("claim-protection", Material.SHIELD),
    ARMOR_WEAR("armor-wear", Material.ANVIL),
    FALL_PROTECTION("fall-protection", Material.FEATHER),
    FLY_BOOST("fly-boost", Material.PHANTOM_MEMBRANE),
    WARPS("warps", Material.ENDER_PEARL),
    SPAWNER_RATE("spawner-rate", Material.SPAWNER),
    CROP_GROWTH("crop-growth", Material.WHEAT),
    MOB_XP("mob-xp", Material.EXPERIENCE_BOTTLE);

    private final String configKey;
    private final Material icon;

    FactionUpgrade(String configKey, Material icon) {
        this.configKey = configKey;
        this.icon = icon;
    }

    public String configKey() {
        return configKey;
    }

    public Material icon() {
        return icon;
    }

    public static FactionUpgrade fromConfigKey(String value) {
        if (value == null) {
            return null;
        }
        for (FactionUpgrade upgrade : values()) {
            if (upgrade.configKey.equalsIgnoreCase(value)) {
                return upgrade;
            }
        }
        return null;
    }
}
