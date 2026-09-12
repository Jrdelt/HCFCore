package me.vertex.core.faction;

import org.bukkit.Material;

/**
 * Upgrades owned by a faction, rather than by an individual player. The
 * config key is deliberately stable: it is also the value persisted in SQL.
 */
public enum FactionUpgrade {
    CLAIM_DAMAGE("claim-damage", Material.DIAMOND_SWORD),
    CLAIM_PROTECTION("claim-protection", Material.DIAMOND_CHESTPLATE),
    ARMOR_WEAR("armor-wear", Material.GOLDEN_CHESTPLATE),
    FALL_PROTECTION("fall-protection", Material.SLIME_BLOCK),
    FLY_BOOST("fly-boost", Material.FEATHER),
    WARPS("warps", Material.ENDER_PEARL),
    SPAWNER_RATE("spawner-rate", Material.SPAWNER),
    CROP_GROWTH("crop-growth", Material.WHEAT),
    MOB_XP("mob-xp", Material.EXPERIENCE_BOTTLE),
    /** Its per-level "bonus" is an absolute TNT capacity, not a percentage. */
    TNT_BANK("tnt-bank", Material.TNT),
    /** Bonus is the total number of unlocked faction-vault rows. */
    VAULT_ROWS("vault-rows", Material.ENDER_CHEST),
    /** Bonus is the total number of unlocked Base Claim slots (1..3). */
    BASE_CLAIM_SLOTS("base-claim-slots", Material.BEACON),
    /** Bonus is an explicitly configured number of additional Shield seconds. */
    SHIELD_DURATION("shield-duration", Material.SHIELD);

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
