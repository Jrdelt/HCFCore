package me.vertex.core.preferences;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Applies Settings choices to communication commands owned by the server or
 * Essentials without replacing those commands.  It only cancels an outgoing
 * command after resolving a real online recipient whose stored preference is
 * disabled, so recipients never see blocked requests.
 */
public final class PlayerInteractionPreferenceListener implements Listener {
    private static final Set<String> PRIVATE_MESSAGES = Set.of(
            "msg", "message", "tell", "w", "whisper", "m", "pm",
            "emsg", "etell", "ewhisper");
    private static final Set<String> PRIVATE_REPLIES = Set.of("r", "reply", "er", "ereply");
    private static final Set<String> PAYMENTS = Set.of("pay", "epay");
    private static final Set<String> TELEPORT_REQUESTS = Set.of("tpa", "tpahere", "etpa", "etpahere");

    private final AnnouncementPreferenceManager preferences;
    private final me.vertex.core.lang.Messages messages;

    public PlayerInteractionPreferenceListener(AnnouncementPreferenceManager preferences,
            me.vertex.core.lang.Messages messages) {
        this.preferences = preferences;
        this.messages = messages;
    }

    /** Global chat remains visible to the sender and console, but not to opted-out recipients. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlobalChat(AsyncChatEvent event) {
        event.viewers().removeIf(viewer -> viewer instanceof Player player
                && !preferences.isEnabled(player.getUniqueId(), AnnouncementCategory.GLOBAL_CHAT));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || raw.length() < 2) {
            return;
        }
        String[] parts = raw.substring(1).trim().split("\\s+");
        String root = parts[0].toLowerCase(Locale.ROOT);
        int namespace = root.indexOf(':');
        if (namespace >= 0) {
            root = root.substring(namespace + 1);
        }
        AnnouncementCategory category;
        String messageKey;
        Player recipient;
        if (PRIVATE_MESSAGES.contains(root)) {
            category = AnnouncementCategory.PRIVATE_MESSAGES;
            messageKey = "settings.recipient-private-messages-disabled";
            recipient = parts.length < 2 ? null : Bukkit.getPlayerExact(parts[1]);
        } else if (PRIVATE_REPLIES.contains(root)) {
            category = AnnouncementCategory.PRIVATE_MESSAGES;
            messageKey = "settings.recipient-private-messages-disabled";
            recipient = essentialsReplyRecipient(event.getPlayer());
        } else if (PAYMENTS.contains(root)) {
            category = AnnouncementCategory.PAYMENTS;
            messageKey = "settings.recipient-payments-disabled";
            recipient = parts.length < 2 ? null : Bukkit.getPlayerExact(parts[1]);
        } else if (TELEPORT_REQUESTS.contains(root)) {
            category = AnnouncementCategory.TELEPORT_REQUESTS;
            messageKey = "settings.recipient-teleport-requests-disabled";
            recipient = parts.length < 2 ? null : Bukkit.getPlayerExact(parts[1]);
        } else {
            return;
        }
        if (recipient == null || preferences.isEnabled(recipient.getUniqueId(), category)) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.get(event.getPlayer(), messageKey,
                "player", recipient.getName()));
    }

    /**
     * Essentials' reply target is intentionally read by reflection so this
     * listener remains optional-dependency safe. If Essentials changes its
     * internals or is absent, its normal reply handling remains untouched.
     */
    private static Player essentialsReplyRecipient(Player sender) {
        org.bukkit.plugin.Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
        if (essentials == null) {
            return null;
        }
        try {
            Object user;
            try {
                user = essentials.getClass().getMethod("getUser", UUID.class)
                        .invoke(essentials, sender.getUniqueId());
            } catch (NoSuchMethodException ignored) {
                user = essentials.getClass().getMethod("getUser", String.class)
                        .invoke(essentials, sender.getName());
            }
            if (user == null) {
                return null;
            }
            Object target = user.getClass().getMethod("getReplyRecipient").invoke(user);
            if (target instanceof UUID uuid) {
                return Bukkit.getPlayer(uuid);
            }
            if (target instanceof org.bukkit.OfflinePlayer player) {
                return player.getPlayer();
            }
            return target == null ? null : Bukkit.getPlayerExact(String.valueOf(target));
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
