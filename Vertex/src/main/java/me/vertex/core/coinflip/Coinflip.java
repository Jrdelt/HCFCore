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

    public boolean isOpenTo(UUID playerUuid) {
        return targetUuid == null || targetUuid.equals(playerUuid);
    }
}
