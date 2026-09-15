package me.vertex.core.enchant;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;

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
            case SEASONAL -> throw new IllegalArgumentException("Seasonal Runes keep their own configured material");
        };
    }
}
