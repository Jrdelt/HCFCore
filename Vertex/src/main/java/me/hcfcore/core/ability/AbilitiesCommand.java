package me.hcfcore.core.ability;

import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class AbilitiesCommand implements CommandExecutor {

    private final Plugin plugin;
    private final AbilityManager abilityManager;

    public AbilitiesCommand(Plugin plugin, AbilityManager abilityManager) {
        this.plugin = plugin;
        this.abilityManager = abilityManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command."));
            return true;
        }
        AbilitiesMenu.open(player, plugin, abilityManager);
        return true;
    }
}
