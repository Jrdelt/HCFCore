package me.vertex.core.shop;

import me.vertex.core.economy.EconomyHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Vertex-owned /sell hand and /sell all, independent of Essentials. */
public final class SellCommand implements CommandExecutor, TabCompleter {
    private final ShopManager shops;
    private final Messages messages;

    public SellCommand(ShopManager shops, Messages messages) {
        this.shops = shops;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        return execute(player, args);
    }

    /** Direct entry point used by Vertex's command-precedence listener. */
    public boolean execute(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(messages.get(player, "shop.sell-usage"));
            return true;
        }
        if (args[0].equalsIgnoreCase("hand")) {
            ShopManager.TradeOutcome outcome = shops.sellSlot(player, player.getInventory().getHeldItemSlot());
            if (outcome.result() != ShopManager.TradeResult.OK) {
                player.sendMessage(messages.get(player,
                        outcome.result() == ShopManager.TradeResult.STORAGE_UNAVAILABLE
                                ? "delivery.storage-unavailable"
                                : outcome.result() == ShopManager.TradeResult.NO_ECONOMY
                                        ? "shop.trade-failed" : "shop.sell-nothing"));
                return true;
            }
            player.sendMessage(messages.get(player, "shop.sell-summary",
                    "total", EconomyHook.format(outcome.total())));
            return true;
        }
        if (!args[0].equalsIgnoreCase("all")) {
            player.sendMessage(messages.get(player, "shop.sell-usage"));
            return true;
        }

        // Only plain vanilla stacks are eligible. Custom/PDC items sharing a
        // material with a shop entry stay untouched.
        Map<Material, Integer> amounts = new EnumMap<>(Material.class);
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (ShopManager.isPlainStack(item) && shops.isTradeable(item.getType())) {
                amounts.merge(item.getType(), item.getAmount(), Integer::sum);
            }
        }
        double total = 0D;
        for (Map.Entry<Material, Integer> entry : amounts.entrySet()) {
            ShopManager.TradeOutcome outcome = shops.sell(player, entry.getKey(), entry.getValue());
            if (outcome.result() == ShopManager.TradeResult.OK) total += outcome.total();
        }
        if (total <= 0D) {
            player.sendMessage(messages.get(player, "shop.sell-nothing"));
        } else {
            player.sendMessage(messages.get(player, "shop.sell-summary", "total", EconomyHook.format(total)));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String typed = args[0].toLowerCase(Locale.ROOT);
        return List.of("all", "hand").stream().filter(value -> value.startsWith(typed)).toList();
    }
}
