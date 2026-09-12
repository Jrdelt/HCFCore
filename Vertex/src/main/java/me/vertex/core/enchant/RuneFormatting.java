package me.vertex.core.enchant;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.Locale;

/** Shared presentation rules for every legacy and Mob Arena rune item. */
public final class RuneFormatting {
    private RuneFormatting() {
    }

    public static Component title(String name, int level, NamedTextColor color, boolean maximumLevel) {
        return plain(smallCaps(name) + " ", color)
                .append(plain(roman(level), color).decoration(TextDecoration.BOLD, maximumLevel));
    }

    public static Component appliedLine(String name, int level, NamedTextColor color, boolean maximumLevel) {
        return title(name, level, color, maximumLevel);
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
        };
    }
}
