package me.hcfcore.core.kit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class KitCommand implements CommandExecutor, TabCompleter {

    private final KitManager kitManager;

    public KitCommand(KitManager kitManager) {
        this.kitManager = kitManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /kit <name> | /kit save <name> | /kit delete <name>", NamedTextColor.RED));
            return true;
        }

        if (args[0].equalsIgnoreCase("delete")) {
            if (!player.hasPermission("hcfcore.kit.delete")) {
                player.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /kit delete <name>", NamedTextColor.RED));
                return true;
            }
            if (kitManager.delete(args[1])) {
                player.sendMessage(Component.text("Deleted kit " + args[1] + ".", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("No kit named '" + args[1] + "'.", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("save")) {
            if (!player.hasPermission("hcfcore.kit.save")) {
                player.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /kit save <name> [permission] [cooldownSeconds]", NamedTextColor.RED));
                return true;
            }
            String name = args[1];
            String permission = args.length > 2 ? args[2] : "hcfcore.kit." + name.toLowerCase(Locale.ROOT);
            int cooldown = args.length > 3 ? parseIntOrDefault(args[3], 0) : 0;
            kitManager.save(name, player, permission, cooldown);
            player.sendMessage(Component.text("Saved kit " + name + " from your current inventory.", NamedTextColor.GREEN));
            return true;
        }

        Kit kit = kitManager.get(args[0]);
        if (kit == null) {
            player.sendMessage(Component.text("No kit named '" + args[0] + "'.", NamedTextColor.RED));
            return true;
        }

        kitManager.apply(player, kit);
        return true;
    }

    private static int parseIntOrDefault(String input, int fallback) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(kitManager.getKits().keySet());
        options.add("save");
        options.add("delete");
        List<String> matches = new ArrayList<>();
        String partial = args[0].toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.startsWith(partial)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
