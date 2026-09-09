package me.vertex.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NumbersTest {

    @AfterEach
    void resetSettings() {
        Numbers.configure(NumberSettings.DEFAULTS);
    }

    @Test
    void parsesPlainAndGroupedNumbers() {
        assertEquals(new BigDecimal("1"), Numbers.parse("1"));
        assertEquals(new BigDecimal("1000000"), Numbers.parse("1000000"));
        assertEquals(new BigDecimal("1500000"), Numbers.parse("1,500,000"));
    }

    @Test
    void parsesEverySupportedSuffixCaseInsensitively() {
        assertEquals(0, Numbers.parse("10k").compareTo(new BigDecimal("10000")));
        assertEquals(0, Numbers.parse("10K").compareTo(new BigDecimal("10000")));
        assertEquals(0, Numbers.parse("1.5m").compareTo(new BigDecimal("1500000")));
        assertEquals(0, Numbers.parse("2.5B").compareTo(new BigDecimal("2500000000")));
        assertEquals(0, Numbers.parse("1t").compareTo(new BigDecimal("1000000000000")));
    }

    @Test
    void parsesDecimalShorthandExactly() {
        assertEquals(0, Numbers.parse("1.25k").compareTo(new BigDecimal("1250")));
        assertEquals(0, Numbers.parse("2.75b").compareTo(new BigDecimal("2750000000")));
    }

    /**
     * The whole reason this is BigDecimal: 0.1 + 0.2 style drift would make a
     * bank deposit land a cent off, and a double-backed parser cannot promise
     * this stays exact.
     */
    @Test
    void decimalShorthandDoesNotDriftLikeFloatingPoint() {
        assertEquals("1500000.00", Numbers.parse("1.50m").toPlainString());
        assertEquals(0, Numbers.parse("0.07m").compareTo(new BigDecimal("70000")));
    }

    @Test
    void rejectsMalformedInput() {
        assertNull(Numbers.parse("1..5m"));
        assertNull(Numbers.parse("10kk"));
        assertNull(Numbers.parse("m10"));
        assertNull(Numbers.parse("1.5.2b"));
        assertNull(Numbers.parse("abc"));
        assertNull(Numbers.parse("k"));
        assertNull(Numbers.parse("12x"));
        assertNull(Numbers.parse(null));
        assertNull(Numbers.parse(""));
        assertNull(Numbers.parse("   "));
    }

    @Test
    void rejectsNonFiniteAndExponentNotation() {
        assertNull(Numbers.parse("Infinity"));
        assertNull(Numbers.parse("NaN"));
        assertNull(Numbers.parse("1e9"));
    }

    @Test
    void rejectsSignedInputSoTransactionsCannotGoNegative() {
        assertNull(Numbers.parse("-10k"));
        assertNull(Numbers.parse("-5"));
        assertNull(Numbers.parse("+5"));
    }

    @Test
    void rejectsMisgroupedCommas() {
        assertNull(Numbers.parse("1,00,000"));
        assertNull(Numbers.parse("1,5"));
    }

    @Test
    void rejectsValuesBeyondTheConfiguredCeiling() {
        assertEquals(0, Numbers.parse("999999t").compareTo(new BigDecimal("999999000000000000")));
        assertNull(Numbers.parse("1000001t"));
    }

    @Test
    void parsePositiveRejectsZeroButParseKeepsIt() {
        assertEquals(0, Numbers.parse("0").signum());
        assertNull(Numbers.parsePositive("0"));
        assertNull(Numbers.parseLongPositive("0"));
    }

    @Test
    void parsesFormattedPositiveDoublesForMoneyAndAuctionPrices() {
        assertEquals(10_000D, Numbers.parseDoublePositive("10k"));
        assertEquals(1_250.50D, Numbers.parseDoublePositive("1.2505k"));
        assertEquals(1_000_000D, Numbers.parseDoublePositive("1,000,000"));
        assertNull(Numbers.parseDoublePositive("0"));
        assertNull(Numbers.parseDoublePositive("10kk"));
    }

    @Test
    void parseLongRoundsToWholeUnits() {
        assertEquals(1L, Numbers.parseLong("0.6"));
        assertEquals(1001L, Numbers.parseLong("1.0005k"));
    }

    @Test
    void formatsShortWithTrailingZerosStripped() {
        assertEquals("1K", Numbers.formatShort(1_000L));
        assertEquals("1.5K", Numbers.formatShort(1_500L));
        assertEquals("10K", Numbers.formatShort(10_000L));
        assertEquals("1M", Numbers.formatShort(1_000_000L));
        assertEquals("1.5M", Numbers.formatShort(1_500_000L));
        assertEquals("1.25B", Numbers.formatShort(1_250_000_000L));
    }

    @Test
    void formatsBelowThresholdInFull() {
        assertEquals("999", Numbers.formatShort(999L));
        assertEquals("0", Numbers.formatShort(0L));
    }

    @Test
    void formatsFullWithCommaGrouping() {
        assertEquals("1,500,000", Numbers.formatFull(1_500_000L));
        assertEquals("999", Numbers.formatFull(999L));
    }

    @Test
    void formatsMoneyWithConfiguredSymbol() {
        assertEquals("$1.5M", Numbers.money(1_500_000D));
        assertEquals("$1,500,000", Numbers.moneyFull(1_500_000D));
        assertEquals("$1.5M (1,500,000)", Numbers.moneyBoth(1_500_000D));
    }

    @Test
    void currencySymbolIsNotHardcoded() {
        Numbers.configure(new NumberSettings(
                NumberSettings.DEFAULTS.suffixes(),
                "€",
                NumberSettings.DEFAULTS.maxDecimals(),
                NumberSettings.DEFAULTS.rounding(),
                NumberSettings.DEFAULTS.acceptCommas(),
                NumberSettings.DEFAULTS.abbreviationThreshold(),
                NumberSettings.DEFAULTS.maxValue()));
        assertEquals("€1.5M", Numbers.money(1_500_000D));
    }

    @Test
    void commaInputCanBeRejectedByConfiguration() {
        Numbers.configure(new NumberSettings(
                NumberSettings.DEFAULTS.suffixes(),
                NumberSettings.DEFAULTS.currencySymbol(),
                NumberSettings.DEFAULTS.maxDecimals(),
                NumberSettings.DEFAULTS.rounding(),
                false,
                NumberSettings.DEFAULTS.abbreviationThreshold(),
                NumberSettings.DEFAULTS.maxValue()));
        assertNull(Numbers.parse("1,500,000"));
        assertEquals(0, Numbers.parse("1500000").compareTo(new BigDecimal("1500000")));
    }

    @Test
    void labelsExpAndTntWithoutImplyingCurrency() {
        assertEquals("1.5M EXP", Numbers.exp(1_500_000L));
        assertEquals("500K TNT", Numbers.tnt(500_000L));
    }
}
