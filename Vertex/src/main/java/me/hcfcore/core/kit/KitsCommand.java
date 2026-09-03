package me.hcfcore.core.kit;

import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.UserManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class KitsCommand implements CommandExecutor {

    private final Plugin plugin;
    private final KitManager kitManager;
    private final UserManager userManager;
    private final Messages messages;

    public KitsCommand(Plugin plugin, KitManager kitManager, UserManager userManager, Messages messages) {
        this.plugin = plugin;
        this.kitManager = kitManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.getChat(sender, "general.players-only"));
            return true;
        }
        KitsMenu.open(player, plugin, kitManager, userManager, messages);
        return true;
    }
}
