package me.vertex.core.blueprint;

import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Admin commands for giving Blueprint items and removing placement cooldowns. */
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
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return give(sender, args[1], args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cooldown") && args[1].equalsIgnoreCase("remove")) {
            return removeCooldown(sender, args[2]);
        }
        sender.sendMessage(messages.get(sender, "blueprint.command-usage"));
        return true;
    }

    private boolean give(CommandSender sender, String playerName, String templateName) {
        if (!sender.hasPermission("vertex.blueprint.give")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        BlueprintTemplate template = manager.getTemplate(templateName);
        if (template == null) {
            sender.sendMessage(messages.get(sender, "blueprint.template-not-found", "template", templateName));
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
        sender.sendMessage(messages.get(sender, "blueprint.gave", "player", target.getName(), "template", template.displayName()));
        return true;
    }

    private boolean removeCooldown(CommandSender sender, String playerName) {
        if (!sender.hasPermission("vertex.blueprint.cooldown.remove")) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        Player onlineTarget = Bukkit.getPlayerExact(playerName);
        OfflinePlayer target = onlineTarget != null ? onlineTarget : Bukkit.getOfflinePlayer(playerName);
        if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            sender.sendMessage(messages.get(sender, "general.player-not-found"));
            return true;
        }
        boolean removed = manager.clearCooldown(target.getUniqueId());
        sender.sendMessage(messages.get(sender,
                removed ? "blueprint.cooldown-removed" : "blueprint.cooldown-none",
                "player", target.getName() == null ? playerName : target.getName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            if ("give".startsWith(partial) && sender.hasPermission("vertex.blueprint.give")) {
                matches.add("give");
            }
            if ("cooldown".startsWith(partial) && sender.hasPermission("vertex.blueprint.cooldown.remove")) {
                matches.add("cooldown");
            }
            return matches;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return onlinePlayerMatches(args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cooldown")) {
            return "remove".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("remove") : List.of();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            return manager.getTemplateNames().stream()
                    .filter(template -> template.toLowerCase(Locale.ROOT).startsWith(partial))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("cooldown") && args[1].equalsIgnoreCase("remove")) {
            return knownPlayerMatches(args[2]);
        }
        return List.of();
    }

    private List<String> onlinePlayerMatches(String input) {
        List<String> matches = new ArrayList<>();
        String partial = input.toLowerCase(Locale.ROOT);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                matches.add(player.getName());
            }
        }
        return matches;
    }

    private List<String> knownPlayerMatches(String input) {
        List<String> matches = onlinePlayerMatches(input);
        String partial = input.toLowerCase(Locale.ROOT);
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            String name = player.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).startsWith(partial)
                    && matches.stream().noneMatch(name::equalsIgnoreCase)) {
                matches.add(name);
            }
        }
        return matches;
    }
}
