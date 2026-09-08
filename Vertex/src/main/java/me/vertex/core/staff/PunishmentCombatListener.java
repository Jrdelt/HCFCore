package me.vertex.core.staff;

import me.vertex.core.lang.Messages;
import me.vertex.core.pvp.CombatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Blocks a staff kick or ban while its target is still combat-tagged.
 *
 * <p>Kicking someone mid-fight hands them the escape they were trying to
 * earn: the tag dies with the session, so their opponent loses the kill and
 * the target keeps their inventory. Staff are told how long is left and can
 * simply run the command again once the tag expires.
 *
 * <p>Vertex owns neither {@code /kick} nor {@code /ban}, so this intercepts
 * the configured aliases before the owning plugin sees them. Because the
 * check runs at dispatch time it is inherently a check at the moment of
 * execution, not a stale snapshot. Mutes are deliberately never guarded --
 * silencing someone does not let them leave a fight.
 */
public final class PunishmentCombatListener implements Listener {

    public static final String BYPASS_PERMISSION = "vertex.staff.punish.bypasscombat";

    private final org.bukkit.plugin.Plugin plugin;
    private final CombatManager combatManager;
    private final Messages messages;

    public PunishmentCombatListener(org.bukkit.plugin.Plugin plugin, CombatManager combatManager, Messages messages) {
        this.plugin = plugin;
        this.combatManager = combatManager;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("staff.punishment-combat-guard.enabled", true)) {
            return;
        }
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2 || !isGuardedCommand(parts[0])) {
            return;
        }

        Player target = Bukkit.getPlayerExact(parts[1]);
        // An offline target has no live tag to protect: their logout was
        // already handled by the combat-logging penalty, so the punishment
        // should go through rather than being blocked forever.
        if (target == null || !combatManager.isTagged(target.getUniqueId())) {
            return;
        }

        Player staff = event.getPlayer();
        long secondsLeft = TimeUnit.MILLISECONDS.toSeconds(
                combatManager.remainingMillis(target.getUniqueId())) + 1;

        if (staff.hasPermission(BYPASS_PERMISSION)) {
            // Permitted, but never silent -- an override that skips the
            // protection is exactly the action an investigation needs to see.
            plugin.getLogger().warning(staff.getName() + " used " + BYPASS_PERMISSION + " to run '"
                    + event.getMessage() + "' on " + target.getName() + ", who was combat-tagged with "
                    + secondsLeft + "s left.");
            staff.sendMessage(messages.get(staff, "staff.punish-combat-bypassed",
                    "player", target.getName(), "seconds", String.valueOf(secondsLeft)));
            return;
        }

        event.setCancelled(true);
        staff.sendMessage(messages.get(staff, "staff.punish-combat-blocked",
                "player", target.getName(), "seconds", String.valueOf(secondsLeft)));
    }

    private boolean isGuardedCommand(String raw) {
        String command = raw.toLowerCase(Locale.ROOT);
        int namespace = command.indexOf(':');
        if (namespace >= 0) {
            command = command.substring(namespace + 1);
        }
        List<String> guarded = plugin.getConfig().getStringList("staff.punishment-combat-guard.commands");
        for (String candidate : guarded) {
            if (candidate.toLowerCase(Locale.ROOT).equals(command)) {
                return true;
            }
        }
        return false;
    }
}
