package me.hcfcore.core.lang;

import me.hcfcore.core.storage.Storage;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final Set<CompletableFuture<Void>> pendingWrites = ConcurrentHashMap.newKeySet();
    // Chains each player's saves so a slower earlier write can't complete
    // after a later one and leave a stale locale value in storage.
    private final java.util.Map<UUID, CompletableFuture<Void>> writeChains = new ConcurrentHashMap<>();

    public LanguageCommand(Plugin plugin, Storage storage, UserManager userManager, Messages messages) {
        this.plugin = plugin;
        this.storage = storage;
        this.userManager = userManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.getChat(sender, "general.players-only"));
            return true;
        }

        String available = String.join(", ", messages.getAvailableLocales());

        if (args.length == 0) {
            User user = userManager.get(player.getUniqueId());
            String current = user != null && user.getLocale() != null ? user.getLocale() : messages.getDefaultLocale();
            player.sendMessage(messages.getChat(player, "language.current", "locale", current));
            player.sendMessage(messages.getChat(player, "language.available", "locales", available));
            return true;
        }

        String code = args[0].toLowerCase(Locale.ROOT);
        if (!messages.isAvailable(code)) {
            player.sendMessage(messages.getChat(player, "language.unknown", "code", args[0], "locales", available));
            return true;
        }

        User user = userManager.get(player.getUniqueId());
        if (user != null) {
            user.setLocale(code);
        }
        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> previous = writeChains.getOrDefault(uuid, CompletableFuture.completedFuture(null));
        CompletableFuture<Void> write = previous.handle((ignored, error) -> null).thenRunAsync(() -> {
            try {
                storage.saveLocale(uuid, code);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist locale for " + uuid, e);
            }
        });
        writeChains.put(uuid, write);
        pendingWrites.add(write);
        write.whenComplete((ignored, error) -> pendingWrites.remove(write));

        player.sendMessage(messages.getChat(player, "language.changed", "locale", code));
        return true;
    }

    public void awaitWrites() {
        try {
            CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException e) {
            plugin.getLogger().warning("Timed out waiting for locale writes during shutdown.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed while waiting for locale writes.", e);
        }
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
