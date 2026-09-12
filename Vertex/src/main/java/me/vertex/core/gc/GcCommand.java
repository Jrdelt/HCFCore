package me.vertex.core.gc;

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
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * {@code /gc}: opens the wallet with no arguments, redeems or issues codes,
 * and gives staff a full balance/audit toolkit.
 *
 * <p>Every {@code /gc admin} balance mutation is logged loudly to console
 * -- permitted or not -- mirroring the {@code vertex.staff.punish.
 * bypasscombat} precedent ({@code PunishmentCombatListener}): a high-risk
 * action that touches a real currency must always leave a trail, whether
 * or not it was allowed to go through.
 */
public final class GcCommand implements CommandExecutor, TabCompleter {

    public static final String USE_PERMISSION = "vertex.gc.use";
    public static final String ADJUST_PERMISSION = "vertex.gc.adjust";
    public static final String VIEW_PERMISSION = "vertex.gc.view";
    public static final String LOGS_PERMISSION = "vertex.gc.logs";
    public static final String REDEEM_CREATE_PERMISSION = "vertex.gc.redeem.create";

    private final Plugin plugin;
    private final GcManager manager;
    private final GcMenu menu;
    private final GcInteropHook interopHook;
    private final Messages messages;
    private final Logger logger;
    private volatile GcChatProtectionListener chatProtection;

    public GcCommand(Plugin plugin, GcManager manager, GcMenu menu,
            GcInteropHook interopHook, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.menu = menu;
        this.interopHook = interopHook;
        this.messages = messages;
        this.logger = plugin.getLogger();
    }

