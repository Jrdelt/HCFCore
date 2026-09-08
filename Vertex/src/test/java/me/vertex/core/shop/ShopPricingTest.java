package me.vertex.core.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopPricingTest {

    @Test
    void buyingPushesThePriceAboveBase() {
        double base = 10.0;
        double price = ShopPricing.buyPrice(base, 100, 0.001, 0.25, 4.0, 0.0);
        assertTrue(price > base, "positive net-volume (net bought) must raise the price above base");
    }

    @Test
    void sellingPushesThePriceBelowBase() {
        double base = 10.0;
        double price = ShopPricing.buyPrice(base, -100, 0.001, 0.25, 4.0, 0.0);
        assertTrue(price < base, "negative net-volume (net sold) must lower the price below base");
    }

    @Test
    void zeroNetVolumeIsExactlyBasePrice() {
        assertEquals(10.0, ShopPricing.buyPrice(10.0, 0, 0.001, 0.25, 4.0, 0.0), 0.0001);
    }

    @Test
    void priceIsClampedAtTheConfiguredBounds() {
        double base = 10.0;
        double high = ShopPricing.buyPrice(base, 1_000_000, 0.001, 0.25, 4.0, 0.0);
        double low = ShopPricing.buyPrice(base, -1_000_000, 0.001, 0.25, 4.0, 0.0);
        assertEquals(base * 4.0, high, 0.0001);
        assertEquals(base * 0.25, low, 0.0001);
    }

    @Test
    void volumeWithinTheDeadZoneDoesNotMoveThePriceAtAll() {
        double base = 10.0;
        assertEquals(base, ShopPricing.buyPrice(base, 50, 0.001, 0.25, 4.0, 50.0), 0.0001);
        assertEquals(base, ShopPricing.buyPrice(base, -50, 0.001, 0.25, 4.0, 50.0), 0.0001);
        assertEquals(base, ShopPricing.buyPrice(base, 30, 0.001, 0.25, 4.0, 50.0), 0.0001,
                "volume under the threshold must also stay flat, not just volume at exactly the threshold");
    }

    @Test
    void volumeBeyondTheDeadZoneMovesThePriceAsIfTheThresholdNeverHappened() {
        double base = 10.0;
        double withDeadZone = ShopPricing.buyPrice(base, 150, 0.001, 0.25, 4.0, 50.0);
        double withoutDeadZone = ShopPricing.buyPrice(base, 100, 0.001, 0.25, 4.0, 0.0);
        assertEquals(withoutDeadZone, withDeadZone, 0.0001,
                "150 units with a 50-unit dead zone must move the price exactly like 100 units with none");
    }

    @Test
    void sellPriceIsAFractionOfTheCurrentBuyPrice() {
        assertEquals(7.5, ShopPricing.sellPrice(10.0, 0.75), 0.0001);
    }

    @Test
    void afterBuyIncreasesNetVolumeByTheAmountBought() {
        assertEquals(15, ShopPricing.afterBuy(10, 5));
    }

    @Test
    void afterSellDecreasesNetVolumeByTheAmountSold() {
        assertEquals(5, ShopPricing.afterSell(10, 5));
    }

    @Test
    void decayShrinksNetVolumeTowardZeroFromEitherSide() {
        assertEquals(95.0, ShopPricing.decay(100.0, 0.05), 0.0001);
        assertEquals(-95.0, ShopPricing.decay(-100.0, 0.05), 0.0001);
    }

    @Test
    void decayEventuallyReachesEffectivelyZero() {
        double volume = 100.0;
        for (int i = 0; i < 1000; i++) {
            volume = ShopPricing.decay(volume, 0.05);
        }
        assertTrue(ShopPricing.isEffectivelyZero(volume));
    }
}
