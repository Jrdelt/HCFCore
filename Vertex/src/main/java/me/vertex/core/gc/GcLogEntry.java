package me.vertex.core.gc;

import java.util.UUID;

/**
 * A permanent record of one GC balance change, written once and never
 * mutated afterward. {@code actorUuid} is null for a system-driven change
 * with no responsible player (a Coinflip payout, an Auction House sale);
 * otherwise it is the player or staff member who caused it. {@code note}
 * carries a redeem code, a staff reason, or is null.
 */
public record GcLogEntry(
        long id,
        UUID actorUuid,
        UUID targetUuid,
        GcAction action,
        long amount,
        long balanceAfter,
        String note,
        long createdAtMillis) {
}
