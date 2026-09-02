package me.hcfcore.core.kit;

import me.hcfcore.core.lang.Messages;
import org.bukkit.Material;
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
    private final Messages messages;

    public KitCommand(KitManager kitManager, Messages messages) {
        this.kitManager = kitManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(messages.get(player, "kit.usage"));
            return true;
        }

        if (args[0].equalsIgnoreCase("delete")) {
            if (!player.hasPermission("hcfcore.kit.delete")) {
                player.sendMessage(messages.get(player, "general.no-permission"));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(messages.get(player, "kit.usage-delete"));
                return true;
            }
            if (kitManager.delete(args[1])) {
                player.sendMessage(messages.get(player, "kit.deleted", "kit", args[1]));
            } else {
                player.sendMessage(messages.get(player, "kit.not-found", "kit", args[1]));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("save")) {
            if (!player.hasPermission("hcfcore.kit.save")) {
                player.sendMessage(messages.get(player, "general.no-permission"));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(messages.get(player, "kit.usage-save"));
                return true;
            }
            String name = args[1];
            String permission = args.length > 2 ? args[2] : "hcfcore.kit." + name.toLowerCase(Locale.ROOT);
            int cooldown = args.length > 3 ? parseIntOrDefault(args[3], 0) : 0;
            double money = args.length > 4 ? parseDoubleOrDefault(args[4], 0.0) : 0.0;
            Kit.Cost cost = args.length > 5 ? parseCostItem(args[5], money) : new Kit.Cost(money, null, 0);
            kitManager.save(name, player, permission, cooldown, cost);
            player.sendMessage(messages.get(player, "kit.saved", "kit", name));
            return true;
        }

        Kit kit = kitManager.get(args[0]);
        if (kit == null) {
            player.sendMessage(messages.get(player, "kit.not-found", "kit", args[0]));
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

    private static double parseDoubleOrDefault(String input, double fallback) {
        try {
            return Double.parseDouble(input);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Parses a "costItem[:amount]" token, e.g. "DIAMOND" or "DIAMOND:4".
     * An unrecognized material silently drops the item cost rather than
     * failing the whole save.
     */
    private static Kit.Cost parseCostItem(String token, double money) {
        String[] parts = token.split(":", 2);
        Material material;
        try {
            material = Material.valueOf(parts[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return new Kit.Cost(money, null, 0);
        }
        int amount = parts.length > 1 ? parseIntOrDefault(parts[1], 1) : 1;
        return new Kit.Cost(money, material, Math.max(1, amount));
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
