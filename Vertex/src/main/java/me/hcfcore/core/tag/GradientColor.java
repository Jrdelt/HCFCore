package me.hcfcore.core.tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reverses the color-stop order of a MiniMessage `<gradient:...>` tag, for
 * the tags GUI's nickname-match "Reversed" preview. A solid color (no
 * gradient tag present) is returned unchanged. A trailing numeric part
 * (a gradient "phase", not a color stop) is left in place rather than
 * being treated as a stop to reverse.
 */
public final class GradientColor {

    private static final Pattern GRADIENT = Pattern.compile("<gradient:([^>]+)>", Pattern.CASE_INSENSITIVE);

    private GradientColor() {
    }

    public static String reverse(String colorTag) {
        if (colorTag == null) {
            return null;
        }
        Matcher matcher = GRADIENT.matcher(colorTag);
        if (!matcher.find()) {
            return colorTag;
        }

        String[] parts = matcher.group(1).split(":");
        int stopCount = parts.length;
        if (stopCount > 0 && isNumeric(parts[stopCount - 1])) {
            stopCount--;
        }
        if (stopCount < 2) {
            return colorTag;
        }

        List<String> stops = new ArrayList<>(List.of(parts).subList(0, stopCount));
        Collections.reverse(stops);

        StringBuilder rebuilt = new StringBuilder("<gradient:");
        rebuilt.append(String.join(":", stops));
        for (int i = stopCount; i < parts.length; i++) {
            rebuilt.append(':').append(parts[i]);
        }
        rebuilt.append('>');

        return colorTag.substring(0, matcher.start())
                + rebuilt
                + colorTag.substring(matcher.end());
    }

    private static boolean isNumeric(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
