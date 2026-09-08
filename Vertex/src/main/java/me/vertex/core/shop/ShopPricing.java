package me.vertex.core.shop;

/**
 * Pure market math for the Shop, kept free of Bukkit so it's unit-testable
 * without a server. {@code netVolume} is "units bought minus units sold"
 * since a block's price last fully recovered to its base -- positive means
 * the shop has been net-bought-from (price up), negative means net-sold-to
 * (price down).
 */
final class ShopPricing {

    private ShopPricing() {
    }

    /** The current buy price, clamped to [basePrice * minMultiplier, basePrice * maxMultiplier]. */
    static double buyPrice(double basePrice, double netVolume, double priceChangePerUnit,
            double minMultiplier, double maxMultiplier, double deadZoneUnits) {
        double raw = basePrice * Math.pow(1 + priceChangePerUnit, effectiveVolume(netVolume, deadZoneUnits));
        double min = basePrice * minMultiplier;
        double max = basePrice * maxMultiplier;
        return Math.max(min, Math.min(max, raw));
    }

    /**
     * The first {@code deadZoneUnits} of net volume in either direction move
     * the price not at all -- only volume beyond that threshold counts, so a
     * handful of trades doesn't nudge the price at all and it only actually
     * moves once a real amount has been bought or sold.
     */
    private static double effectiveVolume(double netVolume, double deadZoneUnits) {
        double magnitude = Math.max(0, Math.abs(netVolume) - deadZoneUnits);
        return Math.copySign(magnitude, netVolume);
    }

    /** Always a fraction of the *current* buy price, not the base price. */
    static double sellPrice(double currentBuyPrice, double sellPriceRatio) {
        return currentBuyPrice * sellPriceRatio;
    }

    /** Net-volume after buying {@code amount} units -- pushes the price up. */
    static double afterBuy(double netVolume, int amount) {
        return netVolume + amount;
    }

    /** Net-volume after selling {@code amount} units -- pushes the price down. */
    static double afterSell(double netVolume, int amount) {
        return netVolume - amount;
    }

    /** Net-volume after one decay interval, shrinking a {@code decayFraction} closer to zero (equilibrium). */
    static double decay(double netVolume, double decayFraction) {
        return netVolume * (1 - decayFraction);
    }

    /** True once decay has brought a block close enough to equilibrium that its stock row can be dropped. */
    static boolean isEffectivelyZero(double netVolume) {
        return Math.abs(netVolume) < 0.01;
    }
}
