package me.vertex.core.auction;

/**
 * What a listing is priced in. GC (gift-card store credit) is a planned
 * third option -- deliberately not added here until it's actually wired
 * up to something real, so the GUI shows it as a visible but disabled
 * "coming soon" choice instead of a half-built currency nothing backs.
 */
public enum AuctionCurrency {
    MONEY, EXP
}
