package me.vertex.core.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one place Vertex turns player-typed amounts into numbers and numbers
 * back into text. Every economy, EXP, TNT, and leaderboard surface routes
 * through here so "1.5m" can never mean two different things in two commands.
 *
 * <p>Parsing is {@link BigDecimal} end to end -- money must never pick up a
 * floating-point rounding error on the way in. Input is validated by shape
 * rather than by catching a parse failure, so malformed values like "10kk",
 * "1..5m", or "1e9" are rejected outright instead of being reinterpreted.
 */
public final class Numbers {

    /**
     * Optional comma grouping, then optionally a single decimal part. Anchored
     * and sign-free on purpose: it is what rejects "-5", "1.5.2", and
     * exponent notation without a try/catch deciding validity.
     */
    private static final Pattern NUMBER = Pattern.compile("(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d+)?");

    private static volatile NumberSettings settings = NumberSettings.DEFAULTS;

    private Numbers() {
    }

    public static void configure(NumberSettings replacement) {
        settings = replacement == null ? NumberSettings.DEFAULTS : replacement;
    }

    public static NumberSettings settings() {
        return settings;
    }

    /** @return the exact parsed value, or {@code null} if {@code raw} isn't a well-formed amount. */
    public static BigDecimal parse(String raw) {
        return parse(raw, settings);
    }

    /** @return the parsed value, or {@code null} if it isn't well-formed or isn't greater than zero. */
    public static BigDecimal parsePositive(String raw) {
        BigDecimal parsed = parse(raw, settings);
        return parsed == null || parsed.signum() <= 0 ? null : parsed;
    }

    /** @return the parsed value rounded to a whole unit, or {@code null} if malformed or out of {@code long} range. */
    public static Long parseLong(String raw) {
        return toLong(parse(raw, settings));
    }

    /** @return as {@link #parseLong}, additionally requiring the result be greater than zero. */
    public static Long parseLongPositive(String raw) {
        Long parsed = toLong(parse(raw, settings));
        return parsed == null || parsed <= 0 ? null : parsed;
    }

    static BigDecimal parse(String raw, NumberSettings config) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replace(" ", "");
        if (value.isEmpty()) {
            return null;
        }
        if (!config.acceptCommas() && value.indexOf(',') >= 0) {
            return null;
        }

        BigDecimal multiplier = BigDecimal.ONE;
        String numberPart = value;
        for (NumberSettings.Suffix suffix : config.suffixes()) {
            String label = suffix.label();
            int start = value.length() - label.length();
            if (start > 0 && value.regionMatches(true, start, label, 0, label.length())) {
                multiplier = suffix.multiplier();
                numberPart = value.substring(0, start);
                break;
            }
        }
        if (!NUMBER.matcher(numberPart).matches()) {
            return null;
        }

        BigDecimal parsed = new BigDecimal(numberPart.replace(",", "")).multiply(multiplier);
        return parsed.abs().compareTo(config.maxValue()) > 0 ? null : parsed;
    }

    private static Long toLong(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal rounded = value.setScale(0, settings.rounding());
        return rounded.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 ? null : rounded.longValue();
    }

    /** Comma-grouped exact output, e.g. {@code 1,500,000} -- for logs, confirmations, and audit records. */
    public static String formatFull(BigDecimal value) {
        return plain(value, settings);
    }

    public static String formatFull(double value) {
        return plain(BigDecimal.valueOf(value), settings);
    }

    public static String formatFull(long value) {
        return plain(BigDecimal.valueOf(value), settings);
    }

    /** Abbreviated output, e.g. {@code 1.5M} -- for GUIs and chat where space is tight. */
    public static String formatShort(BigDecimal value) {
        return abbreviate(value, settings);
    }

    public static String formatShort(double value) {
        return abbreviate(BigDecimal.valueOf(value), settings);
    }

    public static String formatShort(long value) {
        return abbreviate(BigDecimal.valueOf(value), settings);
    }

    /** Abbreviated money with the configured currency symbol, e.g. {@code $1.5M}. */
    public static String money(BigDecimal value) {
        return settings.currencySymbol() + abbreviate(value, settings);
    }

    public static String money(double value) {
        return money(BigDecimal.valueOf(value));
    }

    /** Exact money with the configured currency symbol, e.g. {@code $1,500,000}. */
    public static String moneyFull(BigDecimal value) {
        return settings.currencySymbol() + plain(value, settings);
    }

    public static String moneyFull(double value) {
        return moneyFull(BigDecimal.valueOf(value));
    }

    /** Both forms, e.g. {@code $1.5M (1,500,000)} -- for confirmations where precision matters. */
    public static String moneyBoth(BigDecimal value) {
        return money(value) + " (" + plain(value, settings) + ")";
    }

    public static String moneyBoth(double value) {
        return moneyBoth(BigDecimal.valueOf(value));
    }

    /** Abbreviated experience, e.g. {@code 1.5M EXP} -- same digits as money, no currency implied. */
    public static String exp(long value) {
        return abbreviate(BigDecimal.valueOf(value), settings) + " EXP";
    }

    /** Abbreviated TNT, e.g. {@code 1.5M TNT}. The stored value stays an exact integer. */
    public static String tnt(long value) {
        return abbreviate(BigDecimal.valueOf(value), settings) + " TNT";
    }

    private static String plain(BigDecimal value, NumberSettings config) {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setMaximumFractionDigits(config.maxDecimals());
        format.setRoundingMode(config.rounding());
        return format.format(value);
    }

    private static String abbreviate(BigDecimal value, NumberSettings config) {
        BigDecimal magnitude = value.abs();
        if (magnitude.compareTo(config.abbreviationThreshold()) < 0) {
            return plain(value, config);
        }
        NumberSettings.Suffix chosen = null;
        for (NumberSettings.Suffix suffix : config.suffixes()) {
            if (magnitude.compareTo(suffix.multiplier()) >= 0) {
                chosen = suffix;
            }
        }
        if (chosen == null) {
            return plain(value, config);
        }
        BigDecimal scaled = value.divide(chosen.multiplier(), config.maxDecimals(), config.rounding());
        return scaled.stripTrailingZeros().toPlainString() + chosen.label();
    }
}
