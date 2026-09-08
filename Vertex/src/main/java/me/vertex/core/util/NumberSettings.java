package me.vertex.core.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Tuning for {@link Numbers}, kept as a plain record so the parser/formatter
 * stays free of Bukkit and unit-testable without a server. The plugin builds
 * one of these from {@code number-formatting.yml} at load and reload.
 *
 * @param suffixes             shorthand suffixes, ordered smallest multiplier first
 * @param currencySymbol       prefix for money output; never hardcoded to "$"
 * @param maxDecimals          decimal places kept in abbreviated output before trailing zeros are stripped
 * @param rounding             applied whenever a value must collapse to a whole unit
 * @param acceptCommas         whether grouped input like "1,500,000" parses
 * @param abbreviationThreshold values below this format in full rather than abbreviated
 * @param maxValue             inputs above this are rejected instead of overflowing storage
 */
public record NumberSettings(
        List<Suffix> suffixes,
        String currencySymbol,
        int maxDecimals,
        RoundingMode rounding,
        boolean acceptCommas,
        BigDecimal abbreviationThreshold,
        BigDecimal maxValue) {

    /** One shorthand suffix: the letter a player types and what it multiplies by. */
    public record Suffix(String label, BigDecimal multiplier) {
    }

    public static final NumberSettings DEFAULTS = new NumberSettings(
            List.of(
                    new Suffix("K", new BigDecimal("1000")),
                    new Suffix("M", new BigDecimal("1000000")),
                    new Suffix("B", new BigDecimal("1000000000")),
                    new Suffix("T", new BigDecimal("1000000000000"))),
            "$",
            2,
            RoundingMode.HALF_UP,
            true,
            new BigDecimal("1000"),
            new BigDecimal("1E18"));
}
