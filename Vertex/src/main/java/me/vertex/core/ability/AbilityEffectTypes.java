package me.vertex.core.ability;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/** Resolves a configured vanilla potion-effect ID without using deprecated APIs. */
final class AbilityEffectTypes {
    private AbilityEffectTypes() {
    }

    static PotionEffectType find(String configuredId) {
        if (configuredId == null || configuredId.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(configuredId.trim().toLowerCase(Locale.ROOT));
        return key == null ? null : Registry.POTION_EFFECT_TYPE.get(key);
    }
}
