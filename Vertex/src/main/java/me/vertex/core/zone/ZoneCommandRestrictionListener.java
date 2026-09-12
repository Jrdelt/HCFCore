package me.vertex.core.zone;

import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Blocks teleport and escape commands configured for the zone a player occupies. */
public final class ZoneCommandRestrictionListener implements Listener {
    private final ZoneManager zones;
    private final Messages messages;

    public ZoneCommandRestrictionListener(ZoneManager zones, Messages messages) {
        this.zones = zones;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("vertex.zones.command-bypass")) return;
        ZoneRegion region = zones.regionAt(player.getLocation());
        if (region == null) return;

        String raw = event.getMessage();
        if (raw == null || raw.length() < 2) return;
        String[] typed = raw.substring(1).trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (typed.length == 0 || typed[0].contains(":")) return;

        List<String> blocked = zones.blockedCommands(region.type());
        for (String configured : blocked) {
            String[] tokens = configured.toLowerCase(Locale.ROOT).trim().split("\\s+");
            if (matches(typed, tokens)) {
                event.setCancelled(true);
                player.sendMessage(messages.get(player, "zones.command-blocked", "zone", region.type().displayName()));
                return;
            }
        }
    }

    private static boolean matches(String[] typed, String[] blocked) {
        if (blocked.length == 0 || typed.length < blocked.length) return false;
        return Arrays.equals(Arrays.copyOf(typed, blocked.length), blocked);
    }
}
