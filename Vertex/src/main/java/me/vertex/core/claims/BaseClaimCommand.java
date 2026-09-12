package me.vertex.core.claims;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
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

/**
 * Owns Vertex's {@code /f baseclaim} view, the same "intercept one
 * native faction subcommand routing pattern
 * {@code FTopCommand}/{@code PvpTopCommand} use.
 *
 * <p>Standing on an existing Base Claim (or on Wilderness/SafeZone/WarZone,
 * where there is nothing to create) opens the info/removal GUI. Standing on
 * a plain (Raid Claim) claim belonging to the player's own faction attempts
 * to create a new anchor there instead.
 */
public final class BaseClaimCommand implements Listener {

    private final Plugin plugin;
    private final BaseClaimManager baseClaims;
    private final Messages messages;
    private final MenuRegistry menus;

    public BaseClaimCommand(Plugin plugin, BaseClaimManager baseClaims, Messages messages, MenuRegistry menus) {
        this.plugin = plugin;
        this.baseClaims = baseClaims;
        this.messages = messages;
        this.menus = menus;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBaseClaim(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2 || !isFactionCommand(event.getPlayer(), parts[0]) || !parts[1].equalsIgnoreCase("baseclaim")) {
            return;
        }
        event.setCancelled(true);
        handle(event.getPlayer());
    }

    /** Makes the native /f baseclaim branch visible in faction completions. */
    @EventHandler
    public void onFactionTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player player) || !event.getBuffer().startsWith("/")) return;
        String[] parts = event.getBuffer().substring(1).split("\\s+", -1);
        if (parts.length == 2 && isFactionCommand(player, parts[0])) {
            addCompletion(event, parts[1], "baseclaim");
        }
    }

    private static void addCompletion(TabCompleteEvent event, String partial, String value) {
        if (!value.startsWith(partial.toLowerCase(Locale.ROOT))) return;
        List<String> completions = new ArrayList<>(event.getCompletions());
        if (completions.stream().noneMatch(value::equalsIgnoreCase)) completions.add(value);
        event.setCompletions(completions);
    }

    private void handle(Player player) {
        if (!player.hasPermission("vertex.baseclaim.view")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.get(player, "baseclaim.no-faction"));
            return;
        }
        BaseClaimMenu.open(player, plugin, baseClaims, messages);
    }

    private boolean isFactionCommand(Player player, String command) {
        String normalized = command.contains(":") ? command.substring(command.indexOf(':') + 1) : command;
        return plugin.getConfig()
                .getStringList("factions.command-aliases").stream()
                .anyMatch(alias -> alias.equalsIgnoreCase(normalized));
    }
}
