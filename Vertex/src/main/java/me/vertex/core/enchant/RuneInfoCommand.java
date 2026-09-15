package me.vertex.core.enchant;

import me.vertex.core.enchant.menu.RuneInfoMenu;
import me.vertex.core.lang.Messages;
import me.vertex.core.user.UserManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Inspects the player's main-hand item and opens {@link RuneInfoMenu} listing every applied custom enchant. */
public final class RuneInfoCommand implements CommandExecutor {

    private final EnchantManager manager;
    private final RuneCooldownStore cooldowns;
    private final UserManager users;
    private final Messages messages;

    public RuneInfoCommand(EnchantManager manager, RuneCooldownStore cooldowns, UserManager users, Messages messages) {
        this.manager = manager;
        this.cooldowns = cooldowns;
        this.users = users;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        Map<String, Integer> applied = manager.enchantsOf(item);
        if (applied.isEmpty()) {
            player.sendMessage(messages.get(player, "rune.info-empty"));
            return true;
        }
        List<RuneInfoMenu.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : applied.entrySet()) {
            if (manager.definition(entry.getKey()) != null) {
                entries.add(new RuneInfoMenu.Entry(entry.getKey(), entry.getValue()));
            }
        }
        RuneInfoMenu.openHome(player, manager, cooldowns, users, messages, entries);
        return true;
    }
}
