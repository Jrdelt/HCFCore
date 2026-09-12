package me.vertex.core.command;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.server.TabCompleteEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps administration-only roots out of ordinary players' command trees
 * without attaching a Bukkit command permission. The latter would replace
 * each command's translated, command-specific denial message with Bukkit's
 * generic permission message when somebody manually enters a command.
 */
public final class CommandVisibilityListener implements Listener {

    private static final List<String> FA_PERMISSIONS = List.of(
            "vertex.fa.*", "vertex.fa.help", "vertex.fa.info", "vertex.fa.grace", "vertex.fa.power",
            "vertex.fa.claim", "vertex.fa.unclaim", "vertex.fa.disband", "vertex.fa.relation",
            "vertex.fa.shield", "vertex.fa.bank", "vertex.fa.vault", "vertex.fa.upgrades",
            "vertex.fa.members", "vertex.fa.combat", "vertex.fa.logs", "vertex.fa.network",
            "vertex.fa.season");

    private static final Map<String, List<String>> STAFF_ROOT_PERMISSIONS = Map.ofEntries(
            Map.entry("fa", FA_PERMISSIONS),
            Map.entry("fadmin", FA_PERMISSIONS),
            Map.entry("s", List.of("vertex.server.warp")),
            Map.entry("fpowerbooster", List.of("vertex.fpowerbooster.give")),
            Map.entry("vertex", List.of("vertex.admin")),
            Map.entry("uncombat", List.of("vertex.combat.uncombat")),
            Map.entry("combatcheck", List.of("vertex.combat.check")),
            Map.entry("combattag", List.of("vertex.combat.tag")),
            Map.entry("getitem", List.of("vertex.ability.give")),
            Map.entry("chunkcollector", List.of("vertex.collector.give")),
            Map.entry("chunkbusters", List.of("vertex.chunkbuster.give")),
            Map.entry("chunkbuster", List.of("vertex.chunkbuster.give")),
            Map.entry("blueprint", List.of("vertex.blueprint.give", "vertex.blueprint.cooldown.remove")),
            Map.entry("backpack", List.of("vertex.backpack.give", "vertex.backpack.debug")),
            Map.entry("wand", List.of("vertex.wand.give")),
            Map.entry("reboot", List.of("vertex.reboot.start")),
            Map.entry("rollback", List.of("vertex.staff.rollback")),
            Map.entry("staff", List.of("vertex.staff.mode")),
            Map.entry("vanish", List.of("vertex.staff.vanish")),
            Map.entry("staffchat", List.of("vertex.staff.staffchat")),
            Map.entry("staffbuild", List.of("vertex.staff.staffbuild")),
            Map.entry("freeze", List.of("vertex.staff.freeze")),
            Map.entry("invsee", List.of("vertex.staff.invsee")),
            Map.entry("endersee", List.of("vertex.staff.endersee")),
            Map.entry("tradelogs", List.of("vertex.trade.staff.history")),
            Map.entry("ftopforcecheck", List.of("vertex.ftop.forcecheck")),
            Map.entry("dupe", List.of("vertex.dupe.inspect", "vertex.dupe.resolve")));

    @EventHandler
    public void onCommandTreeSend(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        event.getCommands().removeIf(command -> !maySee(player, command));
    }

    @EventHandler(ignoreCancelled = true)
    public void onRootTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player player)) {
            return;
        }
        String buffer = event.getBuffer();
        if (!buffer.startsWith("/") || buffer.indexOf(' ') >= 0) {
            return;
        }
        event.setCompletions(event.getCompletions().stream()
                .filter(completion -> maySee(player, completion))
                .toList());
    }

    private static boolean maySee(Player player, String rawLabel) {
        List<String> permissions = STAFF_ROOT_PERMISSIONS.get(normalize(rawLabel));
        return permissions == null || permissions.stream().anyMatch(player::hasPermission);
    }

    private static String normalize(String rawLabel) {
        String label = rawLabel.startsWith("/") ? rawLabel.substring(1) : rawLabel;
        int namespace = label.indexOf(':');
        if (namespace >= 0) {
            label = label.substring(namespace + 1);
        }
        return label.toLowerCase(Locale.ROOT);
    }
}
