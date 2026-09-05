package me.hcfcore.core.collector;

import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /chunkcollector give &lt;player&gt; -- hands out a marked Green Shulker Box (there's no in-game shop for these). */
public final class ChunkCollectorCommand implements CommandExecutor, TabCompleter {

    private final ChunkCollectorManager manager;
    private final Messages messages;

    public ChunkCollectorCommand(ChunkCollectorManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.collector.give")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(MessageFormatter.deserialize("<red>Usage: /chunkcollector give <player>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        ChunkCollectorData data = new ChunkCollectorData(0, target.getUniqueId(), null);
        ItemStack item = manager.createCollectorItem(MessageFormatter.deserialize("<green>Chunk Collector"), data);
        for (ItemStack dropped : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), dropped);
        }
        sender.sendMessage(MessageFormatter.deserialize("<green>Gave " + target.getName() + " a Chunk Collector."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return "give".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("give") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> matches = new ArrayList<>();
            String partial = args[1].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    matches.add(player.getName());
                }
            }
            return matches;
        }
        return List.of();
    }
}
