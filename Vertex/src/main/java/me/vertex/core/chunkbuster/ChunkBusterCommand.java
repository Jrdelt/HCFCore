package me.vertex.core.chunkbuster;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;

/** Administrative distribution command for the purchasable Chunk Buster items. */
public final class ChunkBusterCommand implements CommandExecutor, TabCompleter {

    public static final String GIVE_PERMISSION = "vertex.chunkbuster.give";

    private final Plugin plugin;
    private final ChunkBusterManager manager;
    private final Messages messages;

    public ChunkBusterCommand(Plugin plugin, ChunkBusterManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(messages.get(sender, "chunkbuster.give-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        ChunkBusterType type = ChunkBusterType.fromConfigKey(args[2]);
        if (type == null || !manager.isEnabled(type)) {
            sender.sendMessage(messages.get(sender, "chunkbuster.give-invalid-type"));
            return true;
        }
        int amount = args.length >= 4 ? parseAmount(args[3]) : 1;
        if (amount <= 0) {
            sender.sendMessage(messages.get(sender, "chunkbuster.give-invalid-amount"));
            return true;
        }

        ItemStack prototype = manager.createItem(type);
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            ItemStack item = prototype.clone();
            int stackAmount = Math.min(item.getMaxStackSize(), remaining);
            item.setAmount(stackAmount);
            items.add(item);
            remaining -= stackAmount;
        }
        if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                plugin, target, items, "chunkbuster-admin-give")) {
            sender.sendMessage(messages.get(sender, "delivery.storage-unavailable"));
            return true;
        }
        sender.sendMessage(messages.get(sender, "chunkbuster.given", "amount", String.valueOf(amount),
                "type", type.configKey(), "player", target.getName()));
        plugin.getLogger().info(sender.getName() + " gave " + amount + " " + type.configKey()
                + " Chunk Buster(s) to " + target.getName() + ".");
        return true;
    }

    private static int parseAmount(String raw) {
        try {
            return Math.min(2_304, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            return List.of();
        }
        return switch (args.length) {
            case 1 -> matching(args[0], List.of("give"));
            case 2 -> Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .sorted().toList();
            case 3 -> matching(args[2], java.util.Arrays.stream(ChunkBusterType.values())
                    .filter(manager::isEnabled).map(ChunkBusterType::configKey).toList());
            case 4 -> List.of("1", "2", "16", "64");
            default -> List.of();
        };
    }

    private static List<String> matching(String prefix, List<String> values) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }
}
