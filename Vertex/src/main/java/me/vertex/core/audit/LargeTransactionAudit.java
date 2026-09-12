package me.vertex.core.audit;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;

/** Compact, indefinite audit trail for large GC transfers. */
public final class LargeTransactionAudit {
    private LargeTransactionAudit() {
    }

    public static void record(Plugin plugin, long amount, String type, OfflinePlayer first, OfflinePlayer second) {
        if (!plugin.getConfig().getBoolean("transaction-audit.enabled", true)) return;
        long threshold = Math.max(0L, plugin.getConfig().getLong("transaction-audit.minimum-gc", 50L));
        if (amount <= threshold) return;

        String firstName = name(first);
        String secondName = second == null ? "-" : name(second);
        Player firstOnline = first == null ? null : Bukkit.getPlayer(first.getUniqueId());
        Player secondOnline = second == null ? null : Bukkit.getPlayer(second.getUniqueId());
        String ipPair = address(firstOnline) + "<->" + address(secondOnline);
        String line = Instant.now() + " | " + firstName + ": " + amount + " GC | " + type
                + " | " + secondName + " | IPs: " + ipPair + System.lineSeparator();

        LocalDate utcDate = LocalDate.now(ZoneOffset.UTC);
        String week = String.format(java.util.Locale.ROOT, "%04d-W%02d",
                utcDate.get(IsoFields.WEEK_BASED_YEAR), utcDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        String directory = plugin.getConfig().getString("transaction-audit.log-directory", "coinflips");
        Path log = plugin.getDataFolder().toPath().resolve(directory)
                .resolve("transaction-audit-" + week + ".log");
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            plugin.getLogger().warning("Could not append large transaction audit: " + error.getMessage());
        }

        String alert = "[Transaction Audit] " + firstName + ": " + amount + " GC | " + type
                + " | " + secondName;
        String notifyPermission = plugin.getConfig().getString("transaction-audit.notify-permission",
                "vertex.transaction.audit");
        String ipPermission = plugin.getConfig().getString("transaction-audit.ip-permission",
                "vertex.transaction.audit.ip");
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.hasPermission(notifyPermission)) continue;
            String message = alert;
            if (staff.hasPermission(ipPermission)) message += " | IPs: " + ipPair;
            staff.sendMessage(message);
        }
    }

    private static String name(OfflinePlayer player) {
        if (player == null || player.getName() == null || player.getName().isBlank()) return "-";
        return player.getName().replace('|', '/');
    }

    private static String address(Player player) {
        if (player == null || player.getAddress() == null || player.getAddress().getAddress() == null) {
            return "unknown";
        }
        return player.getAddress().getAddress().getHostAddress();
    }
}
