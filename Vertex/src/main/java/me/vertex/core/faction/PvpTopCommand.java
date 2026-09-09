package me.vertex.core.faction;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

/** Routes only `/f pvptop`; FactionsUUID continues to own the rest of `/f`. */
public final class PvpTopCommand implements Listener {
    private final Plugin plugin;
    private final PvpTopManager manager;
    private final Messages messages;

    public PvpTopCommand(Plugin plugin, PvpTopManager manager, Messages messages) {
        this.plugin = plugin; this.manager = manager; this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length != 2 || !isFactionCommand(parts[0]) || !parts[1].equalsIgnoreCase("pvptop")) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(messages.get(event.getPlayer(), "pvptop.header"));
        var entries = manager.leaderboard();
        if (entries.isEmpty()) { event.getPlayer().sendMessage(messages.get(event.getPlayer(), "pvptop.empty")); return; }
        int rank = 1;
        for (PvpTopManager.Entry entry : entries) {
            event.getPlayer().sendMessage(messages.get(event.getPlayer(), "pvptop.entry", "rank", String.valueOf(rank++),
                    "faction", FactionsHook.getFactionName(entry.factionId()), "points", String.valueOf(entry.points())));
        }
    }

    private boolean isFactionCommand(String command) {
        String normalized = command.contains(":") ? command.substring(command.indexOf(':') + 1) : command;
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .anyMatch(alias -> alias.equalsIgnoreCase(normalized));
    }
}
