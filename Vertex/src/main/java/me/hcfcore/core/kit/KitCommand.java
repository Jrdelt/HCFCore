package me.hcfcore.core.kit;

import me.hcfcore.core.lang.Messages;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class KitCommand implements CommandExecutor, TabCompleter {

    private final KitManager kitManager;
    private final Messages messages;
    private final Plugin plugin;

    public KitCommand(Plugin plugin, KitManager kitManager, Messages messages) {
        this.plugin = plugin;
        this.kitManager = kitManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.getChat(sender, "general.players-only"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(messages.getChat(player, "kit.usage"));
            return true;
        }

        if (args[0].equalsIgnoreCase("delete")) {
            if (!player.hasPermission("hcfcore.kit.delete")) {
                player.sendMessage(messages.getChat(player, "general.no-permission"));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(messages.getChat(player, "kit.usage-delete"));
                return true;
            }
            if (kitManager.delete(args[1])) {
                player.sendMessage(messages.getChat(player, "kit.deleted", "kit", args[1]));
            } else {
                player.sendMessage(messages.getChat(player, "kit.not-found", "kit", args[1]));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("create") || args[0].equalsIgnoreCase("save")) {
            if (!player.hasPermission("hcfcore.kit.create")
                    && !player.hasPermission("hcfcore.kit.save")) {
                player.sendMessage(messages.getChat(player, "general.no-permission"));
                return true;
            }
            if (args.length < 2) {
                player.sendMessage(messages.getChat(player, "kit.usage-create"));
                return true;
            }
            String name = args[1];
            String permission = args.length > 2 ? args[2] : "hcfcore.kit." + name.toLowerCase(Locale.ROOT);
            int maxCooldown = Math.max(0, plugin.getConfig().getInt("kits.max-cooldown-seconds", 86400));
            Integer cooldown = 0;
            if (args.length > 3) {
                cooldown = parseIntInRange(args[3], 0, maxCooldown);
            }
            double maxMoney = Math.max(0.0, plugin.getConfig().getDouble("kits.max-money-cost", 1_000_000_000.0));
            Double money = 0.0;
            if (args.length > 4) {
                money = parseDoubleInRange(args[4], 0.0, maxMoney);
            }
            if (cooldown == null || money == null) {
                player.sendMessage(messages.getChat(player, "kit.usage-create"));
                return true;
            }
            int maxItemAmount = Math.max(1, plugin.getConfig().getInt("kits.max-cost-item-amount", 64));
            Kit.Cost cost = args.length > 5
                    ? parseCostItem(args[5], money, maxItemAmount)
                    : new Kit.Cost(money, null, 0);
            if (cost == null) {
                player.sendMessage(messages.getChat(player, "kit.usage-create"));
                return true;
            }
            kitManager.save(name, player, permission, cooldown, cost);
            player.sendMessage(messages.getChat(player, "kit.saved", "kit", name));
            return true;
        }

        Kit kit = kitManager.get(args[0]);
        if (kit == null) {
            player.sendMessage(messages.getChat(player, "kit.not-found", "kit", args[0]));
            return true;
        }

        kitManager.apply(player, kit);
        return true;
    }

    private static Integer parseIntInRange(String input, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(input);
            return value >= minimum && value <= maximum ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDoubleInRange(String input, double minimum, double maximum) {
        try {
            double value = Double.parseDouble(input);
            return Double.isFinite(value) && value >= minimum && value <= maximum ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a "costItem[:amount]" token, e.g. "DIAMOND" or "DIAMOND:4".
    * An unrecognized material rejects the whole save rather than silently
    * dropping the item cost.
     */
    private static Kit.Cost parseCostItem(String token, double money, int maximumAmount) {
        String[] parts = token.split(":", 2);
        Material material;
        try {
            material = Material.valueOf(parts[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        int amount = 1;
        if (parts.length > 1) {
            Integer parsed = parseIntInRange(parts[1], 1, maximumAmount);
            if (parsed == null) {
                return null;
            }
            amount = parsed;
        }
        return new Kit.Cost(money, material, amount);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(kitManager.getKits().keySet());
        options.add("create");
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
