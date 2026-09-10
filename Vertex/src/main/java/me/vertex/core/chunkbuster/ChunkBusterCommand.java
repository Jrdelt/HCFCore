package me.vertex.core.chunkbuster;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /chunkbuster permission <role> <allow|deny>} -- the one command
 * this phase actually needs, per the spec's "Leadership can control which
 * faction roles can use Chunk Busters."
 *
 * <p>Deliberately a standalone command rather than an addition to {@code
 * RallyPermissionMenu}'s {@code CUSTOM_ACTIONS} list: that menu is scoped
 * to Rally's own action set (rally-set/spawner-add/collector-open/
 * bank-deposit/...) and entangling a completely unrelated destructive
 * item's permission with it would make both harder to reason about and
 * revert independently. A minimal standalone command matches the spec's
 * "new small permission-matrix storage, since none exists -- scope
 * minimally" instruction and keeps Chunk Busters self-contained within
 * this one package.
 *
 * <p>Faction-leader only (mirrors {@code BaseClaimCommand}'s leader-only
 * actions) -- edits only the caller's own faction's row. Every change is
 * logged at INFO to the console for a plain audit trail, since it changes
 * who can trigger an irreversible, drop-free area-clear.
 */
public final class ChunkBusterCommand implements CommandExecutor, TabCompleter {

    private final Plugin plugin;
    private final ChunkBusterManager manager;
    private final Messages messages;

    public ChunkBusterCommand(Plugin plugin, ChunkBusterManager manager, Messages messages) {
        this.plugin = plugin;
        this.manager = manager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (args.length != 3 || !args[0].equalsIgnoreCase("permission")) {
            sender.sendMessage(messages.get(sender, "chunkbuster.permission-usage"));
            return true;
        }
        if (!player.hasPermission("vertex.chunkbuster.permission")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return true;
        }
        if (!FactionsHook.isLeader(player)) {
            player.sendMessage(messages.get(player, "chunkbuster.permission-leader-only"));
            return true;
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.get(player, "baseclaim.no-faction"));
            return true;
        }
        String role = args[1].toLowerCase(Locale.ROOT);
        if (!ChunkBusterManager.ROLES.contains(role)) {
            player.sendMessage(messages.get(player, "chunkbuster.permission-invalid-role"));
            return true;
        }
        Boolean allowed = parseAllowDeny(args[2]);
        if (allowed == null) {
            player.sendMessage(messages.get(player, "chunkbuster.permission-invalid-value"));
            return true;
        }
        manager.setRolePermission(factionId, role, allowed);
        player.sendMessage(messages.get(player, "chunkbuster.permission-updated", "role", role,
                "state", allowed ? "allowed" : "denied"));
        plugin.getLogger().info(player.getName() + " set the Chunk Buster permission for role '" + role
                + "' to " + allowed + " in faction #" + factionId + ".");
        return true;
    }

    private static Boolean parseAllowDeny(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "allow", "true", "yes" -> Boolean.TRUE;
            case "deny", "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("permission");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("permission")) {
            return new ArrayList<>(ChunkBusterManager.ROLES);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("permission")) {
            return List.of("allow", "deny");
        }
        return List.of();
    }
}
