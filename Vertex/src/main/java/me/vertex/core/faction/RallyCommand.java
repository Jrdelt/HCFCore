package me.vertex.core.faction;

import dev.kitteh.factions.command.ThirdPartyCommands;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class RallyCommand implements CommandExecutor, Listener {

    private final Plugin plugin;
    private final RallyManager rallyManager;
    private final Messages messages;

    public RallyCommand(Plugin plugin, RallyManager rallyManager, Messages messages) {
        this.plugin = plugin;
        this.rallyManager = rallyManager;
        this.messages = messages;
    }

    /**
     * Adds the Vertex rally branch to FactionsUUID's Paper command tree.
     * This must be called from the dependent plugin's onLoad(), while
     * FactionsUUID still accepts third-party commands. Registering only a
     * PlayerCommandPreprocessEvent lets the command run, but cannot stop the
     * Minecraft client from colouring the unknown `rally` literal red.
     */
    public static void registerFactionsSubcommand(Plugin plugin, Supplier<RallyCommand> commandSupplier) {
        ThirdPartyCommands.register(plugin, "rally", (manager, root, help) -> {
            manager.command(root.literal("rally")
                    .handler(context -> executeRegistered(commandSupplier, context.sender().sender(), new String[0])));
            manager.command(root.literal("rally").literal("set")
                    .handler(context -> executeRegistered(commandSupplier, context.sender().sender(), new String[]{"set"})));
            manager.command(root.literal("rally").literal("clear")
                    .handler(context -> executeRegistered(commandSupplier, context.sender().sender(), new String[]{"clear"})));
        });
    }

    private static void executeRegistered(Supplier<RallyCommand> commandSupplier, CommandSender sender, String[] args) {
        RallyCommand command = commandSupplier.get();
        if (command != null) {
            command.execute(sender, args);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    private boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(messages.getChat(sender, "general.players-only"));
            return true;
        }

        return execute((Player) sender, args);
    }

    /**
     * FactionsUUID owns the `/f` root command. A YAML alias containing a
     * space is not a real Bukkit subcommand, so route its `rally` branch
     * before FactionsUUID handles it, just like permissions and upgrades.
     */
    @EventHandler
    public void onFactionRallyCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2 || !isFactionCommand(parts[0]) || !parts[1].equalsIgnoreCase("rally")) {
            return;
        }
        event.setCancelled(true);
        String[] args = java.util.Arrays.copyOfRange(parts, 2, parts.length);
        execute(event.getPlayer(), args);
    }

    @EventHandler
    public void onFactionRallyTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player) || !event.getBuffer().startsWith("/")) {
            return;
        }
        String[] parts = event.getBuffer().substring(1).split("\\s+", -1);
        if (parts.length == 2 && isFactionCommand(parts[0])) {
            addCompletions(event, parts[1], List.of("rally"));
        } else if (parts.length == 3 && isFactionCommand(parts[0]) && parts[1].equalsIgnoreCase("rally")) {
            addCompletions(event, parts[2], List.of("set", "clear"));
        }
    }

    private boolean execute(Player player, String[] args) {
        int factionId = FactionsHook.getFactionId(player);

        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.getChat(player, "factions.must-be-in-faction"));
            return true;
        }

        if (args.length == 0) {
            if (!rallyManager.canUse(player, "rally-set")) {
                player.sendMessage(messages.getChat(player, "factions.rally-no-permission"));
                return true;
            }
            // /rally with no args - set rally at current location
            rallyManager.setRally(factionId, player.getLocation());
            player.sendMessage(messages.getChat(player, "factions.rally-set"));
            rallyManager.broadcastSet(player);
            return true;
        }

        String action = args[0].toLowerCase();

        if (action.equals("set")) {
            if (!rallyManager.canUse(player, "rally-set")) {
                player.sendMessage(messages.getChat(player, "factions.rally-no-permission"));
                return true;
            }
            rallyManager.setRally(factionId, player.getLocation());
            player.sendMessage(messages.getChat(player, "factions.rally-set"));
            rallyManager.broadcastSet(player);
            return true;
        } else if (action.equals("clear")) {
            if (!rallyManager.canUse(player, "rally-clear")) {
                player.sendMessage(messages.getChat(player, "factions.rally-no-permission"));
                return true;
            }
            rallyManager.clearRally(factionId);
            player.sendMessage(messages.getChat(player, "factions.rally-cleared"));
            return true;
        }

        player.sendMessage(messages.getChat(player, "factions.rally-usage"));
        return true;
    }

    private boolean isFactionCommand(String rawCommand) {
        String command = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;
        int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return plugin.getConfig().getStringList("factions.command-aliases").stream()
                .map(alias -> alias.toLowerCase(Locale.ROOT)).anyMatch(normalized::equals);
    }

    private static void addCompletions(TabCompleteEvent event, String rawPartial, List<String> values) {
        String partial = rawPartial.toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>(event.getCompletions());
        for (String value : values) {
            if (value.startsWith(partial) && completions.stream().noneMatch(value::equalsIgnoreCase)) {
                completions.add(value);
            }
        }
        event.setCompletions(completions);
    }
}
