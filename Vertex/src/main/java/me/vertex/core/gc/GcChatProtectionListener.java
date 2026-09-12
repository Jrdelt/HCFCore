package me.vertex.core.gc;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.vertex.core.lang.Messages;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Prevents an accidental public paste of a redeemable store-GC code. */
public final class GcChatProtectionListener implements Listener {
    private static final long APPROVAL_MILLIS = 15_000L;

    private final Plugin plugin;
    private final GcManager manager;
    private final Messages messages;
    private final Map<UUID, Long> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> approved = new ConcurrentHashMap<>();

    public GcChatProtectionListener(Plugin plugin, GcManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long allowedUntil = approved.remove(player.getUniqueId());
        if (allowedUntil != null && allowedUntil >= now) return;

        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (!containsUnescapedCode(plain)) return;

        event.setCancelled(true);
        pending.put(player.getUniqueId(), now + APPROVAL_MILLIS);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) player.sendMessage(messages.get(player, "gc.chat-code-warning"));
        });
    }

    public void approve(Player player) {
        long now = System.currentTimeMillis();
        Long pendingUntil = pending.remove(player.getUniqueId());
        if (pendingUntil == null || pendingUntil < now) {
            player.sendMessage(messages.get(player, "gc.chat-approval-expired"));
            return;
        }
        approved.put(player.getUniqueId(), now + APPROVAL_MILLIS);
        player.sendMessage(messages.get(player, "gc.chat-approved"));
    }

    boolean containsUnescapedCode(String message) {
        if (message == null) return false;
        for (int length : manager.recognizedCodeLengths()) {
        for (int start = 0; start + length <= message.length(); start++) {
            int end = start + length;
            if (start > 0 && Character.isLetterOrDigit(message.charAt(start - 1))) continue;
            if (end < message.length() && Character.isLetterOrDigit(message.charAt(end))) continue;
            if (start > 0 && message.charAt(start - 1) == '\\') continue;
            if (manager.isRedeemCodeCandidate(message.substring(start, end))) return true;
        }
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        manager.refreshBalance(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        pending.remove(playerId);
        approved.remove(playerId);
    }
}
