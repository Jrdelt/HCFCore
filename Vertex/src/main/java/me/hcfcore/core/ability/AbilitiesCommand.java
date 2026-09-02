package me.hcfcore.core.ability;

import me.hcfcore.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class AbilitiesCommand implements CommandExecutor {

    private final Plugin plugin;
    private final AbilityManager abilityManager;
    private final Messages messages;

    public AbilitiesCommand(Plugin plugin, AbilityManager abilityManager, Messages messages) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        AbilitiesMenu.open(player, plugin, abilityManager, messages);
        return true;
    }
}
