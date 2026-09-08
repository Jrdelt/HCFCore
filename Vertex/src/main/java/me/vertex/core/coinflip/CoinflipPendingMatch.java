package me.vertex.core.coinflip;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * An opponent's item wager for an ITEMS coinflip, submitted but not yet
 * approved by the host. The opponent's items are already out of their
 * inventory the moment this exists -- persisted here (not just held in
 * memory) so a crash between submission and the host's decision can never
 * strand or lose them; {@code coinflipId} is unique across live rows,
 * enforced in code (one pending match per coinflip at a time), not by a
 * database constraint.
 */
public record CoinflipPendingMatch(int id, int coinflipId, UUID opponentUuid, ItemStack[] items,
        long requestedAtMillis) {
}