    public void setChatProtection(GcChatProtectionListener chatProtection) {
        this.chatProtection = chatProtection;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messages.get(sender, "general.players-only"));
                return true;
            }
            if (!player.hasPermission(USE_PERMISSION)) {
                player.sendMessage(messages.get(player, "general.no-permission"));
                return true;
            }
            menu.open(player);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "redeem" -> handleRedeem(sender, args);
            case "withdraw" -> handleWithdrawCode(sender, args);
            case "admin" -> handleAdmin(sender, args);
            case "chatapprove" -> {
                if (sender instanceof Player player && chatProtection != null) chatProtection.approve(player);
                else sender.sendMessage(messages.get(sender, "general.players-only"));
            }
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleWithdrawCode(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        if (args.length != 2) {
            player.sendMessage(messages.get(player, "gc.withdraw-usage"));
            return;
        }
        Long amount = Numbers.parseLongPositive(args[1]);
        if (amount == null || amount < manager.minWithdraw() || amount > manager.maxWithdraw()) {
            player.sendMessage(messages.get(player, "gc.amount-out-of-range"));
            return;
        }
        manager.withdrawToCode(player.getUniqueId(), amount).thenAccept(outcome -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            switch (outcome.result()) {
                case OK -> {
                    player.sendMessage(messages.get(player, "gc.withdraw-code-created",
                            "amount", Numbers.formatFull(amount), "code", outcome.code()));
                    me.vertex.core.audit.LargeTransactionAudit.record(plugin, amount, "GC_WITHDRAW", player, null);
                }
                case INSUFFICIENT -> player.sendMessage(messages.get(player, "gc.not-enough-gc"));
                case FAILED -> player.sendMessage(messages.get(player, "gc.transaction-failed"));
            }
        }));
    }

    private void handleRedeem(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("create")) {
            handleRedeemCreate(sender, args);
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        manager.redeem(player.getUniqueId(), args[1]).thenAccept(outcome -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            switch (outcome.result()) {
                case OK -> {
                    player.sendMessage(messages.get(player, "gc.redeem-success",
                            "amount", Numbers.formatFull(outcome.amount())));
                    interopHook.onGcCredited(player, outcome.amount(), "redeem");
                    me.vertex.core.audit.LargeTransactionAudit.record(plugin, outcome.amount(), "GC_REDEEM", player, null);
                }
                case NOT_FOUND -> player.sendMessage(messages.get(player, "gc.redeem-not-found"));
                case EXPIRED -> player.sendMessage(messages.get(player, "gc.redeem-expired"));
                case EXHAUSTED -> player.sendMessage(messages.get(player, "gc.redeem-exhausted"));
                case COOLDOWN -> player.sendMessage(messages.get(player, "gc.redeem-cooldown",
                        "seconds", Long.toString(Math.max(1L, (outcome.retryAfterMillis() + 999L) / 1000L))));
                case IN_PROGRESS -> player.sendMessage(messages.get(player, "gc.redeem-in-progress"));
                case BALANCE_LIMIT -> player.sendMessage(messages.get(player, "gc.balance-limit"));
                case FAILED -> player.sendMessage(messages.get(player, "gc.transaction-failed"));
            }
        }));
    }

    private void handleRedeemCreate(CommandSender sender, String[] args) {
        if (!sender.hasPermission(REDEEM_CREATE_PERMISSION)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(messages.get(sender, "gc.redeem-create-usage"));
            return;
        }
        Long amount = Numbers.parseLongPositive(args[2]);
        if (amount == null) {
            sender.sendMessage(messages.get(sender, "gc.amount-invalid"));
            return;
        }
        int uses = 1;
        if (args.length >= 4) {
            Long parsedUses = Numbers.parseLongPositive(args[3]);
            if (parsedUses == null || parsedUses > Integer.MAX_VALUE) {
                sender.sendMessage(messages.get(sender, "gc.amount-invalid"));
                return;
            }
            uses = parsedUses.intValue();
        }
        Long expiresAt = null;
        if (args.length >= 5) {
            Long seconds = parseDurationSeconds(args[4]);
            if (seconds == null) {
                sender.sendMessage(messages.get(sender, "gc.expiry-invalid"));
                return;
            }
            try { expiresAt = Math.addExact(System.currentTimeMillis(), Math.multiplyExact(seconds, 1000L)); }
            catch (ArithmeticException overflow) {
                sender.sendMessage(messages.get(sender, "gc.expiry-invalid"));
                return;
            }
        }
        UUID staffUuid = sender instanceof Player player ? player.getUniqueId() : new UUID(0L, 0L);
        int finalUses = uses;
        manager.createRedeemCode(staffUuid, amount, uses, expiresAt).thenAccept(outcome -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!outcome.success()) {
                sender.sendMessage(messages.get(sender, "gc.redeem-create-failed"));
                return;
            }
            sender.sendMessage(messages.get(sender, "gc.redeem-created",
                    "code", outcome.code(), "amount", Numbers.formatFull(amount), "uses", String.valueOf(finalUses)));
        }));
    }

    /** Accepts a bare number of seconds, or a shorthand like {@code 7d}/{@code 12h}/{@code 30m}. */
    Long parseDurationSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        char unit = trimmed.charAt(trimmed.length() - 1);
        String numberPart = Character.isDigit(unit) ? trimmed : trimmed.substring(0, trimmed.length() - 1);
        java.math.BigDecimal number = Numbers.parsePositive(numberPart);
        if (number == null) {
            return null;
        }
        long multiplier = switch (Character.isDigit(unit) ? 's' : unit) {
            case 's' -> 1;
            case 'm' -> 60;
            case 'h' -> 3600;
            case 'd' -> 86400;
            default -> 0;
        };
        try {
            long seconds = number.multiply(java.math.BigDecimal.valueOf(multiplier)).longValueExact();
            return seconds > 0 && seconds <= manager.maxCodeLifetimeSeconds() ? seconds : null;
        } catch (ArithmeticException invalid) { return null; }
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "balance" -> handleAdminBalance(sender, args);
            case "give" -> handleAdminMutate(sender, args, GcAction.STAFF_GIVE);
            case "remove" -> handleAdminMutate(sender, args, GcAction.STAFF_REMOVE);
            case "set" -> handleAdminMutate(sender, args, GcAction.STAFF_SET);
            case "zero" -> handleAdminZero(sender, args);
            case "logs" -> handleAdminLogs(sender, args);
            default -> sendUsage(sender);
        }
    }

    private void handleAdminBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission(VIEW_PERMISSION)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return;
        }
        if (args.length < 3) {
            sendUsage(sender);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        manager.refreshBalance(target.getUniqueId()).whenComplete((fresh,error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(error == null && Boolean.TRUE.equals(fresh)
                    ? messages.get(sender, "gc.admin-balance", "player", displayName(target),
                        "balance", Numbers.formatFull(manager.balance(target.getUniqueId())))
                    : messages.get(sender, "gc.balance-unavailable")));
        });
    }

    /** {@code /gc admin give|remove|set <player> <amount>}. Every attempt is logged, whether or not it was permitted. */
    private void handleAdminMutate(CommandSender sender, String[] args, GcAction action) {
        if (args.length < 4) {
            sendUsage(sender);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        Long amount = Numbers.parseLongPositive(args[3]);
        boolean permitted = sender.hasPermission(ADJUST_PERMISSION);
        logAdminAttempt(sender, action, target, args[3], permitted);
        if (!permitted) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return;
        }
        if (amount == null) {
            sender.sendMessage(messages.get(sender, "gc.amount-invalid"));
            return;
        }
        UUID actorUuid = sender instanceof Player player ? player.getUniqueId() : null;
        UUID targetUuid = target.getUniqueId();
        var result = switch (action) {
            case STAFF_GIVE -> manager.adjustStaffDurably(targetUuid, actorUuid, action, amount, "/gc admin give");
            case STAFF_REMOVE -> manager.adjustStaffDurably(targetUuid, actorUuid, action, -amount, "/gc admin remove");
            case STAFF_SET -> manager.setBalanceDurably(targetUuid, actorUuid, action, amount, "/gc admin set");
            default -> java.util.concurrent.CompletableFuture.completedFuture(false);
        };
        reportMutation(sender, target, result);
    }

    private void handleAdminZero(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sendUsage(sender);
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        boolean permitted = sender.hasPermission(ADJUST_PERMISSION);
        logAdminAttempt(sender, GcAction.STAFF_ZERO, target, "0", permitted);
        if (!permitted) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return;
        }
        UUID actorUuid = sender instanceof Player player ? player.getUniqueId() : null;
        reportMutation(sender, target, manager.setBalanceDurably(target.getUniqueId(), actorUuid,
                GcAction.STAFF_ZERO, 0L, "/gc admin zero"));
    }

    private void reportMutation(CommandSender sender, OfflinePlayer target, java.util.concurrent.CompletableFuture<Boolean> result) {
        result.whenComplete((applied,error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(error == null && Boolean.TRUE.equals(applied)
                    ? messages.get(sender, "gc.admin-mutate-success", "player", displayName(target),
                        "balance", Numbers.formatFull(manager.balance(target.getUniqueId())))
                    : messages.get(sender, "gc.admin-mutate-failed")));
        });
    }

    private void handleAdminLogs(CommandSender sender, String[] args) {
        if (!sender.hasPermission(LOGS_PERMISSION)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return;
        }
        UUID filter = null;
        int page = 0;
        if (args.length >= 3) {
            try {
                page = Math.max(0, Integer.parseInt(args[2]) - 1);
            } catch (NumberFormatException e) {
                filter = Bukkit.getOfflinePlayer(args[2]).getUniqueId();
                if (args.length >= 4) {
                    try {
                        page = Math.max(0, Integer.parseInt(args[3]) - 1);
                    } catch (NumberFormatException ignored) {
                        // Fall back to page 1.
                    }
                }
            }
        }
        int pageSize = manager.logPageSize();
        List<GcLogEntry> entries = manager.loadLog(filter, pageSize, page * pageSize);
        if (entries.isEmpty()) {
            sender.sendMessage(messages.get(sender, "gc.logs-empty"));
            return;
        }
        sender.sendMessage(messages.get(sender, "gc.logs-header", "page", String.valueOf(page + 1)));
        for (GcLogEntry entry : entries) {
            String actor = entry.actorUuid() == null ? "-" : nameOf(entry.actorUuid());
            String target = nameOf(entry.targetUuid());
            Component line = messages.get(sender, "gc.logs-line",
                    "id", String.valueOf(entry.id()),
                    "actor", actor,
                    "target", target,
                    "action", entry.action().name(),
                    "amount", Numbers.formatFull(entry.amount()),
                    "balance", Numbers.formatFull(entry.balanceAfter()));
            sender.sendMessage(line);
        }
    }

    private void logAdminAttempt(CommandSender sender, GcAction action, OfflinePlayer target, String amountText,
            boolean permitted) {
        String senderName = sender instanceof Player player ? player.getName() : sender.getName();
        String verdict = permitted ? "used" : "attempted (denied, missing " + ADJUST_PERMISSION + ")";
        logger.warning(senderName + " " + verdict + " /gc admin " + action.name().toLowerCase(Locale.ROOT).replace("staff_", "")
                + " on " + displayName(target) + " for " + amountText + " GC.");
    }

    private static String displayName(OfflinePlayer player) {
        String name = player.getName();
        return name == null ? player.getUniqueId().toString() : name;
    }

    private static String nameOf(UUID uuid) {
        return displayName(Bukkit.getOfflinePlayer(uuid));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(messages.get(sender, "gc.command-usage"));
        if (sender.hasPermission(ADJUST_PERMISSION) || sender.hasPermission(LOGS_PERMISSION)
                || sender.hasPermission(VIEW_PERMISSION)) {
            sender.sendMessage(messages.get(sender, "gc.command-usage-admin"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("redeem", "withdraw"));
            if (sender.hasPermission(ADJUST_PERMISSION) || sender.hasPermission(LOGS_PERMISSION)
                    || sender.hasPermission(VIEW_PERMISSION)) {
                options.add("admin");
            }
            return filterPrefix(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("redeem") && sender.hasPermission(REDEEM_CREATE_PERMISSION)) {
            return filterPrefix(List.of("create"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission(VIEW_PERMISSION)) {
                options.add("balance");
            }
            if (sender.hasPermission(ADJUST_PERMISSION)) {
                options.addAll(List.of("give", "remove", "set", "zero"));
            }
            if (sender.hasPermission(LOGS_PERMISSION)) {
                options.add("logs");
            }
            return filterPrefix(options, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin")
                && List.of("balance", "give", "remove", "set", "zero").contains(args[1].toLowerCase(Locale.ROOT))) {
            List<String> matches = new ArrayList<>();
            String partial = args[2].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    matches.add(online.getName());
                }
            }
            return matches;
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> options, String partial) {
        String lower = partial.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
