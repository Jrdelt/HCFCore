package me.vertex.core.gc;

/**
 * Every kind of GC balance change, written once per row in {@code gc_log}.
 * A row's {@code amount} is always the positive magnitude of the change;
 * the action itself says whether it was a credit or a debit.
 */
public enum GcAction {
    /** Player-initiated: money leaves Vault economy, GC balance grows. */
    DEPOSIT,
    /** Legacy player-initiated GC-to-Vault withdrawal. Retained so old audit rows still load. */
    WITHDRAW,
    /** Player-initiated: GC balance became a single-use redeem code. */
    WITHDRAW_CODE,
    /** A staff-issued redeem code was consumed. */
    REDEEM,
    /** Staff credited a balance directly ({@code /gc admin give}). */
    STAFF_GIVE,
    /** Staff debited a balance directly ({@code /gc admin remove}). */
    STAFF_REMOVE,
    /** Staff overwrote a balance to an exact value ({@code /gc admin set}). */
    STAFF_SET,
    /** Staff reset a balance to zero ({@code /gc admin zero}). */
    STAFF_ZERO,
    /** A Coinflip wager was taken from the host or opponent. */
    COINFLIP_WAGER,
    /** A Coinflip wager was returned (cancelled, expired, or failed to persist). */
    COINFLIP_REFUND,
    /** A Coinflip payout was credited to the winner. */
    COINFLIP_PAYOUT,
    /** An Auction House listing fee was taken from the seller. */
    AUCTION_FEE,
    /** An Auction House listing fee was returned (a failed/cancelled listing). */
    AUCTION_FEE_REFUND,
    /** Auction House sale proceeds were credited to the seller. */
    AUCTION_SALE,
    /** An Auction House purchase price was taken from the buyer. */
    AUCTION_PURCHASE,
    /** An Auction House purchase was refunded (the sale failed to settle). */
    AUCTION_REFUND
}
