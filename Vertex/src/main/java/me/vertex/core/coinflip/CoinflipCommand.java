package me.vertex.core.coinflip;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /cf} (alias {@code /coinflip}): opens the browser with no
 * arguments, hosts a coinflip, manages a self-ban, or reads the staff
 * audit log. See {@link CoinflipManager} for what each wager type
 * actually does.
 */
public final class CoinflipCommand implements CommandExecutor, TabCompleter {

    private final CoinflipManager manager;
    private final Messages messages;

    public CoinflipCommand(CoinflipManager manager, Messages messages) {
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
            player.sendMessage(messages.get(player, "coinflip.disabled"));
            return true;
        }

        if (args.length == 0) {
            CoinflipMenu.openBrowse(player, manager, messages, 0);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "ban" -> handleBan(player, args);
            case "unban" -> handleUnban(player);
            case "hand" -> handleHand(player, args);
            case "logs" -> handleLogs(player, args);
            case "cancel" -> handleCancel(player, args);
            case "approve" -> handleApprove(player, args);
            case "deny" -> handleDeny(player, args);
            case "review" -> handleReview(player, args);
            default -> handleWager(player, args);
        }
        return true;
    }

    private void handleBan(Player player, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            if (manager.isBanned(player.getUniqueId())) {
                player.sendMessage(messages.get(player, "coinflip.self-ban-already-active"));
                return;
            }
            if (!manager.hasPendingBanConfirmation(player.getUniqueId())) {
                player.sendMessage(messages.get(player, "coinflip.self-ban-confirm-expired"));
                return;
            }
            manager.applyBan(player.getUniqueId());
            player.sendMessage(messages.get(player, "coinflip.self-ban-applied"));
            return;
        }
        if (manager.isBanned(player.getUniqueId())) {
            player.sendMessage(messages.get(player, "coinflip.self-ban-already-active"));
            return;
        }
        manager.requestBanConfirmation(player.getUniqueId());
        player.sendMessage(messages.get(player, "coinflip.self-ban-confirm-prompt"));
    }

    private void handleUnban(Player player) {
        if (!manager.isBanned(player.getUniqueId())) {
            player.sendMessage(messages.get(player, "coinflip.self-ban-not-active"));
            return;
        }
        if (manager.liftBanIfExpired(player.getUniqueId())) {
            player.sendMessage(messages.get(player, "coinflip.self-ban-lifted"));
        } else {
            player.sendMessage(messages.get(player, "coinflip.self-ban-remaining", "time",
                    formatDuration(manager.banRemainingMillis(player.getUniqueId()))));
        }
    }

    private void handleHand(Player player, String[] args) {
        UUID target = null;
        if (args.length >= 2) {
            Player targetPlayer = Bukkit.getPlayerExact(args[1]);
            if (targetPlayer == null) {
                player.sendMessage(messages.get(player, "general.player-not-found"));
                return;
            }
            target = targetPlayer.getUniqueId();
        }
        CoinflipWagerMenu.openForCreate(player, messages, target);
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
        Coinflip coinflip = manager.getCoinflip(id);
        if (coinflip == null) {
            player.sendMessage(messages.get(player, "coinflip.play-gone"));
            return;
        }
        if (!manager.canCancel(player, coinflip)) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        boolean cancelled = manager.cancel(coinflip, player);
        player.sendMessage(messages.get(player, cancelled ? "coinflip.cancelled" : "coinflip.play-gone"));
    }

    private void handleApprove(Player player, String[] args) {
        Integer id = parsePendingMatchId(player, args);
        if (id == null) {
            return;
        }
        CoinflipManager.ApproveOutcome outcome = manager.approveItemMatch(id, player);
        if (outcome.result() != CoinflipManager.ApprovalResult.OK) {
            player.sendMessage(messages.get(player, approvalFailureKey(outcome.result())));
        }
    }

    private void handleDeny(Player player, String[] args) {
        Integer id = parsePendingMatchId(player, args);
        if (id == null) {
            return;
        }
        CoinflipManager.ApprovalResult result = manager.denyItemMatch(id, player);
        if (result != CoinflipManager.ApprovalResult.OK) {
            player.sendMessage(messages.get(player, approvalFailureKey(result)));
            return;
        }
        player.sendMessage(messages.get(player, "coinflip.item-match-you-denied"));
    }

    private void handleReview(Player player, String[] args) {
        Integer id = parsePendingMatchId(player, args);
        if (id == null) {
            return;
        }
        Coinflip coinflip = manager.getCoinflip(id);
        if (coinflip == null) {
            player.sendMessage(messages.get(player, "coinflip.play-gone"));
            return;
        }
        if (!coinflip.hostUuid().equals(player.getUniqueId())) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        CoinflipPendingMatch match = manager.pendingItemMatch(id);
        if (match == null) {
            player.sendMessage(messages.get(player, "coinflip.item-match-none-pending"));
            return;
        }
        CoinflipMatchReviewMenu.open(player, manager, messages, coinflip, match);
    }

    private Integer parsePendingMatchId(Player player, String[] args) {
        if (args.length < 2) {
            sendUsage(player);
            return null;
        }
        try {
            return Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sendUsage(player);
            return null;
        }
    }

    private static String approvalFailureKey(CoinflipManager.ApprovalResult result) {
        return switch (result) {
            case NOT_HOST -> "general.no-permission";
            case NO_PENDING_MATCH -> "coinflip.item-match-none-pending";
            case GONE, OK -> "coinflip.play-gone";
        };
    }

    private void handleLogs(Player player, String[] args) {
        if (!player.hasPermission("vertex.coinflip.logs")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        UUID filter = null;
        int page = 0;
        if (args.length >= 2) {
            try {
                page = Math.max(0, Integer.parseInt(args[1]) - 1);
            } catch (NumberFormatException e) {
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                filter = target.getUniqueId();
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
        List<CoinflipLogEntry> entries = manager.loadLog(filter, pageSize, page * pageSize);
        if (entries.isEmpty()) {
            player.sendMessage(messages.get(player, "coinflip.logs-empty"));
            return;
        }
        player.sendMessage(messages.get(player, "coinflip.logs-header", "page", String.valueOf(page + 1)));
        for (CoinflipLogEntry entry : entries) {
            String host = nameOf(entry.hostUuid());
            String opponent = entry.opponentUuid() == null ? "-" : nameOf(entry.opponentUuid());
            String winner = entry.winnerUuid() == null ? "-" : nameOf(entry.winnerUuid());
            Component line = messages.get(player, "coinflip.logs-line",
                    "id", String.valueOf(entry.id()),
                    "host", host,
                    "opponent", opponent,
                    "type", entry.type().name(),
                    "summary", entry.summary(),
                    "winner", winner,
                    "status", entry.status().name());
            player.sendMessage(line);
        }
    }

    /**
     * {@code /cf <amount> [exp|xp|money] [player]}. Amounts use the shared
     * number grammar, so {@code 10k}, {@code 1.25m}, and {@code 1,000,000}
     * are accepted everywhere the server accepts a price. "money" is accepted
     * explicitly alongside "exp" even though it's also the default with
     * no keyword at all, so both wager types are equally discoverable via
     * tab-completion instead of one being an undocumented implicit case.
     */
    private void handleWager(Player player, String[] args) {
        boolean isExp = args.length >= 2 && isExperienceCurrency(args[1]);
        boolean isMoney = args.length >= 2 && args[1].equalsIgnoreCase("money");
        boolean isGc = args.length >= 2 && args[1].equalsIgnoreCase("gc");
        boolean hasCurrencyKeyword = isExp || isMoney || isGc;
        Double amount = Numbers.parseDoublePositive(args[0]);
        if (amount == null) {
            sendUsage(player);
            return;
        }
        String targetArgName = null;
        if (hasCurrencyKeyword && args.length >= 3) {
            targetArgName = args[2];
        } else if (!hasCurrencyKeyword && args.length >= 2) {
            targetArgName = args[1];
        }

        UUID target = null;
        if (targetArgName != null) {
            Player targetPlayer = Bukkit.getPlayerExact(targetArgName);
            if (targetPlayer == null) {
                player.sendMessage(messages.get(player, "general.player-not-found"));
                return;
            }
            target = targetPlayer.getUniqueId();
        }

        CoinflipManager.CreateOutcome outcome;
        if (isExp) {
            Long levels = Numbers.parseLongPositive(args[0]);
            if (levels == null || levels > Integer.MAX_VALUE) {
                sendUsage(player);
                return;
            }
            outcome = manager.createExpCoinflip(player, levels.intValue(), target);
        } else if (isGc) {
            Long gcAmount = Numbers.parseLongPositive(args[0]);
            if (gcAmount == null) {
                sendUsage(player);
                return;
            }
            outcome = manager.createGcCoinflip(player, gcAmount, target);
        } else {
            outcome = manager.createMoneyCoinflip(player, amount, target);
        }

        if (outcome.result() != CoinflipManager.CreateResult.OK) {
            player.sendMessage(messages.get(player, createFailureKey(outcome.result())));
            return;
        }
        player.sendMessage(messages.get(player, "coinflip.created"));
        CoinflipMenu.openBrowse(player, manager, messages, 0);
    }

    private static String createFailureKey(CoinflipManager.CreateResult result) {
        return switch (result) {
            case DISABLED -> "coinflip.disabled";
            case BANNED -> "coinflip.you-are-banned";
            case OUT_OF_RANGE -> "coinflip.wager-out-of-range";
            case NO_ECONOMY -> "spawner.no-economy";
            case CANNOT_AFFORD -> "coinflip.cannot-afford";
            case EMPTY_WAGER -> "coinflip.wager-empty";
            case TOO_MANY_ITEMS -> "coinflip.wager-too-many-items-generic";
            case ALREADY_HOSTING -> "coinflip.already-hosting";
            case GC_UNAVAILABLE -> "gc.no-economy";
            case OK -> "coinflip.created";
        };
    }

    /** The staff-only /cf logs syntax only gets appended for someone who could actually run it. */
    private void sendUsage(Player player) {
        player.sendMessage(messages.get(player, "coinflip.command-usage"));
        if (player.hasPermission("vertex.coinflip.logs")) {
            player.sendMessage(messages.get(player, "coinflip.command-usage-admin"));
        }
    }

    private static String nameOf(UUID uuid) {
        String name = Bukkit.getOfflinePlayer(uuid).getName();
        return name == null ? uuid.toString() : name;
    }

    private static String formatDuration(long millis) {
        long days = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(millis);
        long hours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(millis) % 24;
        return days + "d " + hours + "h";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("ban", "unban", "hand"));
            if (sender.hasPermission("vertex.coinflip.remove")) {
                options.add("cancel");
            }
            if (sender.hasPermission("vertex.coinflip.logs")) {
                options.add("logs");
            }
            if (sender instanceof Player player && hasAnyPendingMatchAsHost(player)) {
                options.add("approve");
                options.add("deny");
                options.add("review");
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
        if (args.length == 2
                && (args[0].equalsIgnoreCase("approve") || args[0].equalsIgnoreCase("deny") || args[0].equalsIgnoreCase("review"))
                && sender instanceof Player player) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (Coinflip coinflip : manager.activeCoinflips()) {
                if (coinflip.hostUuid().equals(player.getUniqueId()) && manager.hasPendingItemMatch(coinflip.id())) {
                    String id = String.valueOf(coinflip.id());
                    if (id.startsWith(partial)) {
                        matches.add(id);
                    }
                }
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("hand")) {
            // /cf hand <player> -- this slot really is a player target, unlike the type slot below.
            String partial = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    matches.add(online.getName());
                }
            }
            return matches;
        }
        if (args.length == 2 && isNumeric(args[0])) {
            // /cf <amount> <type> -- only the wager type belongs here, never a player
            // name, so a targeted wager always requires typing the type first.
            String partial = args[1].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            if ("exp".startsWith(partial)) {
                matches.add("exp");
            }
            if ("xp".startsWith(partial)) {
                matches.add("xp");
            }
            if ("money".startsWith(partial)) {
                matches.add("money");
            }
            if ("gc".startsWith(partial)) {
                matches.add("gc");
            }
            return matches;
        }
        if (args.length == 3 && isNumeric(args[0]) && (isExperienceCurrency(args[1])
                || args[1].equalsIgnoreCase("money") || args[1].equalsIgnoreCase("gc"))) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    matches.add(online.getName());
                }
            }
            return matches;
        }
        return List.of();
    }

    private boolean hasAnyPendingMatchAsHost(Player player) {
        for (Coinflip coinflip : manager.activeCoinflips()) {
            if (coinflip.hostUuid().equals(player.getUniqueId()) && manager.hasPendingItemMatch(coinflip.id())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNumeric(String value) {
        return Numbers.parsePositive(value) != null;
    }

    private static boolean isExperienceCurrency(String value) {
        return value.equalsIgnoreCase("exp") || value.equalsIgnoreCase("xp");
    }
}
