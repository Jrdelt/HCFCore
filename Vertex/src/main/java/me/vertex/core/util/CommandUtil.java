package me.vertex.core.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Standardized command utility methods for tab-completion and argument matching.
 */
public final class CommandUtil {

    private CommandUtil() {}

    /**
     * Filters a collection of string choices by prefix case-insensitively.
     */
    public static List<String> filterPrefix(Collection<String> options, String prefix) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>(options);
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(s -> s != null && s.toLowerCase(Locale.ROOT).startsWith(lower))
                .collect(Collectors.toList());
    }

    /**
     * Returns all online player names that match the given prefix case-insensitively.
     */
    public static List<String> matchOnlinePlayers(String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower))
                .collect(Collectors.toList());
    }
}
