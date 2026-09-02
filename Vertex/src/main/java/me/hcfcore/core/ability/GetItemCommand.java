package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GetItemCommand implements CommandExecutor, TabCompleter {

    private final AbilityManager abilityManager;
    private final Messages messages;

    public GetItemCommand(AbilityManager abilityManager, Messages messages) {
        this.abilityManager = abilityManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.ability.give")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(messages.get(sender, "ability.getitem-usage"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }

        Ability ability = abilityManager.get(args[1]);
        if (ability == null) {
            sender.sendMessage(messages.get(sender, "ability.getitem-not-found", "ability", args[1]));
            return true;
        }

        int amount = Math.max(1, args.length > 2 ? parseIntOrDefault(args[2], 1) : 1);

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            items.add(abilityManager.createItem(ability));
        }

        PlayerInventory inventory = target.getInventory();
        for (ItemStack dropped : inventory.addItem(items.toArray(new ItemStack[0])).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), dropped);
        }

        Component abilityName = LegacyComponentSerializer.legacyAmpersand().deserialize(ability.getDisplayName());
        sender.sendMessage(messages.get(sender, "ability.getitem-given-sender",
                        "player", target.getName(), "amount", String.valueOf(amount))
                .append(abilityName)
                .append(Component.text(".")));
        target.sendMessage(messages.get(target, "ability.getitem-given-target", "amount", String.valueOf(amount))
                .append(abilityName)
                .append(Component.text(".")));
        return true;
    }

    private static int parseIntOrDefault(String input, int fallback) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return fallback;
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
