package me.hcfcore.core.ability;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

    public GetItemCommand(AbilityManager abilityManager) {
        this.abilityManager = abilityManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.ability.give")) {
            sender.sendMessage(Component.text("You don't have permission to do that.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /getitem <username> <ability> [amount]", NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
            return true;
        }

        Ability ability = abilityManager.get(args[1]);
        if (ability == null) {
            sender.sendMessage(Component.text("No ability named '" + args[1] + "'.", NamedTextColor.RED));
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
        sender.sendMessage(Component.text("Gave " + target.getName() + " " + amount + "x ", NamedTextColor.GREEN)
                .append(abilityName)
                .append(Component.text(".", NamedTextColor.GREEN)));
        target.sendMessage(Component.text("You received " + amount + "x ", NamedTextColor.GREEN)
                .append(abilityName)
                .append(Component.text(".", NamedTextColor.GREEN)));
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
