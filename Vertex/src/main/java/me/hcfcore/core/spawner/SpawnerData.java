package me.hcfcore.core.spawner;

import org.bukkit.entity.EntityType;

/** A tracked, stacked spawner's mutable state -- everything but its location. */
public final class SpawnerData {

    private final EntityType mobType;
    private int stackSize;
    /**
     * The faction tag that placed this spawner, recorded once at placement
     * time and never touched afterward -- used to tell "this claim is being
     * overclaimed out from under its actual owner" apart from "the owning
     * faction just re-ran /f claim on land that was already theirs", which
     * the current claim owner alone can't distinguish after the fact. Null
     * for spawners placed before this tracking existed.
     */
    private final String ownerFactionTag;

    public SpawnerData(EntityType mobType, int stackSize, String ownerFactionTag) {
        this.mobType = mobType;
        this.stackSize = stackSize;
        this.ownerFactionTag = ownerFactionTag;
    }

    public EntityType mobType() {
        return mobType;
    }

    public int stackSize() {
        return stackSize;
    }

    public void setStackSize(int stackSize) {
        this.stackSize = stackSize;
    }

    public String ownerFactionTag() {
        return ownerFactionTag;
    }
}
