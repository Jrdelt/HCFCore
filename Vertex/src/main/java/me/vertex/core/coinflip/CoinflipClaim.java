package me.vertex.core.coinflip;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * One item payout waiting to be claimed -- from winning an item coinflip,
 * or an admin-cancelled item wager refunded while the host was offline.
 * Money and experience never go through this: they settle directly
 * (instantly if the recipient is online, otherwise via Vault's
 * offline-safe deposit for money, or on next join for experience).
 */
public record CoinflipClaim(int id, UUID winnerUuid, ItemStack[] items, long wonAtMillis) {
}
