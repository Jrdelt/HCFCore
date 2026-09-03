package me.hcfcore.core.faction;

import me.hcfcore.core.factions.FactionsHook;
import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RallyCommand implements CommandExecutor {

    private final RallyManager rallyManager;
    private final Messages messages;

    public RallyCommand(RallyManager rallyManager, Messages messages) {
        this.rallyManager = rallyManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("Only players can use this command").color(NamedTextColor.RED));
            return true;
        }

        if (!sender.hasPermission("hcfcore.faction.rally")) {
            sender.sendMessage(messages.getChat(sender, "general.no-permission"));
            return true;
        }

        Player player = (Player) sender;
        int factionId = FactionsHook.getFactionId(player);

        if (factionId == FactionsHook.NO_FACTION) {
            sender.sendMessage(Component.text("You must be in a faction to set a rally").color(NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /" + label + " set|clear").color(NamedTextColor.RED));
            return true;
        }

        String action = args[0].toLowerCase();

        if (action.equals("set")) {
            rallyManager.setRally(factionId, player.getLocation());
            sender.sendMessage(Component.text("Rally set at your location!").color(NamedTextColor.GREEN));
            return true;
        } else if (action.equals("clear")) {
            rallyManager.clearRally(factionId);
            sender.sendMessage(Component.text("Rally cleared!").color(NamedTextColor.GREEN));
            return true;
        }

        sender.sendMessage(Component.text("Usage: /" + label + " set|clear").color(NamedTextColor.RED));
        return true;
    }
}
