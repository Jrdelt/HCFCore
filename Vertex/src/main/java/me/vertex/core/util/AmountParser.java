package me.vertex.core.util;

/**
 * Parses a player-typed amount for the anvil-based quantity prompts (Chunk
 * Collector withdrawal, faction bank deposit/withdraw). These prompts always
 * want a whole count greater than zero, so this is a thin positive-only view
 * over {@link Numbers} -- the shared service owns the actual grammar.
 */
public final class AmountParser {

    private AmountParser() {
    }

    /** @return the parsed amount (always {@code > 0}), or {@code null} if {@code raw} isn't a valid amount. */
    public static Long parse(String raw) {
        return Numbers.parseLongPositive(raw);
    }
}
