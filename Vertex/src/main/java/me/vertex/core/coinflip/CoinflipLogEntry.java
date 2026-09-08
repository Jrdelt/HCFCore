package me.vertex.core.coinflip;

import java.util.UUID;

/**
 * A permanent audit record of one coinflip's outcome, for staff to
 * investigate a lost item or a coinflip that "disappeared." Written once a
 * coinflip resolves or is cancelled and never mutated afterward;
 * {@code summary} freezes a human-readable description of the wager at
 * that moment, since the items/amount themselves may later be spent,
 * traded, or consumed.
 */
public record CoinflipLogEntry(
        int id,
        UUID hostUuid,
        UUID opponentUuid,
        CoinflipType type,
        String summary,
        UUID winnerUuid,
        long resolvedAtMillis,
        Status status,
        UUID cancelledByUuid) {

    public enum Status {
        RESOLVED,
        CANCELLED
    }
}
