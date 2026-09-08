package me.vertex.core.mine;

import me.vertex.core.booster.BoosterService;
import me.vertex.core.lang.Messages;
import me.vertex.core.menu.MenuRegistry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code /mines} for players; {@code /mines wand|cancel|list} for staff. */
public final class MinesCommand implements CommandExecutor, TabCompleter {

    public static final String ADMIN_PERMISSION = "vertex.mines.admin";
    private static final List<String> ADMIN_ACTIONS = List.of("wand", "cancel", "list");

    private final MineManager mines;
    private final MineKothManager koths;
    private final HotZoneManager hotZones;
    private final BoosterService boosters;
    private final Messages messages;
    private final MenuRegistry menus;

    public MinesCommand(MineManager mines, MineKothManager koths, HotZoneManager hotZones,
            BoosterService boosters, Messages messages, MenuRegistry menus) {
        this.mines = mines;
        this.koths = koths;
        this.hotZones = hotZones;
        this.boosters = boosters;
        this.messages = messages;
        this.menus = menus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(messages.get(sender, "general.players-only"));
            return true;
        }
        if (!mines.isEnabled()) {
            player.sendMessage(messages.get(player, "mines.disabled"));
            return true;
        }
        if (args.length == 0) {
            sendOverview(player);
            return true;
        }
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            // Never name the staff subcommands to someone who cannot use them.
            sendOverview(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "wand" -> {
                if (args.length < 2) {
                    player.sendMessage(messages.get(player, "mines.usage-admin"));
                    return true;
                }
                boolean kothZone = args.length >= 3 && args[2].equalsIgnoreCase("koth");
                mines.beginSelection(player, args[1], kothZone);
            }
            case "cancel" -> player.sendMessage(messages.get(player,
                    mines.cancelSelection(player) ? "mines.selection-cancelled" : "mines.selection-none"));
            case "list" -> {
                for (MineRegion region : mines.regions()) {
                    player.sendMessage(messages.get(player, region.isDefined()
                                    ? "mines.list-defined" : "mines.list-undefined",
                            "mine", region.id(),
                            "world", region.world() == null || region.world().isBlank() ? "-" : region.world()));
                }
            }
            default -> player.sendMessage(messages.get(player, "mines.usage-admin"));
        }
        return true;
    }

    private void sendOverview(Player player) {
        MinesMenu.openOverview(player, mines, koths, hotZones, boosters, messages, menus);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            for (String action : ADMIN_ACTIONS) {
                if (action.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(action);
                }
            }
            return options;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("wand")) {
            return mines.regions().stream()
                    .map(MineRegion::id)
                    .filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("wand")) {
            return "koth".startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of("koth") : List.of();
        }
        return List.of();
    }
}
