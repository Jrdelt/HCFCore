package me.vertex.core.resetvault;

import me.vertex.core.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Command executor and tab completer for /rv (Reset Vault).
 * Enforces permission tiers and conceals recovery commands from unauthorized callers.
 */
public final class ResetVaultCommand implements CommandExecutor, TabCompleter {

    private final ResetVaultManager manager;
    private final Messages messages;
    private final ResetVaultBlockListener blockListener;

    public ResetVaultCommand(ResetVaultManager manager, Messages messages, ResetVaultBlockListener blockListener) {
        this.manager = manager;
        this.messages = messages;
        this.blockListener = blockListener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messages.get(sender, "general.players-only"));
                return true;
            }
            if (!player.hasPermission("vertex.reset.use")) {
                player.sendMessage(messages.get(player, "general.no-permission"));
                return true;
            }
            if (manager.phase() == ResetVaultPhase.CLOSED) {
                player.sendMessage(messages.get(player, "reset-vault.phase.closed"));
                return true;
            }
            if (manager.phase() == ResetVaultPhase.BACKUP_RUNNING) {
                player.sendMessage(messages.get(player, "reset-vault.phase.backup-running"));
                return true;
            }
            if (manager.phase() == ResetVaultPhase.RECOVERY_LOCKED) {
                player.sendMessage(messages.get(player, "reset-vault.phase.recovery-locked"));
                return true;
            }
            ResetVaultMenu.open(player, manager, messages, 0, -1);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "status" -> {
                sender.sendMessage(messages.get(sender, "reset-vault.status-header"));
                sender.sendMessage(messages.get(sender, "reset-vault.status-phase",
                        "phase", messages.getRaw(sender, manager.phase().langKey())));
                if (sender.hasPermission("vertex.reset.admin")) {
                    sender.sendMessage(messages.get(sender, "reset-vault.status-admin",
                            "blocks", String.valueOf(manager.accessBlockCount()),
                            "sessions", String.valueOf(manager.activeSessionCount())));
                }
                return true;
            }

            case "give" -> {
                if (!sender.hasPermission("vertex.developer")) {
                    sender.sendMessage(messages.get(sender, "general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(messages.get(sender, "reset-vault.give.usage"));
                    return true;
                }
                String targetName = args[1];
                if (!manager.isGiveAllowed(targetName)) {
                    sender.sendMessage(messages.get(sender, "reset-vault.give.not-allowlisted", "player", targetName));
                    return true;
                }
                Player target = Bukkit.getPlayer(targetName);
                if (target == null) {
                    sender.sendMessage(messages.get(sender, "general.player-not-found"));
                    return true;
                }
                ItemStack blockItem = blockListener.createAccessBlockItem();
                me.vertex.core.storage.ItemGiver.give(target, List.of(blockItem));
                sender.sendMessage(messages.get(sender, "reset-vault.give.success", "player", target.getName()));
                target.sendMessage(messages.get(target, "reset-vault.give.received"));
                return true;
            }

            case "token" -> {
                if (!sender.hasPermission("vertex.reset.admin")) {
                    sender.sendMessage(messages.get(sender, "general.no-permission"));
                    return true;
                }
                if (args.length < 3 || !args[1].equalsIgnoreCase("give")) {
                    sender.sendMessage(messages.get(sender, "reset-vault.token.usage"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    sender.sendMessage(messages.get(sender, "general.player-not-found"));
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Integer.parseInt(args[3]));
                    } catch (NumberFormatException ignored) {}
                }
                List<ItemStack> tokens = new ArrayList<>(amount);
                for (int i = 0; i < amount; i++) {
                    tokens.add(manager.tokenManager().createToken(sender));
                }
                me.vertex.core.storage.ItemGiver.give(target, tokens);
                sender.sendMessage(messages.get(sender, "reset-vault.token.given",
                        "player", target.getName(),
                        "amount", String.valueOf(amount)));
                target.sendMessage(messages.get(target, "reset-vault.token.received",
                        "amount", String.valueOf(amount)));
                return true;
            }

            case "open" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(messages.get(sender, "general.players-only"));
                    return true;
                }
                if (!player.hasPermission("vertex.reset.admin.edit")) {
                    player.sendMessage(messages.get(player, "general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(messages.get(player, "reset-vault.admin.open-usage"));
                    return true;
                }
                String targetName = args[1];
                OfflinePlayer offline = Bukkit.getOfflinePlayer(targetName);
                if (offline.getUniqueId() == null) {
                    player.sendMessage(messages.get(player, "general.player-not-found"));
                    return true;
                }
                AdminVaultMenu.open(player, offline.getUniqueId(),
                        offline.getName() != null ? offline.getName() : targetName, manager, messages, 0);
                return true;
            }

            case "setphase" -> {
                if (!sender.hasPermission("vertex.reset.admin")) {
                    sender.sendMessage(messages.get(sender, "general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(messages.get(sender, "reset-vault.setphase.usage"));
                    return true;
                }
                try {
                    ResetVaultPhase newPhase = ResetVaultPhase.valueOf(args[1].toUpperCase(Locale.ROOT));
                    manager.setPhase(newPhase);
                    sender.sendMessage(messages.get(sender, "reset-vault.setphase.success",
                            "phase", messages.getRaw(sender, newPhase.langKey())));
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(messages.get(sender, "reset-vault.setphase.invalid"));
                }
                return true;
            }

            case "backup" -> {
                if (!sender.hasPermission("vertex.reset.admin")) {
                    sender.sendMessage(messages.get(sender, "general.no-permission"));
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
                    sender.sendMessage(messages.get(sender, "reset-vault.backup.status-header"));
                    sender.sendMessage(messages.get(sender, "reset-vault.backup.status-details",
                            "phase", manager.phase().name(),
                            "status", manager.backupProgressStatus(),
                            "sessions", String.valueOf(manager.activeSessionCount()),
                            "vaults", String.valueOf(manager.backupProgressVaultCount()),
                            "size", String.valueOf(manager.backupProgressCompressedSize()),
                            "checksum", manager.backupProgressChecksum()));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    manager.startBackupProcess(sender);
                    return true;
                }
                BackupConfirmMenu.open(player, manager, messages);
                return true;
            }

            case "restore" -> {
                if (!sender.hasPermission("vertex.reset.recover")) {
                    sender.sendMessage(messages.get(sender, "general.no-permission"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(messages.get(sender, "general.players-only"));
                    return true;
                }
                RestoreBrowserMenu.open(player, manager, messages, 0);
                return true;
            }

            case "undo" -> {
                if (!sender.hasPermission("vertex.reset.recover")) {
                    sender.sendMessage(messages.get(sender, "general.no-permission"));
                    return true;
                }
                sender.sendMessage(messages.get(sender, "reset-vault.undo.starting"));
                manager.executeUndo(sender).thenAccept(success -> {
                    if (success) {
                        sender.sendMessage(messages.get(sender, "reset-vault.undo.success"));
                    } else {
                        sender.sendMessage(messages.get(sender, "reset-vault.undo.failed"));
                    }
                });
                return true;
            }

            case "blacklist" -> {
                if (!sender.hasPermission("vertex.reset.admin")) {
                    sender.sendMessage(messages.get(sender, "general.no-permission"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(messages.get(sender, "general.players-only"));
                    return true;
                }
                BlacklistEditorMenu.open(player, manager, messages, 0);
                return true;
            }

            default -> {
                sender.sendMessage(messages.get(sender, "reset-vault.unknown-subcommand"));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("status");

            if (sender.hasPermission("vertex.developer")) {
                completions.add("give");
            }
            if (sender.hasPermission("vertex.reset.admin")) {
                completions.add("backup");
                completions.add("blacklist");
                completions.add("setphase");
                completions.add("token");
            }
            if (sender.hasPermission("vertex.reset.admin.edit")) {
                completions.add("open");
            }
            // Dedicated recovery permission: strictly hidden from unauthorized users
            if (sender.hasPermission("vertex.reset.recover")) {
                completions.add("restore");
                completions.add("undo");
            }

            return filterPrefix(completions, args[0]);
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("setphase") && sender.hasPermission("vertex.reset.admin")) {
                List<String> phases = new ArrayList<>();
                for (ResetVaultPhase p : ResetVaultPhase.values()) {
                    phases.add(p.name());
                }
                return filterPrefix(phases, args[1]);
            }
            if (args[0].equalsIgnoreCase("backup") && sender.hasPermission("vertex.reset.admin")) {
                return filterPrefix(List.of("status"), args[1]);
            }
            if (args[0].equalsIgnoreCase("token") && sender.hasPermission("vertex.reset.admin")) {
                return filterPrefix(List.of("give"), args[1]);
            }
            if ((args[0].equalsIgnoreCase("give") && sender.hasPermission("vertex.developer"))
                    || (args[0].equalsIgnoreCase("open") && sender.hasPermission("vertex.reset.admin.edit"))) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return filterPrefix(names, args[1]);
            }
        }

        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("token") && args[1].equalsIgnoreCase("give") && sender.hasPermission("vertex.reset.admin")) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return filterPrefix(names, args[2]);
            }
        }

        return Collections.emptyList();
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        return me.vertex.core.util.CommandUtil.filterPrefix(options, prefix);
    }
}
