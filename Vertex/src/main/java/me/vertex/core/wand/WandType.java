package me.vertex.core.wand;

/** What a wand does when a container is left-clicked with it. */
public enum WandType {
    /** Sells the container's eligible contents, paying the user directly. */
    SELL,
    /** Converts the container's Gunpowder into TNT banked to the user's faction. */
    TNT
}
