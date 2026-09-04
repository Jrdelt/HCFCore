package me.hcfcore.core.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.regex.Pattern;

/**
 * Test support for asserting that a player received a particular
 * message, identified by its lang key rather than by its display text.
 *
 * <p>Tests used to assert on English fragments ({@code contains("Usage")}),
 * which meant any copy edit to lang/*.yml broke them even though the
 * behaviour under test was unchanged -- exactly what happened when the
 * bundled locales were rewritten in small-caps unicode. Resolving the
 * expected text through {@link Messages} instead keeps the assertion
 * pointed at "the right message was sent" and lets the wording move
 * freely.
 *
 * <p>Matching is done on placeholder-free plain text: the resolved
 * template is rendered to plain text, split on its {@code {placeholder}}
 * spans, and every literal segment must appear in the received message.
 * That ignores both formatting and runtime-substituted values, so a test
 * doesn't have to know what a config-driven number like the reboot delay
 * happened to be.
 */
public final class MessageAssertions {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    private MessageAssertions() {
    }

    /** The plain text of a component, formatting stripped; null-safe. */
    public static String plain(Component component) {
        return component == null ? null : PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * Whether {@code actual} is the rendering of {@code key} as
     * {@code viewer} would see it. {@link Messages#getChat} and
     * {@link Messages#get} are equivalent now -- neither adds a prefix,
     * any prefix (e.g. "&e&lABILITES &7>") is baked into the lang string
     * itself -- so there's no longer a meaningful distinction between a
     * "chat" and a "bare" message; this one helper covers both.
     */
    public static boolean isChatMessage(Messages messages, CommandSender viewer, Component actual, String key) {
        return isMessage(messages.getRaw(viewer, key), actual);
    }

    /** @deprecated identical to {@link #isChatMessage} now; kept for existing call sites. */
    @Deprecated
    public static boolean isBareMessage(Messages messages, CommandSender viewer, Component actual, String key) {
        return isChatMessage(messages, viewer, actual, key);
    }

    private static boolean isMessage(String rawTemplate, Component actual) {
        if (actual == null) {
            return false;
        }
        // Deserialize the whole template before splitting: placeholders are
        // literal text to MiniMessage, so they survive into the plain text,
        // whereas splitting first could hand MiniMessage a fragment whose
        // opening or closing tag lives in a different segment.
        String template = plain(MessageFormatter.deserialize(rawTemplate));
        String rendered = plain(actual);
        for (String segment : PLACEHOLDER.split(template)) {
            if (!segment.isBlank() && !rendered.contains(segment)) {
                return false;
            }
        }
        return true;
    }
}
