package me.vertex.core.auction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** One active, unsold buy-it-now listing. */
public record AuctionListing(int id, UUID sellerUuid, ItemStack item, double price, AuctionCurrency currency,
        long listedAtMillis, long expiresAtMillis) {

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }
}
