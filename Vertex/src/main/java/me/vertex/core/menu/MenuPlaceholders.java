package me.vertex.core.menu;

import me.vertex.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Values a menu fills into its configured name and lore templates.
 *
 * <p>Three kinds, deliberately kept apart:
 *
 * <ul>
 *   <li><b>text</b> -- inline {@code {key}} substitutions. Escaped before
 *       insertion, because a value may be a player name or faction tag and
 *       must never smuggle formatting into an admin's template.
 *   <li><b>trusted text</b> -- inline {@code {key}} substitutions inserted
 *       verbatim, for admin-authored lang snippets (e.g. a colored "Yes"/
 *       "No" or status label from en_us.yml) that are meant to carry their
 *       own MiniMessage formatting into the surrounding template. Never use
 *       this for a player name, faction tag, or anything else a player
 *       controls the contents of.
 *   <li><b>blocks</b> -- a whole lore line replaced by several rendered
 *       lines, for genuinely variable-length content like "one line per
 *       booster source". These are built in code, so they are trusted and
 *       inserted as-is.
 * </ul>
 */
public final class MenuPlaceholders {

    private final Map<String, String> text = new LinkedHashMap<>();
    private final Map<String, String> trustedText = new LinkedHashMap<>();
    private final Map<String, List<Component>> blocks = new LinkedHashMap<>();

    public static MenuPlaceholders of() {
        return new MenuPlaceholders();
    }

    public MenuPlaceholders put(String key, String value) {
        text.put(key, value == null ? "" : value);
        return this;
    }

    public MenuPlaceholders put(String key, Number value) {
        return put(key, String.valueOf(value));
    }

    /** Like {@link #put(String, String)}, but inserted without escaping -- see the class doc. */
    public MenuPlaceholders putTrusted(String key, String value) {
        trustedText.put(key, value == null ? "" : value);
        return this;
    }

    /** Replaces a lore line consisting solely of {@code {key}} with these lines. */
    public MenuPlaceholders putBlock(String key, List<Component> lines) {
        blocks.put(key, List.copyOf(lines));
        return this;
    }

    List<Component> block(String key) {
        return blocks.get(key);
    }

    /** The whole line is one placeholder, so it can expand to a block. */
    String soleBlockKey(String template) {
        String trimmed = template.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}") || trimmed.length() < 3) {
            return null;
        }
        String key = trimmed.substring(1, trimmed.length() - 1);
        return blocks.containsKey(key) ? key : null;
    }

    String apply(String template) {
        String result = template;
        for (Map.Entry<String, String> entry : text.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}",
                    MessageFormatter.escapeForSubstitution(entry.getValue()));
        }
        for (Map.Entry<String, String> entry : trustedText.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    /** Renders one template line, expanding it to several when it is a block. */
    List<Component> render(String template) {
        String blockKey = soleBlockKey(template);
        if (blockKey != null) {
            return new ArrayList<>(blocks.get(blockKey));
        }
        return List.of(MessageFormatter.deserialize(apply(template)));
    }
}
