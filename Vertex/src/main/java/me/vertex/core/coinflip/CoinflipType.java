package me.vertex.core.coinflip;

/**
 * What's being wagered. {@link #GC} wagers Vertex's own self-hosted GC
 * balance (see {@code me.vertex.core.gc.GcManager}) -- taken and paid out
 * through {@code GcManager} the same way a money wager goes through
 * {@code EconomyHook}.
 */
public enum CoinflipType {
    MONEY,
    EXP,
    ITEMS,
    GC
}
