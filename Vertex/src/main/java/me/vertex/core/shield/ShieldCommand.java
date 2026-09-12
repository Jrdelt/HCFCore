package me.vertex.core.shield;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.grace.DurationParser;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native player-facing /f shield schedule and activation branch. */
public final class ShieldCommand implements Listener {
    private final Plugin plugin;
    private final ShieldManager shield;
    private final Messages messages;
    private final ShieldScheduleMenu menu;

    public ShieldCommand(Plugin plugin, ShieldManager shield, Messages messages, ShieldScheduleMenu menu) {
        this.plugin = plugin; this.shield = shield; this.messages = messages; this.menu = menu;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2 || !isFactionCommand(parts[0]) || !parts[1].equalsIgnoreCase("shield")) return;
        event.setCancelled(true);
        handle(event.getPlayer(), parts);
    }

    @EventHandler
    public void onTab(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player) || !event.getBuffer().startsWith("/")) return;
        String[] parts = event.getBuffer().substring(1).split("\\s+", -1);
        if (parts.length == 2 && isFactionCommand(parts[0])) add(event, parts[1], List.of("shield"));
        else if (parts.length == 3 && isFactionCommand(parts[0]) && parts[1].equalsIgnoreCase("shield")) {
            add(event, parts[2], List.of("activate"));
        }
    }

    private void handle(Player player, String[] parts) {
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) { player.sendMessage(messages.get(player, "shield.no-faction")); return; }
        if (parts.length >= 3 && parts[2].equalsIgnoreCase("activate")) {
            if (!player.hasPermission("vertex.shield.activate")) {
                player.sendMessage(messages.get(player, "general.no-permission")); return;
            }
            var service = FactionsHook.service();
            var member = service.member(player.getUniqueId());
            if (member == null || (member.role() != me.vertex.core.factions.FactionRole.LEADER
                    && member.role() != me.vertex.core.factions.FactionRole.COLEADER)) {
                player.sendMessage(messages.get(player, "shield.rank-blocked")); return;
            }
            ShieldManager.ActivateResult result = shield.activate(factionId, player.getUniqueId());
            switch (result) {
                case OK -> player.sendMessage(messages.get(player, "shield.activated", "time",
                        DurationParser.format(shield.secondsUntilDeactivation(factionId))));
                case ALREADY_ACTIVE -> player.sendMessage(messages.get(player, "shield.already-active"));
                case COOLDOWN, NOT_ELIGIBLE -> player.sendMessage(messages.get(player, "shield.cooldown", "time",
                        DurationParser.format(shield.secondsUntilAvailable(factionId))));
                case NO_PERMISSION -> player.sendMessage(messages.get(player, "shield.rank-blocked"));
                case STORAGE_ERROR -> player.sendMessage(messages.get(player, "shield.persist-failed"));
            }
            return;
        }
        menu.open(player);
    }

    private boolean isFactionCommand(String command) {
        String normalized = command.contains(":") ? command.substring(command.indexOf(':') + 1) : command;
        return plugin.getConfig().getStringList("factions.command-aliases").stream().anyMatch(a -> a.equalsIgnoreCase(normalized));
    }

    private static void add(TabCompleteEvent event, String partial, List<String> values) {
        String prefix = partial.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>(event.getCompletions());
        values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix))
                .filter(v -> result.stream().noneMatch(v::equalsIgnoreCase)).forEach(result::add);
        event.setCompletions(result);
    }
}
