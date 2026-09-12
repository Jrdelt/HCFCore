package me.vertex.core.grace;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parser for compact durations such as 2d12h, 30m, or 45s. */
public final class DurationParser {
    private static final Pattern PART = Pattern.compile("(\\d+)([dhms])");
    private DurationParser() { }

    public static long parseSeconds(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Duration is empty");
        String value = input.toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher matcher = PART.matcher(value);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (matcher.start() != end) throw new IllegalArgumentException("Invalid duration");
            long amount = Long.parseLong(matcher.group(1));
            long unit = switch (matcher.group(2)) { case "d" -> 86_400L; case "h" -> 3_600L; case "m" -> 60L; default -> 1L; };
            total = Math.addExact(total, Math.multiplyExact(amount, unit));
            end = matcher.end();
        }
        if (end != value.length() || total <= 0) throw new IllegalArgumentException("Invalid duration");
        return total;
    }

    public static String format(long seconds) {
        seconds = Math.max(0, seconds);
        long days = seconds / 86_400; seconds %= 86_400;
        long hours = seconds / 3_600; seconds %= 3_600;
        long minutes = seconds / 60; long secs = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(days).append('d').append(' ');
        if (hours > 0) out.append(hours).append('h').append(' ');
        if (minutes > 0) out.append(minutes).append('m').append(' ');
        if (secs > 0 || out.isEmpty()) out.append(secs).append('s');
        return out.toString().trim();
    }
}
