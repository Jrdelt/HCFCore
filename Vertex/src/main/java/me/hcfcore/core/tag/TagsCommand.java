package me.hcfcore.core.tag;

import me.hcfcore.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class TagsCommand implements CommandExecutor {

    private final TagManager tagManager;
    private final Messages messages;

    public TagsCommand(TagManager tagManager, Messages messages) {
        this.tagManager = tagManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.getChat(sender, "general.players-only"));
            return true;
        }
        TagMenu.open(player, tagManager, messages, TagMenuState.initial());
        return true;
    }
}
