package me.vertex.core.command;

import me.vertex.core.backpack.BackpackFilterCommand;
import me.vertex.core.shop.SellCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Arrays;
import java.util.Locale;

/** Ensures Vertex owns commands that commonly collide with Essentials. */
public final class VertexCommandPrecedenceListener implements Listener {
    private final BackpackFilterCommand filters;
    private final SellCommand sell;

    public VertexCommandPrecedenceListener(BackpackFilterCommand filters, SellCommand sell) {
        this.filters = filters;
        this.sell = sell;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || raw.length() < 2) return;
        String[] split = raw.substring(1).trim().split("\\s+");
        if (split.length == 0 || split[0].contains(":")) return;
        String root = split[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(split, 1, split.length);
        if (root.equals("filter")) {
            event.setCancelled(true);
            filters.execute(event.getPlayer(), args);
        } else if (root.equals("sell")) {
            event.setCancelled(true);
            sell.execute(event.getPlayer(), args);
        }
    }
}
