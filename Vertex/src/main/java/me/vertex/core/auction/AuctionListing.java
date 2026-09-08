package me.vertex.core.auction;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** One active, unsold buy-it-now listing. */
public record AuctionListing(int id, UUID sellerUuid, ItemStack item, double price, AuctionCurrency currency,
        long listedAtMillis, long expiresAtMillis) {

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    /**
     * True while the listing exists only in memory, waiting on the database
     * insert that will give it a real id.
     *
     * <p>A pending listing must never be bought, cancelled, or swept. Doing so
     * removes it from the live map, and the insert callback then re-adds it
     * under its real id -- putting the same item back up for sale after it has
     * already been sold. It is shown so the seller sees their listing appear
     * at once, but it cannot be acted on until it is durable.
     */
    public boolean isPending() {
        return id < 0;
    }
}
