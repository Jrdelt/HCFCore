package me.hcfcore.core.ability;

import me.hcfcore.core.kit.Kit;
import me.hcfcore.core.kit.KitManager;
import me.hcfcore.core.lang.Messages;
import me.hcfcore.core.user.User;
import me.hcfcore.core.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class CooldownsCommand implements CommandExecutor {

    private final KitManager kitManager;
    private final AbilityManager abilityManager;
    private final UserManager userManager;
    private final Messages messages;

    public CooldownsCommand(KitManager kitManager, AbilityManager abilityManager,
                            UserManager userManager, Messages messages) {
        this.kitManager = kitManager;
        this.abilityManager = abilityManager;
        this.userManager = userManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.getChat(sender, "general.players-only"));
            return true;
        }

        User user = userManager.get(player.getUniqueId());
        if (user == null) {
            player.sendMessage(messages.getChat(player, "general.data-unavailable"));
            return true;
        }

        long now = System.currentTimeMillis();
        List<CooldownEntry> entries = new ArrayList<>();
        for (Kit kit : kitManager.getKits().values()) {
            addEntry(entries, simplify(kit.getName()), user.getCooldownExpiry(kit.getName().toLowerCase(Locale.ROOT)), now);
        }
        for (Ability ability : abilityManager.getAbilities().values()) {
            addEntry(entries, simplify(ability.getId()), user.getCooldownExpiry("ability:" + ability.getId()), now);
        }

        long globalRemaining = abilityManager.globalCooldownRemainingMillis(player.getUniqueId());
        if (globalRemaining > 0) {
            entries.add(new CooldownEntry("Global Abilities", ceilSeconds(globalRemaining)));
        }

        entries.sort(Comparator.comparing(CooldownEntry::name));
        player.sendMessage(messages.getChat(player, "cooldowns.title"));
        if (entries.isEmpty()) {
            player.sendMessage(messages.getChat(player, "cooldowns.none"));
            return true;
        }
        for (CooldownEntry entry : entries) {
            player.sendMessage(Component.text(entry.name(), NamedTextColor.GRAY)
                    .append(Component.text(": ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(entry.seconds() + "s", NamedTextColor.YELLOW)));
        }
        return true;
    }

    private static void addEntry(List<CooldownEntry> entries, String name, long expiry, long now) {
        if (expiry > now) {
            entries.add(new CooldownEntry(name, ceilSeconds(expiry - now)));
        }
    }

    private static long ceilSeconds(long millis) {
        return (millis + 999L) / 1000L;
    }

    private static String simplify(String value) {
        String[] words = value.replace('_', '-').split("-");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private record CooldownEntry(String name, long seconds) {
    }
}
