package me.vertex.core.enchant;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared presentation rules for every rune item, across every tier. */
public final class RuneFormatting {
    // The black-and-gold/eagle seasonal set's name gradient, matching the
    // exact per-letter color scheme supplied for it (a warm gold fading to
    // a darker bronze). Seasonal names render in normal case, not small
    // caps, as a deliberate visual break from every other tier.
    private static final TextColor SEASONAL_GRADIENT_START = TextColor.fromHexString("#D4AF37");
    private static final TextColor SEASONAL_GRADIENT_END = TextColor.fromHexString("#937025");

    private RuneFormatting() {
    }

    public static Component title(String name, int level, NamedTextColor color, boolean maximumLevel) {
        return plain(smallCaps(name) + " ", color)
                .append(plain(roman(level), color).decoration(TextDecoration.BOLD, maximumLevel));
    }

    public static Component appliedLine(String name, int level, NamedTextColor color, boolean maximumLevel) {
        return title(name, level, color, maximumLevel);
    }

    /** Tier-aware title: every tier uses {@link #title}, except Seasonal, which uses its own gold-to-bronze gradient (see {@link #seasonalTitle}). */
    public static Component titleFor(RuneTier tier, String name, int level, boolean maximumLevel) {
        return tier == RuneTier.SEASONAL ? seasonalTitle(name, level) : title(name, level, tierColor(tier), maximumLevel);
    }

    /** The seasonal set's normal-case, bold, per-character gold-to-bronze gradient name. */
    public static Component seasonalTitle(String name, int level) {
        return gradientBold(name + " " + roman(level), SEASONAL_GRADIENT_START, SEASONAL_GRADIENT_END);
    }

    private static Component gradientBold(String text, TextColor from, TextColor to) {
        Component result = Component.empty();
        int length = text.length();
        for (int index = 0; index < length; index++) {
            float ratio = length <= 1 ? 0F : (float) index / (length - 1);
            result = result.append(Component.text(String.valueOf(text.charAt(index)), TextColor.lerp(ratio, from, to))
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return result;
    }

    /**
     * Greedy word-wrap for a lore tooltip: breaks {@code text} into lines no
     * longer than {@code maxLineLength} characters, splitting only on spaces
     * so no word is ever cut mid-character. A single word longer than the
     * limit is kept whole on its own line rather than force-split, since a
     * client tooltip box already just widens for one long word -- it's a
     * whole unbroken paragraph that makes a lore box uncomfortably wide.
     */
    public static List<String> wrap(String text, int maxLineLength) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.isEmpty()) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= maxLineLength) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    /**
     * A rune's display name as a raw MiniMessage-tagged string, styled to
     * match its actual on-item name -- for embedding in a chat message
     * template's {@code {enchant}} placeholder (see {@link
     * me.vertex.core.lang.Messages}' raw-passthrough handling for that
     * key), so every chat line that names a specific rune shows it exactly
     * like the item itself does, instead of a generic "RUNES >" style
     * prefix. Seasonal gets the same bold gold-to-bronze per-character
     * gradient {@link #seasonalTitle} renders on the item (a flat color
     * here would visibly mismatch it); every other tier gets a flat color
     * matching {@link #title}'s plain (non-gradient, non-bold) name. {@code
     * null} tier (a retired/legacy rune with no current tier) falls back to
     * plain gray.
     */
    public static String coloredNameRaw(RuneTier tier, String name) {
        if (tier == RuneTier.SEASONAL) {
            return "<bold><gradient:" + SEASONAL_GRADIENT_START.asHexString() + ":"
                    + SEASONAL_GRADIENT_END.asHexString() + ">" + name + "</gradient></bold>";
        }
        TextColor color = tier == null ? NamedTextColor.GRAY : tierColor(tier);
        return "<color:" + color.asHexString() + ">" + name + "</color>";
    }

    public static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    public static String smallCaps(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replace("a", "ᴀ").replace("b", "ʙ").replace("c", "ᴄ")
                .replace("d", "ᴅ").replace("e", "ᴇ").replace("f", "ꜰ")
                .replace("g", "ɢ").replace("h", "ʜ").replace("i", "ɪ")
                .replace("j", "ᴊ").replace("k", "ᴋ").replace("l", "ʟ")
                .replace("m", "ᴍ").replace("n", "ɴ").replace("o", "ᴏ")
                .replace("p", "ᴘ").replace("q", "ǫ").replace("r", "ʀ")
                .replace("s", "ꜱ").replace("t", "ᴛ").replace("u", "ᴜ")
                .replace("v", "ᴠ").replace("w", "ᴡ").replace("x", "x")
                .replace("y", "ʏ").replace("z", "ᴢ");
    }

    public static String roman(int value) {
        if (value < 1 || value > 3999) {
            return String.valueOf(value);
        }
        int[] amounts = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < amounts.length; index++) {
            while (value >= amounts[index]) {
                result.append(numerals[index]);
                value -= amounts[index];
            }
        }
        return result.toString();
    }

    public static String percent(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    public static NamedTextColor tierColor(RuneTier tier) {
        return switch (tier) {
            case SIMPLE -> NamedTextColor.GRAY;
            case ELITE -> NamedTextColor.YELLOW;
            case RARE -> NamedTextColor.LIGHT_PURPLE;
            case LEGENDARY -> NamedTextColor.RED;
            case ARENA -> NamedTextColor.BLUE;
            case SEASONAL -> NamedTextColor.GOLD;
            case COMMON -> NamedTextColor.WHITE;
            case MYTHIC -> NamedTextColor.DARK_AQUA;
            case CURSED -> NamedTextColor.DARK_RED;
        };
    }

    /**
     * The dye-colored candle matching {@link #tierColor(RuneTier)}, used as
     * every identified (non-seasonal) Rune's physical item material
     * regardless of that Rune's own configured icon -- tier alone decides
     * the candle color, never the individual enchant. Seasonal Runes keep
     * whatever material their own config specifies and never call this.
     */
    public static Material tierCandle(RuneTier tier) {
        return switch (tier) {
            case SIMPLE -> Material.GRAY_CANDLE;
            case ELITE -> Material.YELLOW_CANDLE;
            case RARE -> Material.MAGENTA_CANDLE;
            case LEGENDARY -> Material.RED_CANDLE;
            case ARENA -> Material.BLUE_CANDLE;
            case COMMON -> Material.WHITE_CANDLE;
            case MYTHIC -> Material.CYAN_CANDLE;
            case CURSED -> Material.BLACK_CANDLE;
            case SEASONAL -> throw new IllegalArgumentException("Seasonal Runes keep their own configured material");
        };
    }
}
