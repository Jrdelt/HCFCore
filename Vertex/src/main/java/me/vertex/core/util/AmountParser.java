package me.vertex.core.util;

import java.util.Locale;

/**
 * Parses a player-typed amount for the anvil-based quantity prompts
 * (Chunk Collector withdrawal, faction bank deposit/withdraw). Accepts a
 * plain whole number, optionally comma-grouped ("1,000,000"), and a
 * shorthand suffix -- k/m/b for thousand/million/billion, case-insensitive,
 * with an optional decimal point ("1.5m", "100k", "1b").
 */
public final class AmountParser {

    private AmountParser() {
    }

    /** @return the parsed amount (always {@code > 0}), or {@code null} if {@code raw} isn't a valid amount. */
    public static Long parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().replace(",", "").replace(" ", "");
        if (value.isEmpty()) {
            return null;
        }

        double multiplier = 1;
        char suffix = Character.toLowerCase(value.charAt(value.length() - 1));
        String numberPart = value;
        switch (suffix) {
            case 'k' -> {
                multiplier = 1_000D;
                numberPart = value.substring(0, value.length() - 1);
            }
            case 'm' -> {
                multiplier = 1_000_000D;
                numberPart = value.substring(0, value.length() - 1);
            }
            case 'b' -> {
                multiplier = 1_000_000_000D;
                numberPart = value.substring(0, value.length() - 1);
            }
            default -> {
                // No recognized suffix -- numberPart is the whole string.
            }
        }
        if (numberPart.isEmpty()) {
            return null;
        }

        double parsed;
        try {
            parsed = Double.parseDouble(numberPart.toLowerCase(Locale.ROOT)) * multiplier;
        } catch (NumberFormatException e) {
            return null;
        }
        // Double.parseDouble also accepts things this prompt shouldn't,
        // like "1e9" or "Infinity" -- both slip through as finite-looking
        // numbers otherwise, "1e9" harmlessly (it's a real number) but
        // "Infinity"/"NaN" would not be without this check.
        if (!Double.isFinite(parsed) || parsed <= 0 || parsed > 9.0e18) {
            return null;
        }
        return Math.round(parsed);
    }
}
