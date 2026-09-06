package me.vertex.core.staff;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.vertex.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Redirects chat to a staff-only channel for players with staffchat mode
 * on. Runs at LOWEST, before {@code ChatFormatterListener} (which is
 * HIGHEST), so cancelling here skips normal formatting/delivery entirely --
 * and deliberately without {@code ignoreCancelled}, so an unrelated earlier
 * cancellation (a mute filter, a spam filter, etc.) can never silently
 * swallow a staff member's message with no indication staffchat mode
 * specifically ate it. Staff chat is a separate, private channel and
 * should work regardless of what public-chat restrictions apply.
 */
public final class StaffChatListener implements Listener {

    private final StaffManager staffManager;

    public StaffChatListener(StaffManager staffManager) {
        this.staffManager = staffManager;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!staffManager.isStaffChat(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);

        // The player's own message is appended as a Component rather than
        // interpolated into the MiniMessage string below, so nothing in it
        // (e.g. "<red>") is parsed as markup.
        Component formatted = MessageFormatter.deserialize("&c&lSTAFF&r &7> &e" + player.getName() + "&7: &f")
                .append(event.message());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.hasPermission("vertex.staff.staffchat")) {
                viewer.sendMessage(formatted);
            }
        }
        Bukkit.getConsoleSender().sendMessage(formatted);
    }
}
