package me.vertex.core.shop;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import me.vertex.core.spawner.SpawnerManager;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /shop}: opens the browser, or trades directly via {@code /shop buy|sell <block> <amount>}. */
public final class ShopCommand implements CommandExecutor, TabCompleter {

    /**
     * totalBuyCost/totalSellPayout loop once per unit (compounding the
     * price a step at a time) -- an unbounded amount here is a main-thread
     * hang, not just a large trade, so this caps it well below anything
     * that could ever be a legitimate single trade.
     */
    private static final int MAX_TRADE_AMOUNT = 10_000;

    private final ShopManager manager;
    private final SpawnerManager spawnerManager;
    private final Messages messages;

    public ShopCommand(ShopManager manager, SpawnerManager spawnerManager, Messages messages) {
        this.manager = manager;
        this.spawnerManager = spawnerManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!manager.isEnabled()) {
            player.sendMessage(messages.get(player, "shop.disabled"));
            return true;
        }
        if (args.length == 0) {
            ShopMenu.openCategories(player, manager, spawnerManager, messages);
            return true;
        }
        if (args.length < 2 || !(args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("sell"))) {
            player.sendMessage(messages.get(player, "shop.command-usage"));
            return true;
        }

        Material material = Material.matchMaterial(args[1].toUpperCase(Locale.ROOT));
        if (material == null || !manager.isTradeable(material)) {
            player.sendMessage(messages.get(player, "shop.unknown-block"));
            return true;
        }
        int amount = manager.defaultBuyAmount();
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(messages.get(player, "shop.command-usage"));
                return true;
            }
        }
        if (amount <= 0 || amount > MAX_TRADE_AMOUNT) {
            player.sendMessage(messages.get(player, "shop.command-usage"));
            return true;
        }

        boolean buying = args[0].equalsIgnoreCase("buy");
        ShopManager.TradeOutcome outcome = buying ? manager.buy(player, material, amount) : manager.sell(player, material, amount);
        if (outcome.result() != ShopManager.TradeResult.OK) {
            player.sendMessage(messages.get(player, failureKey(buying, outcome.result())));
            return true;
        }
        player.sendMessage(messages.get(player, buying ? "shop.bought" : "shop.sold",
                "amount", String.valueOf(amount), "block", material.name(), "total", EconomyHook.format(outcome.total())));
        return true;
    }

    private static String failureKey(boolean buying, ShopManager.TradeResult result) {
        return switch (result) {
            case DISABLED -> "shop.disabled";
            case UNKNOWN_BLOCK -> "shop.unknown-block";
            case NO_ECONOMY -> "spawner.no-economy";
            case CANNOT_AFFORD -> "shop.cannot-afford";
            case NOT_ENOUGH_ITEMS -> "shop.not-enough-items";
            case STORAGE_UNAVAILABLE -> "delivery.storage-unavailable";
            case OK -> buying ? "shop.bought" : "shop.sold";
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String option : List.of("buy", "sell")) {
                if (option.startsWith(partial)) {
                    matches.add(option);
                }
            }
            return matches;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("buy") || args[0].equalsIgnoreCase("sell"))) {
            String partial = args[1].toUpperCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (ShopEntry entry : manager.entries()) {
                if (entry.material().name().startsWith(partial)) {
                    matches.add(entry.material().name());
                }
            }
            return matches;
        }
        return List.of();
    }
}
