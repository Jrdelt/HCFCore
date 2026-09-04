package me.hcfcore.core.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.luckperms.LuckPermsHook;
import me.hcfcore.core.placeholderapi.PlaceholderApiHook;
import me.hcfcore.core.tag.TagManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * On Paper, {@code AsyncChatEvent} has exactly one renderer slot -- the
 * last handler to call {@code event.renderer(...)} wins outright, nobody's
 * output is merged with anybody else's. FactionsUUID ships its own
 * Paper-native chat formatter (enabled by default) that sets its renderer
 * at {@code HIGHEST} too (see {@code ListenPaperChat.onPlayerChatLater} in
 * dev.kitteh:factions), so at equal priority it came down to plugin load
 * order which formatter actually showed up in chat -- sometimes ours,
 * sometimes theirs. Registering at {@code MONITOR} instead guarantees this
 * always runs after HIGHEST, so our renderer always wins regardless of
 * load order.
 */
public final class ChatFormatterListener implements Listener {

    private final TagManager tagManager;
    private final Plugin plugin;

    /**
     * Takes the {@link Plugin} rather than a {@code FileConfiguration}
     * snapshot -- {@code plugin.reloadConfig()} builds a brand-new config
     * object rather than mutating the old one in place, so a reference
     * captured once at construction would go stale after every
     * {@code /hcfcore reload}. Calling {@code plugin.getConfig()} fresh
     * each time picks up config changes immediately.
     */
    public ChatFormatterListener(TagManager tagManager, Plugin plugin) {
        this.tagManager = tagManager;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        event.renderer(ChatRenderer.viewerUnaware((source, displayName, message) -> render(player, message)));
    }

    private Component render(Player player, Component message) {
        Component result = Component.empty();

        String faction = FactionsHook.getFactionTag(player);
        if (!"None".equalsIgnoreCase(faction)) {
            result = result.append(deserializeTemplate(player, "chat.faction-format", "<gold>[{faction}]</gold> ", "faction", faction));
        }

        TagManager.Tag tag = selectedTag(player);
        if (tag != null) {
            result = result.append(coloredTag(tag));
        }

        String rank = LuckPermsHook.getPrimaryGroupDisplayName(player);
        String prefix = LuckPermsHook.getPrefix(player);
        if ((rank != null && !rank.isBlank()) || prefix != null) {
            result = result.append(rankComponent(player, rank, prefix));
        }

        return result.append(nameComponent(player))
                    .append(deserializeTemplate(player, "chat.separator", "<gray>»</gray>"))
                    .append(message);
    }

    /**
     * `{prefix}` is LuckPerms' own prefix meta ({@code /lp user <name>
     * meta setprefix "..."}) -- admin-authored and already carrying
     * whatever color/brackets the admin configured -- so it's substituted
     * raw rather than through {@link #deserializeTemplate}'s
     * escaped-placeholder path, same reasoning as {@link #coloredTag}.
     * {@code {rank}} (the LuckPerms group's plain display name) still goes
     * through the escaped substitution since it isn't meant to carry
     * markup of its own.
     */
    private Component rankComponent(Player player, String rank, String prefix) {
        String template = plugin.getConfig().getString("chat.rank-format", "<light_purple>[{rank}]</light_purple> ");
        template = template.replace("{prefix}", prefix == null ? "" : prefix);
        template = template.replace("{rank}", MiniMessage.miniMessage().escapeTags(rank == null ? "" : rank));
        return MessageFormatter.deserialize(PlaceholderApiHook.apply(player, template));
    }

    /**
     * `display` embeds its own tag color and brackets (tags.yml), so
     * different tags already look different in chat without a separate
     * color lookup, and don't need brackets added again here. `display` is
     * admin-authored (tags.yml), so this builds the component directly
     * rather than through the escaped placeholder path in
     * deserializeTemplate, which exists specifically to guard untrusted
     * values like a player's own name.
     */
    private Component coloredTag(TagManager.Tag tag) {
        return MessageFormatter.deserialize(tag.display() + " ");
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
        return deserializeTemplate(player, "chat.name-format", "<white>{name}</white>", "name", player.getName());
    }

    private Component deserializeTemplate(Player player, String path, String fallback, String... values) {
        String template = plugin.getConfig().getString(path, fallback);
        for (int i = 0; i + 1 < values.length; i += 2) {
            template = template.replace("{" + values[i] + "}", MiniMessage.miniMessage().escapeTags(values[i + 1]));
        }
        return MessageFormatter.deserialize(PlaceholderApiHook.apply(player, template));
    }

    private TagManager.Tag selectedTag(Player player) {
        String tagId = tagManager.getPlayerTag(player.getUniqueId());
        TagManager.Tag tag = tagId == null ? null : tagManager.get(tagId);
        return tagManager.isUnlocked(player, tag) ? tag : null;
    }
}
