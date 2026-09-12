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
            case "payouts" -> handlePayouts(player, args);
            case "intents" -> handleIntents(player, args);
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
            } else if (args[2].equalsIgnoreCase("gc")) {
                currency = AuctionCurrency.GC;
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
        AuctionManager.ListOutcome outcome = manager.listHeld(player, price, currency);
        if (outcome.result() != AuctionManager.ListResult.OK) {
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

    private void handlePayouts(Player player, String[] args) {
        if (!player.hasPermission("vertex.auction.payouts")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        if (args.length == 1) {
            List<AuctionStorage.PendingPayout> payouts = manager.uncertainPayouts();
            if (payouts.isEmpty()) {
                player.sendMessage(messages.get(player, "auction.payouts-empty"));
                return;
            }
            player.sendMessage(messages.get(player, "auction.payouts-header", "count", String.valueOf(payouts.size())));
            for (AuctionStorage.PendingPayout payout : payouts) player.sendMessage(messages.get(player,
                    "auction.payouts-line", "key", payout.key(), "player", nameOf(payout.ownerUuid()),
                    "currency", payout.currency().name(), "amount", String.valueOf(payout.amount())));
            return;
        }
        if (args.length != 3 || !(args[2].equalsIgnoreCase("paid") || args[2].equalsIgnoreCase("retry"))) {
            player.sendMessage(messages.get(player, "auction.payouts-usage"));
            return;
        }
        boolean paid = args[2].equalsIgnoreCase("paid");
        boolean success = manager.resolveUncertainPayout(args[1], paid);
        player.sendMessage(messages.get(player, success ? "auction.payouts-resolved" : "auction.payouts-missing",
                "key", args[1], "action", paid ? "PAID" : "RETRY"));
    }

    private void handleIntents(Player player, String[] args) {
        if (!player.hasPermission("vertex.auction.intents")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        if (args.length == 1) {
            List<AuctionStorage.CreationIntent> intents = manager.unresolvedCreationIntents();
            if (intents.isEmpty()) {
                player.sendMessage(messages.get(player, "auction.intents-empty"));
                return;
            }
            player.sendMessage(messages.get(player, "auction.intents-header", "count", String.valueOf(intents.size())));
            for (AuctionStorage.CreationIntent intent : intents) {
                player.sendMessage(messages.get(player, "auction.intents-line", "key", intent.key(),
                        "player", nameOf(intent.sellerUuid()), "item", intent.item().getType().name(),
                        "amount", String.valueOf(intent.item().getAmount()), "price", String.valueOf(intent.price()),
                        "currency", intent.currency().name()));
            }
            return;
        }
        if (args.length == 2) {
            manager.creationIntent(args[1]).ifPresentOrElse(intent -> player.sendMessage(messages.get(player,
                    "auction.intents-detail", "key", intent.key(), "player", nameOf(intent.sellerUuid()),
                    "item", intent.item().getType().name(), "amount", String.valueOf(intent.item().getAmount()),
                    "price", String.valueOf(intent.price()), "currency", intent.currency().name())),
                    () -> player.sendMessage(messages.get(player, "auction.intents-missing", "key", args[1])));
            return;
        }
        if (args.length != 3 || !(args[2].equalsIgnoreCase("debited")
                || args[2].equalsIgnoreCase("not-debited")
                || args[2].equalsIgnoreCase("item-not-removed"))) {
            player.sendMessage(messages.get(player, "auction.intents-usage"));
            return;
        }
        AuctionStorage.IntentResolutionDecision decision = args[2].equalsIgnoreCase("debited")
                ? AuctionStorage.IntentResolutionDecision.DEBITED
                : args[2].equalsIgnoreCase("not-debited")
                        ? AuctionStorage.IntentResolutionDecision.NOT_DEBITED
                        : AuctionStorage.IntentResolutionDecision.ITEM_NOT_REMOVED;
        AuctionStorage.IntentResolution result = manager.resolveCreationIntent(args[1], decision, player);
        player.sendMessage(messages.get(player, intentResultKey(result.status()), "key", args[1],
                "prior", result.priorDecision() == null ? "-" : result.priorDecision()));
    }

    private static String intentResultKey(AuctionStorage.IntentResolutionStatus status) {
        return switch (status) {
            case ACTIVATED -> "auction.intents-activated";
            case REFUNDED -> "auction.intents-refunded";
            case DISCARDED -> "auction.intents-discarded";
            case ALREADY_RESOLVED -> "auction.intents-already-resolved";
            case CONFLICT -> "auction.intents-conflict";
            case INVALID_STATE -> "auction.intents-invalid";
            case MISSING -> "auction.intents-missing";
            case STORAGE_ERROR -> "auction.intents-storage-error";
        };
    }

    private static String listFailureKey(AuctionManager.ListResult result) {
        return switch (result) {
            case DISABLED -> "auction.disabled";
            case OUT_OF_RANGE -> "auction.price-out-of-range";
            case TOO_MANY_LISTINGS -> "auction.too-many-listings";
            case NO_ECONOMY -> "spawner.no-economy";
            case NO_GC -> "gc.no-economy";
            case CANNOT_AFFORD_FEE -> "auction.cannot-afford-fee";
            case PERSIST_FAILED -> "auction.create-persist-failed";
            case RECOVERY_REQUIRED -> "auction.create-recovery-required";
            case OK -> "auction.listed";
        };
    }

    /** The staff-only /ah logs syntax only gets appended for someone who could actually run it. */
    private void sendUsage(Player player) {
        player.sendMessage(messages.get(player, "auction.command-usage"));
        if (player.hasPermission("vertex.auction.logs") || player.hasPermission("vertex.auction.payouts")
                || player.hasPermission("vertex.auction.intents")) {
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
            if (sender.hasPermission("vertex.auction.payouts")) options.add("payouts");
            if (sender.hasPermission("vertex.auction.intents")) options.add("intents");
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String option : options) {
                if (option.startsWith(partial)) {
                    matches.add(option);
                }
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("payouts")
                && sender.hasPermission("vertex.auction.payouts")) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return manager.uncertainPayouts().stream().map(AuctionStorage.PendingPayout::key)
                    .filter(key -> key.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("payouts")
                && sender.hasPermission("vertex.auction.payouts")) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            return List.of("paid", "retry").stream().filter(value -> value.startsWith(partial)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("intents")
                && sender.hasPermission("vertex.auction.intents")) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            return manager.unresolvedCreationIntents().stream().map(AuctionStorage.CreationIntent::key)
                    .filter(key -> key.toLowerCase(Locale.ROOT).startsWith(partial)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("intents")
                && sender.hasPermission("vertex.auction.intents")) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            return List.of("debited", "not-debited", "item-not-removed").stream()
                    .filter(value -> value.startsWith(partial)).toList();
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
            if ("gc".startsWith(partial)) {
                matches.add("gc");
            }
            return matches;
        }
        return List.of();
    }

    private static boolean isExperienceCurrency(String value) {
        return value.equalsIgnoreCase("exp") || value.equalsIgnoreCase("xp");
    }
}
