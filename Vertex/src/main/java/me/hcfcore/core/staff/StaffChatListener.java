package me.hcfcore.core.staff;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.hcfcore.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Redirects chat to a staff-only channel for players with staffchat mode
 * on. Runs before {@code ChatFormatterListener} (which is HIGHEST) so
 * cancelling here skips normal formatting/delivery entirely.
 */
public final class StaffChatListener implements Listener {

    private final StaffManager staffManager;

    public StaffChatListener(StaffManager staffManager) {
        this.staffManager = staffManager;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!staffManager.isStaffChat(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);

        // The player's own message is appended as a Component rather than
        // interpolated into the MiniMessage string below, so nothing in it
        // (e.g. "<red>") is parsed as markup.
        Component formatted = MessageFormatter.deserialize("&c&lSTAFF &7> &e" + player.getName() + "&7: &f")
                .append(event.message());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.hasPermission("hcfcore.staff.staffchat")) {
                viewer.sendMessage(formatted);
            }
        }
        Bukkit.getConsoleSender().sendMessage(formatted);
    }
}
