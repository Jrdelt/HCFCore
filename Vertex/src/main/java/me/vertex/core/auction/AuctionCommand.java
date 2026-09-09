package me.vertex.core.auction;

import me.vertex.core.lang.Messages;
import me.vertex.core.util.Numbers;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** {@code /ah} (alias {@code /auctionhouse}): browse, list, cancel, collect returned items, or read the staff log. */
public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private final AuctionManager manager;
    private final Messages messages;

    public AuctionCommand(AuctionManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!manager.isEnabled()) {
            player.sendMessage(messages.get(player, "auction.disabled"));
            return true;
        }
        if (args.length == 0) {
            AuctionMenu.openBrowse(player, manager, messages, 0);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> handleSell(player, args);
            case "cancel" -> handleCancel(player, args);
            case "collect" -> AuctionMenu.openClaim(player, manager, messages);
            case "logs" -> handleLogs(player, args);
            default -> sendUsage(player);
        }
        return true;
    }

    private void handleSell(Player player, String[] args) {
        if (args.length < 2) {
            sendUsage(player);
            return;
        }
        Double price = Numbers.parseDoublePositive(args[1]);
        if (price == null) {
            sendUsage(player);
            return;
        }
        AuctionCurrency currency = AuctionCurrency.MONEY;
        if (args.length >= 3) {
            if (isExperienceCurrency(args[2])) {
                currency = AuctionCurrency.EXP;
            } else if (!args[2].equalsIgnoreCase("money")) {
                sendUsage(player);
                return;
            }
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            player.sendMessage(messages.get(player, "auction.empty-hand"));
            return;
        }
        ItemStack toList = held.clone();
        player.getInventory().setItemInMainHand(null);

        AuctionManager.ListOutcome outcome = manager.list(player, toList, price, currency);
        if (outcome.result() != AuctionManager.ListResult.OK) {
            // Hand it back -- nothing was actually listed.
            player.getInventory().addItem(toList).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            player.sendMessage(messages.get(player, listFailureKey(outcome.result())));
            return;
        }
        player.sendMessage(messages.get(player, "auction.listed"));
    }

    private void handleCancel(Player player, String[] args) {
        if (args.length < 2) {
            sendUsage(player);
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sendUsage(player);
            return;
        }
        AuctionListing listing = manager.getListing(id);
        if (listing == null) {
            player.sendMessage(messages.get(player, "auction.gone"));
            return;
        }
        if (!manager.canCancel(player, listing)) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        boolean cancelled = manager.cancel(listing, player);
        player.sendMessage(messages.get(player, cancelled ? "auction.cancelled" : "auction.gone"));
    }

    private void handleLogs(Player player, String[] args) {
        if (!player.hasPermission("vertex.auction.logs")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        UUID filter = null;
        int page = 0;
        if (args.length >= 2) {
            try {
                page = Math.max(0, Integer.parseInt(args[1]) - 1);
            } catch (NumberFormatException e) {
                filter = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                if (args.length >= 3) {
                    try {
                        page = Math.max(0, Integer.parseInt(args[2]) - 1);
                    } catch (NumberFormatException ignored) {
                        // Fall back to page 1.
                    }
                }
            }
        }
        int pageSize = 8;
        List<AuctionLogEntry> entries = manager.loadLog(filter, pageSize, page * pageSize);
        if (entries.isEmpty()) {
            player.sendMessage(messages.get(player, "auction.logs-empty"));
            return;
        }
        player.sendMessage(messages.get(player, "auction.logs-header", "page", String.valueOf(page + 1)));
        for (AuctionLogEntry entry : entries) {
            String seller = nameOf(entry.sellerUuid());
            String buyer = entry.buyerUuid() == null ? "-" : nameOf(entry.buyerUuid());
            Component line = messages.get(player, "auction.logs-line",
                    "id", String.valueOf(entry.id()),
                    "seller", seller,
                    "buyer", buyer,
                    "summary", entry.itemSummary(),
                    "price", String.valueOf(entry.price()),
                    "status", entry.status().name());
            player.sendMessage(line);
        }
    }

    private static String listFailureKey(AuctionManager.ListResult result) {
        return switch (result) {
            case DISABLED -> "auction.disabled";
            case OUT_OF_RANGE -> "auction.price-out-of-range";
            case TOO_MANY_LISTINGS -> "auction.too-many-listings";
            case NO_ECONOMY -> "spawner.no-economy";
            case CANNOT_AFFORD_FEE -> "auction.cannot-afford-fee";
            case OK -> "auction.listed";
        };
    }

    /** The staff-only /ah logs syntax only gets appended for someone who could actually run it. */
    private void sendUsage(Player player) {
        player.sendMessage(messages.get(player, "auction.command-usage"));
        if (player.hasPermission("vertex.auction.logs")) {
            player.sendMessage(messages.get(player, "auction.command-usage-admin"));
        }
    }

    private static String nameOf(UUID uuid) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name == null ? uuid.toString() : name;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("sell", "cancel", "collect"));
            if (sender.hasPermission("vertex.auction.logs")) {
                options.add("logs");
            }
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String option : options) {
                if (option.startsWith(partial)) {
                    matches.add(option);
                }
            }
            return matches;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("sell")) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            if ("money".startsWith(partial)) {
                matches.add("money");
            }
            if ("exp".startsWith(partial)) {
                matches.add("exp");
            }
            if ("xp".startsWith(partial)) {
                matches.add("xp");
            }
            return matches;
        }
        return List.of();
    }

    private static boolean isExperienceCurrency(String value) {
        return value.equalsIgnoreCase("exp") || value.equalsIgnoreCase("xp");
    }
}
