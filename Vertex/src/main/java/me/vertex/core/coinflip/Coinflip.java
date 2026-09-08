package me.vertex.core.coinflip;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * One open, not-yet-played coinflip. {@code amount} is the money amount for
 * {@link CoinflipType#MONEY}, or the wagered level count for
 * {@link CoinflipType#EXP} -- unused (0) for {@link CoinflipType#ITEMS},
 * where {@code items} carries the wager instead. {@code targetUuid} is null
 * for a coinflip anyone can play.
 */
public record Coinflip(
        int id,
        UUID hostUuid,
        UUID targetUuid,
        CoinflipType type,
        double amount,
        ItemStack[] items,
        long createdAtMillis) {

    /**
     * True while the coinflip exists only in memory, waiting on the database
     * insert that will give it a real id.
     *
     * <p>A pending coinflip must never be played, matched, or cancelled.
     * Doing so removes it from the live map, and the insert callback then
     * re-adds it under its real id -- reopening a wager that has already been
     * settled and paid out. It is shown so the host sees it appear at once,
     * but it cannot be acted on until it is durable.
     */
    public boolean isPending() {
        return id < 0;
    }

    public boolean isOpenTo(UUID playerUuid) {
        return targetUuid == null || targetUuid.equals(playerUuid);
    }
}
