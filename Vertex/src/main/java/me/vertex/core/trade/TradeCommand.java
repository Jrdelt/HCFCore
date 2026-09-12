package me.vertex.core.trade;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /trade request, accept and cancellation command family. */
public final class TradeCommand implements CommandExecutor, TabCompleter {
    private final TradeManager manager; private final Messages messages;
    public TradeCommand(TradeManager manager, Messages messages) { this.manager=manager; this.messages=messages; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.get(sender, "general.players-only")); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("payouts")) { handlePayouts(player, args); return true; }
        if (!player.hasPermission("vertex.trade.use")) { player.sendMessage(messages.get(player, "general.no-permission")); return true; }
        if (args.length == 0) { player.sendMessage(messages.get(player, "trade.usage")); return true; }
        if (args[0].equalsIgnoreCase("cancel")) { manager.cancel(manager.session(player.getUniqueId()), "trade-cancelled"); return true; }
        if (args[0].equalsIgnoreCase("accept")) { Player other = args.length < 2 ? null : Bukkit.getPlayerExact(args[1]); send(player, manager.accept(player, other == null ? player : other), other); return true; }
        Player target = Bukkit.getPlayerExact(args[0]); if (target == null) { player.sendMessage(messages.get(player, "general.player-not-found")); return true; }
        TradeManager.Result result = manager.request(player, target); if (result == TradeManager.Result.OK) { player.sendMessage(messages.get(player, "trade.request-sent", "player", target.getName())); target.sendMessage(messages.get(target, "trade.request-received", "player", player.getName())); } else send(player, result, target); return true;
    }
    private void send(Player player, TradeManager.Result result, Player other) { String name = other == null ? "player" : other.getName(); String key = switch(result) { case SELF -> "trade.self"; case BUSY -> "trade.busy"; case TOO_FAR -> "trade.too-far"; case TARGET_OFF -> "trade.target-off"; case BLOCKED -> "trade.blocked"; case COOLDOWN -> "trade.cooldown"; case NO_REQUEST, NOT_REQUESTED, EXPIRED -> "trade.no-request"; case DISABLED -> "trade.disabled"; default -> "trade.cancelled"; }; player.sendMessage(messages.get(player,key,"player",name,"distance",String.valueOf(manager.maxDistance()))); }
    private void handlePayouts(Player player, String[] args) {
        if (!player.hasPermission("vertex.trade.payouts")) { player.sendMessage(messages.get(player, "general.no-permission")); return; }
        if (args.length == 1) {
            List<TradeStorage.PendingPayout> payouts = manager.uncertainPayouts();
            if (payouts.isEmpty()) { player.sendMessage(messages.get(player, "trade.payouts-empty")); return; }
            player.sendMessage(messages.get(player, "trade.payouts-header", "count", String.valueOf(payouts.size())));
            for (TradeStorage.PendingPayout payout : payouts) player.sendMessage(messages.get(player,
                    "trade.payouts-line", "key", payout.key(), "player", playerName(payout.owner()),
                    "amount", String.valueOf(payout.amount()), "currency", payout.currency().name()));
            return;
        }
        if (args.length != 3 || !(args[2].equalsIgnoreCase("paid") || args[2].equalsIgnoreCase("retry"))) {
            player.sendMessage(messages.get(player, "trade.payouts-usage")); return;
        }
        boolean paid = args[2].equalsIgnoreCase("paid");
        boolean changed = manager.resolveUncertainPayout(args[1], paid, player);
        player.sendMessage(messages.get(player, changed ? "trade.payouts-resolved" : "trade.payouts-missing",
                "key", args[1], "action", paid ? "PAID" : "RETRY"));
    }
    private static String playerName(java.util.UUID uuid) { String name=Bukkit.getOfflinePlayer(uuid).getName(); return name==null?uuid.toString():name; }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if(args.length==1){String partial=args[0].toLowerCase(Locale.ROOT);List<String> values=new ArrayList<>();values.add("accept");values.add("cancel");if(sender.hasPermission("vertex.trade.payouts"))values.add("payouts");Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(values::add);return values.stream().filter(value->value.toLowerCase(Locale.ROOT).startsWith(partial)).distinct().toList();}
        if(args.length==2&&args[0].equalsIgnoreCase("accept"))return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        if(args.length==2&&args[0].equalsIgnoreCase("payouts")&&sender.hasPermission("vertex.trade.payouts")){String partial=args[1].toLowerCase(Locale.ROOT);return manager.uncertainPayouts().stream().map(TradeStorage.PendingPayout::key).filter(key->key.toLowerCase(Locale.ROOT).startsWith(partial)).toList();}
        if(args.length==3&&args[0].equalsIgnoreCase("payouts")&&sender.hasPermission("vertex.trade.payouts")){String partial=args[2].toLowerCase(Locale.ROOT);return List.of("paid","retry").stream().filter(value->value.startsWith(partial)).toList();}
        return List.of();
    }
}
