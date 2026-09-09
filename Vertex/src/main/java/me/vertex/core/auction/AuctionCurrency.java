package me.vertex.core.auction;

/**
 * What a listing is priced in. {@link #GC} is Vertex's own self-hosted
 * currency (see {@code me.vertex.core.gc.GcManager}) -- listing fees,
 * purchase prices, and sale proceeds all move through {@code GcManager}
 * the same way a money listing moves through {@code EconomyHook}.
 */
public enum AuctionCurrency {
    MONEY, EXP, GC
}
