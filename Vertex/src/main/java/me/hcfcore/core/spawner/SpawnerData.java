package me.hcfcore.core.spawner;

import org.bukkit.entity.EntityType;

/** A tracked, stacked spawner's mutable state -- everything but its location. */
public final class SpawnerData {

    private final EntityType mobType;
    private int stackSize;

    public SpawnerData(EntityType mobType, int stackSize) {
        this.mobType = mobType;
        this.stackSize = stackSize;
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
}
