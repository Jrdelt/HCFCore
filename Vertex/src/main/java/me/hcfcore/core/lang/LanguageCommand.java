package me.hcfcore.core.lang;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * /language [code] -- lets any player pick their own locale. No permission
 * node: this is a personal preference, not an admin action.
 */
public final class LanguageCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final Storage storage;
    private final UserManager userManager;
    private final Messages messages;

    public LanguageCommand(Plugin plugin, Storage storage, UserManager userManager, Messages messages) {
        this.plugin = plugin;
        this.storage = storage;
        this.userManager = userManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }

        String available = String.join(", ", messages.getAvailableLocales());

        if (args.length == 0) {
            User user = userManager.get(player.getUniqueId());
            String current = user != null && user.getLocale() != null ? user.getLocale() : messages.getDefaultLocale();
            player.sendMessage(messages.get(player, "language.current", "locale", current));
            player.sendMessage(messages.get(player, "language.available", "locales", available));
            return true;
        }

        String code = args[0].toLowerCase(Locale.ROOT);
        if (!messages.isAvailable(code)) {
            player.sendMessage(messages.get(player, "language.unknown", "code", args[0], "locales", available));
            return true;
        }

        User user = userManager.get(player.getUniqueId());
        if (user != null) {
            user.setLocale(code);
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                storage.saveLocale(player.getUniqueId(), code);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist locale for " + player.getUniqueId(), e);
            }
        });

        player.sendMessage(messages.get(player, "language.changed", "locale", code));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        String partial = args[0].toLowerCase(Locale.ROOT);
        for (String locale : messages.getAvailableLocales()) {
            if (locale.startsWith(partial)) {
                matches.add(locale);
            }
        }
        return matches;
    }
}
