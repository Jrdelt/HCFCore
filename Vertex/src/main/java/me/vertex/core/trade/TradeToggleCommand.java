package me.vertex.core.trade;
import me.vertex.core.lang.Messages; import org.bukkit.command.*; import org.bukkit.entity.Player;
/** Persistent incoming-request opt-out. */
public final class TradeToggleCommand implements CommandExecutor { private final TradeManager manager; private final Messages messages; public TradeToggleCommand(TradeManager manager, Messages messages){this.manager=manager;this.messages=messages;} @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args){if(!(sender instanceof Player p)){sender.sendMessage(messages.get(sender,"general.players-only"));return true;} boolean accepting=manager.toggle(p);p.sendMessage(messages.get(p,accepting?"trade.toggle-off":"trade.toggle-on"));return true;} }
