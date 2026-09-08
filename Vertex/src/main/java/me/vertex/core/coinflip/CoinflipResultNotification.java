package me.vertex.core.coinflip;

import java.util.UUID;

/**
 * A result chat line waiting for a participant who disconnected before the
 * shared coinflip animation finished. The row is deliberately separate from
 * the audit log: a result can be delivered exactly once on the next join
 * without mutating or pruning the permanent staff record.
 */
public record CoinflipResultNotification(int id, UUID recipientUuid, boolean won, long createdAtMillis) {
}
