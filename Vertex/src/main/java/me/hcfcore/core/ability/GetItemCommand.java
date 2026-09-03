package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.lang.MessageFormatter;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GetItemCommand implements CommandExecutor, TabCompleter {

    private final AbilityManager abilityManager;
    private final Messages messages;
    private final Plugin plugin;

    public GetItemCommand(Plugin plugin, AbilityManager abilityManager, Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.ability.give")) {
            sender.sendMessage(messages.getChat(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(messages.getChat(sender, "ability.getitem-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(messages.getChat(sender, "general.player-not-found"));
            return true;
        }

        Ability ability = abilityManager.get(args[1]);
        if (ability == null) {
            sender.sendMessage(messages.getChat(sender, "ability.getitem-not-found", "ability", args[1]));
            return true;
        }

        int maxAmount = Math.max(1, plugin.getConfig().getInt("abilities.max-getitem-amount", 64));
        Integer amount = 1;
        if (args.length > 2) {
            amount = parseIntInRange(args[2], 1, maxAmount);
        }
        if (amount == null) {
            sender.sendMessage(messages.getChat(sender, "ability.getitem-usage"));
            return true;
        }

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            items.add(abilityManager.createItem(ability));
        }

        PlayerInventory inventory = target.getInventory();
        for (ItemStack dropped : inventory.addItem(items.toArray(new ItemStack[0])).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), dropped);
        }

        Component abilityName = MessageFormatter.deserialize(ability.getDisplayName());
        sender.sendMessage(messages.getChat(sender, "ability.getitem-given-sender",
                        "player", target.getName(), "amount", String.valueOf(amount))
                .append(abilityName)
                .append(Component.text(".")));
        target.sendMessage(messages.getChat(target, "ability.getitem-given-target", "amount", String.valueOf(amount))
                .append(abilityName)
                .append(Component.text(".")));
        return true;
    }

    private static Integer parseIntInRange(String input, int minimum, int maximum) {
        try {
            int value = Integer.parseInt(input);
            return value >= minimum && value <= maximum ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> matches = new ArrayList<>();
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    matches.add(player.getName());
                }
            }
        } else if (args.length == 2) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            for (String id : abilityManager.getAbilities().keySet()) {
                if (id.startsWith(partial)) {
                    matches.add(id);
                }
            }
        }
        return matches;
    }
}
