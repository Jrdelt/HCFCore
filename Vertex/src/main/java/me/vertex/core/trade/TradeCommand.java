package me.vertex.core.trade;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.Locale;

/** /trade request, accept and cancellation command family. */
public final class TradeCommand implements CommandExecutor, TabCompleter {
    private final TradeManager manager; private final Messages messages;
    public TradeCommand(TradeManager manager, Messages messages) { this.manager=manager; this.messages=messages; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.get(sender, "general.players-only")); return true; }
        if (!player.hasPermission("vertex.trade.use")) { player.sendMessage(messages.get(player, "general.no-permission")); return true; }
        if (args.length == 0) { player.sendMessage(messages.get(player, "trade.usage")); return true; }
        if (args[0].equalsIgnoreCase("cancel")) { manager.cancel(manager.session(player.getUniqueId()), "trade-cancelled"); return true; }
        if (args[0].equalsIgnoreCase("accept")) { Player other = args.length < 2 ? null : Bukkit.getPlayerExact(args[1]); send(player, manager.accept(player, other == null ? player : other), other); return true; }
        Player target = Bukkit.getPlayerExact(args[0]); if (target == null) { player.sendMessage(messages.get(player, "general.player-not-found")); return true; }
        TradeManager.Result result = manager.request(player, target); if (result == TradeManager.Result.OK) { player.sendMessage(messages.get(player, "trade.request-sent", "player", target.getName())); target.sendMessage(messages.get(target, "trade.request-received", "player", player.getName())); } else send(player, result, target); return true;
    }
    private void send(Player player, TradeManager.Result result, Player other) { String name = other == null ? "player" : other.getName(); String key = switch(result) { case SELF -> "trade.self"; case BUSY -> "trade.busy"; case TOO_FAR -> "trade.too-far"; case TARGET_OFF -> "trade.target-off"; case BLOCKED -> "trade.blocked"; case COOLDOWN -> "trade.cooldown"; case NO_REQUEST, NOT_REQUESTED, EXPIRED -> "trade.no-request"; case DISABLED -> "trade.disabled"; default -> "trade.cancelled"; }; player.sendMessage(messages.get(player,key,"player",name,"distance",String.valueOf(manager.maxDistance()))); }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { if(args.length==1) return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n->n.toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))).toList(); if(args.length==2&&List.of("accept").contains(args[0].toLowerCase(Locale.ROOT))) return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(); return List.of(); }
}
