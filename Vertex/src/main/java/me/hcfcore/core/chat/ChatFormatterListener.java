package me.hcfcore.core.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.luckperms.LuckPermsHook;
import me.hcfcore.core.tag.TagManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class ChatFormatterListener implements Listener {

    private final TagManager tagManager;
    private final FileConfiguration config;

    public ChatFormatterListener(TagManager tagManager, FileConfiguration config) {
        this.tagManager = tagManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> render(player, message)));
    }

    private Component render(Player player, Component message) {
        Component result = Component.empty();

        String faction = FactionsHook.getFactionTag(player);
        if (!"None".equalsIgnoreCase(faction)) {
            result = result.append(deserializeTemplate("chat.faction-format", "<gold>[{faction}]</gold> ", "faction", faction));
        }

        TagManager.Tag tag = selectedTag(player);
        if (tag != null) {
            result = result.append(coloredTag(tag));
        }

        String rank = LuckPermsHook.getPrimaryGroupDisplayName(player);
        if (rank != null && !rank.isBlank()) {
            result = result.append(deserializeTemplate("chat.rank-format", "<light_purple>[{rank}]</light_purple> ", "rank", rank));
        }

        return result.append(nameComponent(player))
                    .append(deserializeTemplate("chat.separator", "<gray>»</gray>"))
                    .append(message);
    }

    /**
     * `display` embeds its own tag color (tags.yml), so different tags
     * already look different in chat without a separate color lookup.
     * `display` is admin-authored (tags.yml), so this builds the
     * component directly rather than through the escaped placeholder path
     * in deserializeTemplate, which exists specifically to guard untrusted
     * values like a player's own name.
     */
    private Component coloredTag(TagManager.Tag tag) {
        return MessageFormatter.deserialize("<gray>[</gray>" + tag.display() + "<gray>]</gray> ");
    }

    /**
     * If the player has nickname-match enabled and their equipped tag has
     * a color, their name is rendered in that color/gradient instead of
     * the default chat.name-format. Player names can't contain MiniMessage
     * syntax (Minecraft restricts the character set), so no escaping is
     * needed here either.
     */
    private Component nameComponent(Player player) {
        String nicknameColor = tagManager.getNicknameColor(player);
        if (nicknameColor != null) {
            return MessageFormatter.deserialize(nicknameColor + player.getName());
        }
        return deserializeTemplate("chat.name-format", "<white>{name}</white>", "name", player.getName());
    }

    private Component deserializeTemplate(String path, String fallback, String... values) {
        String template = config.getString(path, fallback);
        for (int i = 0; i + 1 < values.length; i += 2) {
            template = template.replace("{" + values[i] + "}", MiniMessage.miniMessage().escapeTags(values[i + 1]));
        }
        return MessageFormatter.deserialize(template);
    }

    private TagManager.Tag selectedTag(Player player) {
        String tagId = tagManager.getPlayerTag(player.getUniqueId());
        TagManager.Tag tag = tagId == null ? null : tagManager.get(tagId);
        return tagManager.isUnlocked(player, tag) ? tag : null;
    }
}
