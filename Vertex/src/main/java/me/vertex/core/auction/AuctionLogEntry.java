package me.vertex.core.auction;

import java.util.UUID;

/** A permanent audit record of one listing's outcome, for staff to investigate a lost or missing item. */
public record AuctionLogEntry(
        int id,
        UUID sellerUuid,
        UUID buyerUuid,
        String itemSummary,
        double price,
        long listedAtMillis,
        long resolvedAtMillis,
        Status status,
        UUID cancelledByUuid) {

    public enum Status {
        SOLD, EXPIRED, CANCELLED
    }
}
