package me.hcfcore.core.blueprint;

import me.hcfcore.core.lang.MessageFormatter;
import me.hcfcore.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /blueprint give &lt;player&gt; &lt;template&gt; -- hands out a marked Beacon (there's no in-game shop for these). */
public final class BlueprintCommand implements CommandExecutor, TabCompleter {

    private final BlueprintManager manager;
    private final Messages messages;
    private final NamespacedKey templateKey;

    public BlueprintCommand(Plugin plugin, BlueprintManager manager, Messages messages) {
        this.manager = manager;
        this.messages = messages;
        this.templateKey = new NamespacedKey(plugin, "blueprint_template");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("hcfcore.blueprint.give")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        if (args.length != 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(MessageFormatter.deserialize("<red>Usage: /blueprint give <player> <template>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        BlueprintTemplate template = manager.getTemplate(args[2]);
        if (template == null) {
            sender.sendMessage(MessageFormatter.deserialize("<red>No blueprint template named '" + args[2] + "'."));
            return true;
        }

        ItemStack item = new ItemStack(Material.BEACON);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MessageFormatter.deserialize(template.displayName()));
        meta.getPersistentDataContainer().set(templateKey, PersistentDataType.STRING, template.name());
        item.setItemMeta(meta);

        for (ItemStack dropped : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), dropped);
        }
        sender.sendMessage(MessageFormatter.deserialize("<green>Gave " + target.getName() + " a " + template.displayName() + " blueprint."));
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
