package me.vertex.core.wand;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/** {@code /wand give <player> <tier> [uses]} -- there is no in-game shop for wands. */
public final class WandCommand implements CommandExecutor, TabCompleter {

    public static final String GIVE_PERMISSION = "vertex.wand.give";

    private final WandManager wands;
    private final Messages messages;

    public WandCommand(WandManager wands, Messages messages) {
        this.wands = wands;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(messages.get(sender, "wand.usage"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        WandTier tier = wands.tier(args[2]);
        if (tier == null) {
            sender.sendMessage(messages.get(sender, "wand.unknown-tier",
                    "tiers", String.join(", ", wands.tierIds())));
            return true;
        }
        int uses = tier.uses();
        if (args.length >= 4) {
            try {
                uses = Math.max(1, Integer.parseInt(args[3]));
            } catch (NumberFormatException e) {
                sender.sendMessage(messages.get(sender, "wand.usage"));
                return true;
            }
        }

        ItemStack wand = wands.createWand(tier, uses);
        if (!me.vertex.core.storage.DeliveryManager.queueOverflow(
                wands.plugin(), target, List.of(wand), "wand-admin-give")) {
            sender.sendMessage(messages.get(sender, "delivery.storage-unavailable"));
            return true;
        }
        sender.sendMessage(messages.get(sender, "wand.given",
                "tier", tier.id(), "player", target.getName(), "uses", String.valueOf(uses)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            return List.of();
        }
        return switch (args.length) {
            case 1 -> "give".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("give") : List.of();
            case 2 -> Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .sorted()
                    .toList();
            case 3 -> wands.tierIds().stream()
                    .filter(id -> id.startsWith(args[2].toLowerCase(Locale.ROOT)))
                    .toList();
            default -> List.of();
        };
    }
}
