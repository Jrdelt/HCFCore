package me.vertex.core.shield;

import me.vertex.core.factions.FactionsHook;
import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * Owns {@code /f shield} (the "intercept one FactionsUUID subcommand"
 * pattern {@code FTopCommand}/{@code BaseClaimCommand} use) and extends the
 * *native* {@code /f who} in place, without cancelling it.
 *
 * <p>{@code /f who} is left to run untouched -- unlike {@code /f map},
 * FactionsUUID's own who-output is plain chat, not baked-in hover text, so
 * there's nothing to disassemble around. A one-tick-delayed follow-up
 * message is appended after FactionsUUID's own output prints, showing
 * Shield state/countdown/override for the faction being looked up.
 */
public final class ShieldCommand implements Listener {

    private final Plugin plugin;
    private final ShieldManager shield;
    private final Messages messages;

    public ShieldCommand(Plugin plugin, ShieldManager shield, Messages messages) {
        this.plugin = plugin;
        this.shield = shield;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).trim().split("\\s+");
        if (parts.length < 2 || !isFactionCommand(event.getPlayer(), parts[0])) {
            return;
        }
        if (parts[1].equalsIgnoreCase("shield")) {
            event.setCancelled(true);
            handleShield(event.getPlayer(), parts);
            return;
        }
        if (parts[1].equalsIgnoreCase("who") && parts.length >= 3) {
            // Deliberately not cancelled -- let FactionsUUID's native /f who run first.
            String target = parts[2];
            Player player = event.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> appendWhoInfo(player, target), 1L);
        }
    }

    private void appendWhoInfo(Player viewer, String target) {
        int factionId = FactionsHook.getFactionIdByTag(target);
        if (factionId == FactionsHook.NO_FACTION) {
            // Not a faction tag -- try resolving as a player's faction instead.
            Player targetPlayer = Bukkit.getPlayerExact(target);
            if (targetPlayer != null) {
                factionId = FactionsHook.getFactionId(targetPlayer);
            }
        }
        if (factionId == FactionsHook.NO_FACTION) {
            return;
        }
        sendStatusValues(viewer, factionId);
    }

    private void handleShield(Player player, String[] parts) {
        if (parts.length >= 3 && parts[2].equalsIgnoreCase("admin")) {
            handleAdmin(player, parts);
            return;
        }
        if (parts.length >= 3 && parts[2].equalsIgnoreCase("set")) {
            handleSet(player, parts);
            return;
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.get(player, "shield.no-faction"));
            return;
        }
        sendStatusValues(player, factionId);
    }

    private void sendStatusValues(Player player, int factionId) {
        boolean active = shield.isShieldActive(factionId);
        player.sendMessage(messages.get(player, active ? "shield.status-active" : "shield.status-inactive"));

        Optional<ShieldStorage.OverrideRow> override = shield.override(factionId);
        if (override.isPresent()) {
            player.sendMessage(messages.get(player, "shield.override-active",
                    "state", override.get().forcedState()));
        }

        if (active) {
            long secondsLeft = shield.secondsUntilDeactivation(factionId);
            if (secondsLeft > 0) {
                player.sendMessage(messages.get(player, "shield.time-remaining", "time", formatDuration(secondsLeft)));
            }
        } else {
            if (!shield.isEligible(factionId)) {
                long secondsLeft = Math.max(0L, (shield.eligibleAtMillis(factionId) - System.currentTimeMillis() + 999L) / 1_000L);
                player.sendMessage(messages.get(player, "shield.not-eligible-yet", "time", formatDuration(secondsLeft)));
            } else {
                long secondsUntil = shield.secondsUntilNextActivation(factionId);
                if (secondsUntil >= 0) {
                    player.sendMessage(messages.get(player, "shield.next-activation", "time", formatDuration(secondsUntil)));
                }
            }
        }

        shield.liveSchedule(factionId).ifPresent(schedule ->
                player.sendMessage(messages.get(player, "shield.current-schedule", "schedule", schedule.toString())));
        shield.pendingSchedule(factionId).ifPresent(schedule -> {
            long activatesAt = shield.pendingActivatesAt(factionId);
            long secondsUntilActivates = Math.max(0L, (activatesAt - System.currentTimeMillis() + 999L) / 1_000L);
            player.sendMessage(messages.get(player, "shield.pending-schedule",
                    "schedule", schedule.toString(), "time", formatDuration(secondsUntilActivates)));
        });
    }

    private void handleSet(Player player, String[] parts) {
        if (!player.hasPermission("vertex.shield.set")) {
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.get(player, "shield.no-faction"));
            return;
        }
        if (!FactionsHook.isLeader(player)) {
            player.sendMessage(messages.get(player, "shield.leader-only"));
            return;
        }
        if (parts.length < 5) {
            player.sendMessage(messages.get(player, "shield.set-usage"));
            return;
        }
        try {
            String[] time = parts[3].split(":");
            int hour = Integer.parseInt(time[0]);
            int minute = Integer.parseInt(time[1]);
            int duration = Integer.parseInt(parts[4]);
            ShieldSchedule schedule = ShieldSchedule.ofHourMinuteDuration(hour, minute, duration);
            ShieldManager.SubmitResult result = shield.submitSchedule(factionId, schedule, player.getUniqueId());
            switch (result) {
                case OK -> player.sendMessage(messages.get(player, "shield.submitted", "schedule", schedule.toString()));
                case NOT_ELIGIBLE -> {
                    long secondsLeft = Math.max(0L,
                            (shield.eligibleAtMillis(factionId) - System.currentTimeMillis() + 999L) / 1_000L);
                    player.sendMessage(messages.get(player, "shield.not-eligible-yet", "time", formatDuration(secondsLeft)));
                }
                case NO_ROW -> player.sendMessage(messages.get(player, "shield.no-faction"));
            }
        } catch (RuntimeException invalid) {
            player.sendMessage(messages.get(player, "shield.set-usage"));
        }
    }

    private void handleAdmin(Player player, String[] parts) {
        if (!player.hasPermission("vertex.admin.claims")) {
            plugin.getLogger().warning(player.getName() + " attempted /f shield admin (denied, missing vertex.admin.claims).");
            player.sendMessage(messages.get(player, "general.no-permission"));
            return;
        }
        if (parts.length < 5) {
            player.sendMessage(messages.get(player, "shield.admin-usage"));
            return;
        }
        String tag = parts[3];
        String action = parts[4].toLowerCase(java.util.Locale.ROOT);
        int factionId = FactionsHook.getFactionIdByTag(tag);
        if (factionId == FactionsHook.NO_FACTION) {
            player.sendMessage(messages.get(player, "shield.admin-unknown-faction"));
            return;
        }
        switch (action) {
            case "active" -> {
                plugin.getLogger().warning(player.getName() + " forced Shield ACTIVE for faction " + tag + " via /f shield admin.");
                shield.applyOverride(factionId, true, player.getUniqueId());
                player.sendMessage(messages.get(player, "shield.admin-forced", "faction", tag, "state", "ACTIVE"));
            }
            case "inactive" -> {
                plugin.getLogger().warning(player.getName() + " forced Shield INACTIVE for faction " + tag + " via /f shield admin.");
                shield.applyOverride(factionId, false, player.getUniqueId());
                player.sendMessage(messages.get(player, "shield.admin-forced", "faction", tag, "state", "INACTIVE"));
            }
            case "clear" -> {
                plugin.getLogger().warning(player.getName() + " cleared the Shield override for faction " + tag + " via /f shield admin.");
                shield.removeOverride(factionId, player.getUniqueId());
                player.sendMessage(messages.get(player, "shield.admin-cleared", "faction", tag));
            }
            default -> player.sendMessage(messages.get(player, "shield.admin-usage"));
        }
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    private boolean isFactionCommand(Player player, String command) {
        String normalized = command.contains(":") ? command.substring(command.indexOf(':') + 1) : command;
        return plugin.getConfig()
                .getStringList("factions.command-aliases").stream()
                .anyMatch(alias -> alias.equalsIgnoreCase(normalized));
    }
}
