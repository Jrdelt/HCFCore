package me.vertex.core.backpack;

import me.vertex.core.lang.Messages;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** `/filter <material>` toggles a material that an equipped Backpack discards. */
public final class BackpackFilterCommand implements CommandExecutor, TabCompleter {
    private final BackpackFilterManager filters;
    private final Messages messages;

    public BackpackFilterCommand(BackpackFilterManager filters, Messages messages) {
        this.filters = filters;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (args.length == 0) {
            String listed = filters.filtered(player.getUniqueId()).stream().map(Material::name).sorted()
                    .reduce((left, right) -> left + ", " + right).orElse(messages.getRaw(player, "backpack.filter-none"));
            player.sendMessage(messages.get(player, "backpack.filter-list", "items", listed));
            player.sendMessage(messages.get(player, "backpack.filter-usage"));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("clear")) {
            int removed = filters.clear(player.getUniqueId());
            player.sendMessage(messages.get(player, "backpack.filter-cleared", "count", String.valueOf(removed)));
            return true;
        }
        if (args.length != 1) {
            player.sendMessage(messages.get(player, "backpack.filter-usage"));
            return true;
        }
        Material material = Material.matchMaterial(args[0]);
        if (material == null || material.isAir()) {
            player.sendMessage(messages.get(player, "backpack.filter-invalid-item"));
            return true;
        }
        boolean enabled = filters.toggle(player.getUniqueId(), material);
        player.sendMessage(messages.get(player, enabled ? "backpack.filter-enabled" : "backpack.filter-disabled",
                "item", material.name()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String partial = args[0].toLowerCase(Locale.ROOT);
        return java.util.stream.Stream.concat(
                        java.util.stream.Stream.of("clear"),
                        java.util.Arrays.stream(Material.values()).filter(material -> !material.isAir()).map(Material::name))
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                .sorted(Comparator.naturalOrder())
                .limit(100)
                .toList();
    }
}
