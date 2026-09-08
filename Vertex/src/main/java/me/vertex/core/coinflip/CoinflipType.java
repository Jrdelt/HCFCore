package me.vertex.core.coinflip;

/**
 * What's being wagered. A store gift-card type ("GC") is planned but not
 * implemented yet -- there's deliberately no placeholder value for it here,
 * since a real "not implemented" branch is cheaper to add later than a
 * dead one is to keep in sync until then.
 */
public enum CoinflipType {
    MONEY,
    EXP,
    ITEMS
}
