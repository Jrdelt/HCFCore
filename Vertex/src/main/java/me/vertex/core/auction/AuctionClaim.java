package me.vertex.core.auction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * An item waiting to be claimed back -- from a listing that expired
 * unsold, or was cancelled while the seller was offline. Proceeds from an
 * actual sale never go through this: money always settles instantly via
 * Vault, whether the seller is online or not.
 */
public record AuctionClaim(int id, UUID ownerUuid, ItemStack item, long createdAtMillis) {
}
