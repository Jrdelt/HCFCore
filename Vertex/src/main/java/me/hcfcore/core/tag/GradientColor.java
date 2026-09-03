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
    // A tag's `display` can be authored in MiniMessage ("<light_purple>") or
    // legacy ("&d", "&#facc15") style, so the leading-color match needs to
    // recognize both.
    private static final Pattern LEADING_TAGS = Pattern.compile(
            "^(?:<[^>]+>|&#[0-9A-Fa-f]{6}|&[0-9A-Fa-fK-Ok-oRr])+");

    private GradientColor() {
    }

    /**
     * A tag's `display` embeds its own color directly (e.g.
     * "<gradient:#a:#b>Legend"). This pulls out just the leading color/
     * gradient tag(s), for reuse elsewhere (like coloring a matched
     * player's nickname) -- or null if display doesn't start with one.
     */
    public static String extractLeadingColor(String display) {
        if (display == null) {
            return null;
        }
        Matcher matcher = LEADING_TAGS.matcher(display);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * The counterpart to extractLeadingColor: the same display string with
     * its leading color/gradient tag(s) removed, for sorting/searching by
     * the visible name rather than the raw MiniMessage-tagged string.
     */
    public static String stripLeadingColor(String display) {
        if (display == null) {
            return null;
        }
        Matcher matcher = LEADING_TAGS.matcher(display);
        return matcher.find() ? display.substring(matcher.end()) : display;
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
