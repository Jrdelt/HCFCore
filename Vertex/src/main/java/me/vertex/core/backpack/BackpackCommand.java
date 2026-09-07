package me.vertex.core.backpack;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /backpack give <player> <tier> [level]} -- hands out a Backpack (there's no in-game shop for these). */
public final class BackpackCommand implements CommandExecutor, TabCompleter {

    private final BackpackManager manager;
    private final Messages messages;
    private final BackpackInteractListener interactListener;

    public BackpackCommand(BackpackManager manager, Messages messages, BackpackInteractListener interactListener) {
        this.manager = manager;
        this.messages = messages;
        this.interactListener = interactListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messages.get(sender, "general.players-only"));
                return true;
            }
            if (!sender.hasPermission("vertex.backpack.debug")) {
                sender.sendMessage(messages.get(sender, "general.no-permission"));
                return true;
            }
            boolean enabled = interactListener.toggleDebug(player.getUniqueId());
            player.sendMessage(messages.get(player, enabled ? "backpack.debug-enabled" : "backpack.debug-disabled"));
            return true;
        }
        if (!sender.hasPermission("vertex.backpack.give")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (!manager.isEnabled()) {
            sender.sendMessage(messages.get(sender, "backpack.disabled"));
            return true;
        }
        if ((args.length != 3 && args.length != 4) || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(messages.get(sender, "backpack.command-usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        BackpackTier tier = manager.getTier(args[2]);
        if (tier == null) {
            sender.sendMessage(messages.get(sender, "backpack.unknown-tier"));
            return true;
        }

        int level = 1;
        if (args.length == 4) {
            try {
                level = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(messages.get(sender, "backpack.invalid-level"));
                return true;
            }
            if (level < 1) {
                sender.sendMessage(messages.get(sender, "backpack.invalid-level"));
                return true;
            }
        }

        ItemStack item = manager.createBackpackItem(tier, level);
        if (item == null) {
            sender.sendMessage(messages.get(sender, "backpack.invalid-item-type"));
            return true;
        }
        for (ItemStack dropped : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), dropped);
        }
        sender.sendMessage(messages.get(sender, "backpack.gave", "player", target.getName(),
                "tier", tier.displayName(), "level", String.valueOf(level)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> result = new ArrayList<>();
            if ("give".startsWith(partial)) {
                result.add("give");
            }
            if ("debug".startsWith(partial) && sender.hasPermission("vertex.backpack.debug")) {
                result.add("debug");
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> matches = new ArrayList<>();
            String partial = args[1].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    matches.add(player.getName());
                }
            }
            return matches;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String tierId : manager.tierIds()) {
                if (tierId.startsWith(partial)) {
                    matches.add(tierId);
                }
            }
            return matches;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            BackpackTier tier = manager.getTier(args[2]);
            return tier == null ? List.of() : List.of("1");
        }
        return List.of();
    }
}
