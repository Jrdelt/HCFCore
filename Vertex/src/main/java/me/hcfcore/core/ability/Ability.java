package me.hcfcore.core.ability;

import org.bukkit.Material;

import java.util.List;

/**
 * Metadata for an ability item. No behavior lives here yet -- what happens
 * when one is actually used is wired up separately, per ability, later.
 */
public final class Ability {

    private final String id;
    private final Material material;
    private final String displayName;
    private final List<String> lore;
    private final int cooldownSeconds;

    public Ability(String id, Material material, String displayName, List<String> lore, int cooldownSeconds) {
        this.id = id;
        this.material = material;
        this.displayName = displayName;
        this.lore = lore;
        this.cooldownSeconds = cooldownSeconds;
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
}
