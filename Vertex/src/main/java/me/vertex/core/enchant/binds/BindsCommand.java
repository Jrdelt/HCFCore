package me.vertex.core.enchant.binds;

import me.vertex.core.enchant.EnchantManager;
import me.vertex.core.enchant.binds.menu.BindsHomeMenu;
import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /binds} opens the Bind Management GUI. */
public final class BindsCommand implements CommandExecutor {

    private final EnchantManager enchants;
    private final BindManager binds;
    private final Messages messages;

    public BindsCommand(EnchantManager enchants, BindManager binds, Messages messages) {
        this.enchants = enchants;
        this.binds = binds;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        PlayerBinds playerBinds = binds.get(player.getUniqueId());
        if (playerBinds == null) {
            player.sendMessage(messages.get(player, "binds.not-loaded"));
            return true;
        }
        BindsHomeMenu.open(player, enchants, binds, playerBinds, messages);
        return true;
    }
}
