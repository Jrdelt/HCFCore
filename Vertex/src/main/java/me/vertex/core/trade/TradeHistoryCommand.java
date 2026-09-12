package me.vertex.core.trade;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Staff audit browser for {@code /tradelogs [all|player]}. */
public final class TradeHistoryCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION = "vertex.trade.staff.history";

    private final TradeManager manager;
    private final Messages messages;

    public TradeHistoryCommand(TradeManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }

        UUIDAndName target = args.length == 0 || args[0].equalsIgnoreCase("all")
                ? new UUIDAndName(null, "all trades") : resolve(args[0]);
        manager.openHistory(player, target.id(), target.name(), 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) || args.length != 1) {
            return List.of();
        }
        String partial = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static UUIDAndName resolve(String raw) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(raw);
        return new UUIDAndName(player.getUniqueId(), player.getName() == null ? raw : player.getName());
    }

    private record UUIDAndName(UUID id, String name) {
    }
}
