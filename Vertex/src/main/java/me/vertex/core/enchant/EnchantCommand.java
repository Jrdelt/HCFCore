package me.vertex.core.enchant;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * {@code /enchant give <player> rune|gem <tier> [amount]} -- there is no
 * other admin distribution path for Lucky Gems (Runes are also
 * shop-purchasable; see {@code RuneShopMenu}), mirroring {@code
 * WandCommand}'s "no in-game shop" give command shape.
 */
public final class EnchantCommand implements CommandExecutor, TabCompleter {

    public static final String GIVE_PERMISSION = "vertex.enchant.give";

    private final EnchantManager manager;
    private final Messages messages;

    public EnchantCommand(EnchantManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(messages.get(sender, "enchant.usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        String kind = args[2].toLowerCase(Locale.ROOT);
        if (!kind.equals("rune") && !kind.equals("gem")) {
            sender.sendMessage(messages.get(sender, "enchant.usage"));
            return true;
        }

        int amount = 1;
        RuneTier tier = null;
        if (kind.equals("rune")) {
            if (args.length < 4) {
                sender.sendMessage(messages.get(sender, "enchant.usage"));
                return true;
            }
            tier = parseTier(args[3]);
            if (tier == null) {
                sender.sendMessage(messages.get(sender, "rune.unknown-tier"));
                return true;
            }
            if (args.length >= 5) {
                amount = parseAmount(args[4], sender);
                if (amount <= 0) {
                    return true;
                }
            }
        } else if (args.length >= 4) {
            amount = parseAmount(args[3], sender);
            if (amount <= 0) {
                return true;
            }
        }

        for (int i = 0; i < amount; i++) {
            ItemStack item = kind.equals("rune") ? manager.createRune(tier) : manager.createLuckyGem();
            target.getInventory().addItem(item).values()
                    .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
        }
        String kindLabel = kind.equals("rune") ? (tier.name() + " Rune") : "Lucky Gem";
        sender.sendMessage(messages.get(sender, "enchant.given", "player", target.getName(),
                "amount", String.valueOf(amount), "kind", kindLabel));
        return true;
    }

    private int parseAmount(String raw, CommandSender sender) {
        try {
            int amount = Integer.parseInt(raw);
            if (amount <= 0) {
                sender.sendMessage(messages.get(sender, "enchant.usage"));
                return -1;
            }
            return Math.min(amount, 64 * 6);
        } catch (NumberFormatException e) {
            sender.sendMessage(messages.get(sender, "enchant.usage"));
            return -1;
        }
    }

    private RuneTier parseTier(String raw) {
        try {
            return RuneTier.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            return List.of();
        }
        return switch (args.length) {
            case 1 -> "give".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("give") : List.of();
            case 2 -> Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
            case 3 -> List.of("rune", "gem").stream()
                    .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
            case 4 -> args[2].equalsIgnoreCase("rune")
                    ? java.util.Arrays.stream(RuneTier.values())
                            .map(tier -> tier.name().toLowerCase(Locale.ROOT))
                            .filter(name -> name.startsWith(args[3].toLowerCase(Locale.ROOT)))
                            .toList()
                    : List.of();
            default -> List.of();
        };
    }
}
