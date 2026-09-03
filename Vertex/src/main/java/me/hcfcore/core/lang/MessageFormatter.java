package me.hcfcore.core.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Pattern;

public final class MessageFormatter {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern LEGACY_HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private MessageFormatter() {
    }

    public static Component deserialize(String message) {
        return MINI_MESSAGE.deserialize(normalize(message))
            .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * The visible text of a color-coded string, with all formatting
     * removed -- for matching or sorting on what a player actually reads
     * rather than on the markup wrapped around it. Goes through
     * deserialize() so it handles legacy codes and MiniMessage tags alike.
     */
    public static String plain(String message) {
        return PlainTextComponentSerializer.plainText().serialize(deserialize(message));
    }

    private static String normalize(String message) {
        message = LEGACY_HEX.matcher(message).replaceAll("<#$1>");
        return message
                .replace("<deny>", "<red>")
                .replace("</deny>", "</red>")
                .replace("<success>", "<green>")
                .replace("</success>", "</green>")
                .replace("<info>", "<gray>")
                .replace("</info>", "</gray>")
                .replace("<warning>", "<yellow>")
                .replace("</warning>", "</yellow>")
                .replace("&0", "<black>")
                .replace("&1", "<dark_blue>")
                .replace("&2", "<dark_green>")
                .replace("&3", "<dark_aqua>")
                .replace("&4", "<dark_red>")
                .replace("&5", "<dark_purple>")
                .replace("&6", "<gold>")
                .replace("&7", "<gray>")
                .replace("&8", "<dark_gray>")
                .replace("&9", "<blue>")
                .replace("&a", "<green>")
                .replace("&b", "<aqua>")
                .replace("&c", "<red>")
                .replace("&d", "<light_purple>")
                .replace("&e", "<yellow>")
                .replace("&f", "<white>")
                .replace("&k", "<obfuscated>")
                .replace("&l", "<bold>")
                .replace("&m", "<strikethrough>")
                .replace("&n", "<underlined>")
                .replace("&o", "")
                .replace("&r", "<reset>");
    }
}