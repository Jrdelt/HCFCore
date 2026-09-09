package me.vertex.core.event;

import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
import me.vertex.core.mine.HotZoneManager;
import me.vertex.core.mine.MineKothManager;
import me.vertex.core.mine.MineManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Opens the player-facing hub for the currently available Vertex events. */
public final class EventsCommand implements CommandExecutor {

    private final MineManager mines;
    private final MineKothManager koths;
    private final HotZoneManager hotZones;
    private final Messages messages;
    private final MenuRegistry menus;

    public EventsCommand(MineManager mines, MineKothManager koths, HotZoneManager hotZones,
            Messages messages, MenuRegistry menus) {
        this.mines = mines;
        this.koths = koths;
        this.hotZones = hotZones;
        this.messages = messages;
        this.menus = menus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        EventsMenu.open(player, mines, koths, hotZones, messages, menus);
        return true;
    }
}
