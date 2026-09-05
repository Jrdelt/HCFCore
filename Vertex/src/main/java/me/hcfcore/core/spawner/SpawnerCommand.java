package me.hcfcore.core.spawner;

import me.hcfcore.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SpawnerCommand implements CommandExecutor {

    private final SpawnerManager spawnerManager;
    private final Messages messages;

    public SpawnerCommand(SpawnerManager spawnerManager, Messages messages) {
        this.spawnerManager = spawnerManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.getChat(sender, "general.players-only"));
            return true;
        }
        SpawnerShopMenu.open(player, spawnerManager, messages);
        return true;
    }
}
