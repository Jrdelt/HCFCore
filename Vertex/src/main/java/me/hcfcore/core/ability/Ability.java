package me.hcfcore.core.ability;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

/**
 * Metadata for an ability item, plus a generic bag of per-ability extra
 * settings (grapple multipliers, bard buff duration, etc.) so individual
 * ability listeners can each read their own config knobs from the same
 * abilities.yml section without this class growing a field per ability.
 */
public final class Ability {

    private final String id;
    private final Material material;
    private final String displayName;
    private final List<String> lore;
    private final int cooldownSeconds;
    private final Map<String, Object> settings;

    public Ability(String id, Material material, String displayName, List<String> lore, int cooldownSeconds,
                    Map<String, Object> settings) {
        this.id = id;
        this.material = material;
        this.displayName = displayName;
        this.lore = lore;
        this.cooldownSeconds = cooldownSeconds;
        this.settings = settings;
    }

    public String getId() {
        return id;
    }

    public Material getMaterial() {
        return material;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<String> getLore() {
        return lore;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public double getDouble(String key, double defaultValue) {
        Object value = settings.get(key);
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object value = settings.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    public String getString(String key, String defaultValue) {
        Object value = settings.get(key);
        return value instanceof String string ? string : defaultValue;
    }
}
