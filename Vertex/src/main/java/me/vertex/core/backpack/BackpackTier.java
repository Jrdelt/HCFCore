package me.vertex.core.backpack;

import org.bukkit.Material;

/** One Backpack type, loaded from {@code backpacks.yml}'s {@code tiers} section. */
record BackpackTier(
        String id,
        String displayName,
        Material itemType,
        int customModelData,
        double dropBonusBasePercent,
        double dropBonusPerLevelPercent) {
}
