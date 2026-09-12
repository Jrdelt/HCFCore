package me.vertex.core.factions;

import me.vertex.core.claims.BaseClaimManager;
import me.vertex.core.claims.ChunkKey;
import me.vertex.core.faction.FactionBankManager;
import me.vertex.core.faction.FactionVaultManager;
import me.vertex.core.faction.FactionUpgrade;
import me.vertex.core.faction.FactionUpgradeManager;
import me.vertex.core.grace.DurationParser;
import me.vertex.core.grace.GraceManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.network.NetworkManager;
import me.vertex.core.network.ShardState;
import me.vertex.core.shield.ShieldManager;
import me.vertex.core.season.SeasonResetManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Granular, command-only /fa administration with durable auditing. */
public final class FactionAdminCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOTS = List.of("help", "info", "grace", "power", "claim",
            "unclaim", "disband", "relation", "shield", "bank", "vault", "upgrades", "members", "combat", "logs", "network", "season");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final FactionService factions;
    private final GraceManager grace;
    private final ShieldManager shield;
    private final BaseClaimManager bases;
    private final FactionBankManager bank;
    private final FactionVaultManager vault;
    private final FactionSocialManager social;
    private final CombatManager combat;
    private final AdminAuditStorage audit;
    private final Messages messages;
    private final NetworkManager network;
    private final SeasonResetManager seasonReset;
    private FactionUpgradeManager upgrades;

    public FactionAdminCommand(Plugin plugin, FactionService factions, GraceManager grace,
            ShieldManager shield, BaseClaimManager bases, FactionBankManager bank,
            FactionSocialManager social, CombatManager combat, AdminAuditStorage audit, Messages messages) {
        this(plugin, factions, grace, shield, bases, bank, social, combat, audit, messages, null, null, null);
    }

    public FactionAdminCommand(Plugin plugin, FactionService factions, GraceManager grace,
            ShieldManager shield, BaseClaimManager bases, FactionBankManager bank,
            FactionSocialManager social, CombatManager combat, AdminAuditStorage audit, Messages messages,
            NetworkManager network) {
        this(plugin, factions, grace, shield, bases, bank, social, combat, audit, messages, network, null, null);
    }

    public FactionAdminCommand(Plugin plugin, FactionService factions, GraceManager grace,
            ShieldManager shield, BaseClaimManager bases, FactionBankManager bank,
            FactionSocialManager social, CombatManager combat, AdminAuditStorage audit, Messages messages,
            NetworkManager network, SeasonResetManager seasonReset) {
        this(plugin, factions, grace, shield, bases, bank, social, combat, audit, messages,
                network, seasonReset, null);
    }

    public FactionAdminCommand(Plugin plugin, FactionService factions, GraceManager grace,
            ShieldManager shield, BaseClaimManager bases, FactionBankManager bank,
            FactionSocialManager social, CombatManager combat, AdminAuditStorage audit, Messages messages,
            NetworkManager network, SeasonResetManager seasonReset, FactionVaultManager vault) {
        this.plugin = plugin; this.factions = factions; this.grace = grace; this.shield = shield;
        this.bases = bases; this.bank = bank; this.social = social; this.combat = combat;
        this.audit = audit; this.messages = messages; this.network = network;
        this.seasonReset = seasonReset; this.vault = vault;
    }

    public void setUpgradeManager(FactionUpgradeManager upgrades) { this.upgrades = upgrades; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String root = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed(sender, root)) {
            sender.sendMessage(messages.get(sender, "general.no-permission"));
            return true;
        }
        switch (root) {
            case "help" -> help(sender, integer(arg(args, 1), 1));
            case "info" -> info(sender, args);
            case "grace" -> grace(sender, args);
            case "power" -> power(sender, args);
            case "claim" -> claim(sender, args);
            case "unclaim" -> unclaim(sender, args);
            case "disband" -> disband(sender, args);
            case "relation" -> relation(sender, args);
            case "shield" -> shield(sender, args);
            case "bank" -> bank(sender, args);
            case "vault" -> vault(sender, args);
            case "upgrades" -> upgrades(sender, args);
            case "members" -> members(sender, args);
            case "combat" -> combat(sender, args);
            case "logs" -> logs(sender, args);
            case "network" -> network(sender, args);
            case "season" -> season(sender, args);
            default -> help(sender, 1);
        }
        return true;
    }

    private void help(CommandSender sender, int page) {
        List<String> visible = ROOTS.stream().filter(root -> allowed(sender, root)).toList();
        int pages = Math.max(1, (visible.size() + 5) / 6);
        int selected = Math.max(1, Math.min(page, pages));
        sender.sendMessage(messages.get(sender, "fa.help-header", "page", String.valueOf(selected),
                "pages", String.valueOf(pages)));
        visible.stream().skip((long) (selected - 1) * 6L).limit(6).forEach(root ->
                sender.sendMessage(messages.get(sender, "fa.help-line", "command", usage(sender, root))));
    }

    private void info(CommandSender sender, String[] args) {
        FactionData faction = resolveFaction(arg(args, 1));
        if (faction == null) { send(sender, "faction-not-found"); return; }
        sender.sendMessage(messages.get(sender, "fa.info", "faction", faction.tag(),
                "id", String.valueOf(faction.id()), "members", String.valueOf(factions.members(faction.id()).size()),
                "claims", String.valueOf(factions.claimCount(faction.id())),
                "power", number(faction.power()), "maximum", number(faction.powerMax()),
                "bases", String.valueOf(bases.anchoredCount(faction.id())),
                "shield", messages.getRaw(sender, shield.isShieldActive(faction.id())
                        ? "fa.state-active" : "fa.state-inactive")));
    }

    private void grace(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(messages.get(sender, grace.isActive() ? "grace.admin-status-active"
                    : "grace.admin-status-inactive", "remaining", DurationParser.format(grace.secondsRemaining())));
            return;
        }
        GraceManager.Result result;
        String details;
        if (args[1].equalsIgnoreCase("off")) {
            result = grace.disable(uuid(sender)); details = "off";
        } else if (args[1].equalsIgnoreCase("on") && args.length >= 3) {
            try { long seconds = DurationParser.parseSeconds(args[2]); result = grace.enable(seconds, uuid(sender)); details = "on " + seconds; }
            catch (RuntimeException error) { send(sender, "invalid-duration"); return; }
        } else { send(sender, "usage-grace"); return; }
        boolean success = result == GraceManager.Result.OK;
        audited(sender, "GRACE", "global", details, success);
        send(sender, success ? "updated" : "failed");
    }

    private void power(CommandSender sender, String[] args) {
        if (args.length < 5 || !args[1].equalsIgnoreCase("set")) { send(sender, "usage-power"); return; }
        OfflinePlayer target = resolvePlayer(args[2]);
        if (target == null) { send(sender, "player-not-found"); return; }
        double amount;
        try { amount = Double.parseDouble(args[4]); } catch (NumberFormatException error) { send(sender, "invalid-number"); return; }
        if (!Double.isFinite(amount) || amount < 0D) { send(sender, "invalid-number"); return; }
        String type = args[3].toLowerCase(Locale.ROOT);
        if (!List.of("current", "max").contains(type)) { send(sender, "usage-power"); return; }
        submit(sender, "POWER_SET", target.getName(), type + "=" + amount,
                () -> type.equals("current")
                        ? factions.setPersonalPowerCurrent(target.getUniqueId(), amount)
                        : factions.setPersonalPowerMaximum(target.getUniqueId(), amount));
    }

    private void claim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { send(sender, "players-only"); return; }
        FactionData faction = resolveFaction(arg(args, 1));
        if (faction == null) { send(sender, "faction-not-found"); return; }
        ChunkKey chunk = ChunkKey.of(player.getLocation());
        submitResult(sender, "FORCE_CLAIM", faction.tag(), chunk.toString(),
                () -> factions.forceClaim(faction.id(), player, chunk));
    }

    private void unclaim(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) || !hasForce(args)) { send(sender, "usage-unclaim"); return; }
        ChunkKey chunk = ChunkKey.of(((Player) sender).getLocation());
        submit(sender, "FORCE_UNCLAIM", chunk.toString(), "--force",
                () -> factions.forceUnclaim(chunk));
    }

    private void disband(CommandSender sender, String[] args) {
        FactionData faction = resolveFaction(arg(args, 1));
        if (faction == null) { send(sender, "faction-not-found"); return; }
        if (!hasForce(args)) { send(sender, "usage-disband"); return; }
        submitResult(sender, "FORCE_DISBAND", faction.tag(), "--force",
                () -> factions.forceDisband(faction.id(), sender instanceof Player player ? player : null));
    }

    private void relation(CommandSender sender, String[] args) {
        if (args.length < 5 || !hasForce(args)) { send(sender, "usage-relation"); return; }
        FactionData left = resolveFaction(args[1]), right = resolveFaction(args[2]);
        FactionRelation value = FactionRelation.parse(args[3], null);
        if (left == null || right == null || value == null || left.id() == right.id()) {
            send(sender, "usage-relation"); return;
        }
        submit(sender, "FORCE_RELATION", left.tag() + "/" + right.tag(), value.name(),
                () -> social.forceRelation(left.id(), right.id(), value,
                        sender instanceof Player player ? player : null));
    }

    private void shield(CommandSender sender, String[] args) {
        FactionData faction = resolveFaction(arg(args, 1));
        if (faction == null || args.length < 3) { send(sender, "usage-shield"); return; }
        String action = args[2].toLowerCase(Locale.ROOT);
        if (!hasForce(args)) {
            send(sender, "force-required"); return;
        }
        boolean success = switch (action) {
            case "active" -> shield.applyOverride(faction.id(), true, uuid(sender));
            case "inactive" -> shield.applyOverride(faction.id(), false, uuid(sender));
            case "clear" -> shield.removeOverride(faction.id(), uuid(sender));
            default -> false;
        };
        audited(sender, "SHIELD_OVERRIDE", faction.tag(), action, success);
        send(sender, success ? "updated" : "failed");
    }

    private void bank(CommandSender sender, String[] args) {
        if (args.length < 5) { send(sender, "usage-bank"); return; }
        FactionData faction = resolveFaction(args[1]);
        if (faction == null) { send(sender, "faction-not-found"); return; }
        String type = args[2].toLowerCase(Locale.ROOT), operation = args[3].toLowerCase(Locale.ROOT);
        double raw;
        try { raw = Double.parseDouble(args[4]); } catch (NumberFormatException error) { send(sender, "invalid-number"); return; }
        if (!Double.isFinite(raw) || raw < 0D || !List.of("money", "xp", "tnt").contains(type)
                || !List.of("set", "add", "take").contains(operation)) { send(sender, "usage-bank"); return; }
        double money = bank.money(faction.id()); long xp = bank.experience(faction.id()), tnt = bank.tnt(faction.id());
        if (type.equals("money")) money = calculate(money, raw, operation);
        else if (type.equals("xp")) xp = (long) calculate(xp, raw, operation);
        else tnt = (long) calculate(tnt, raw, operation);
        double nextMoney = Math.max(0D, money); long nextXp = Math.max(0L, xp), nextTnt = Math.max(0L, tnt);
        CompletableFuture<Boolean> future = bank.set(faction.id(), nextMoney, nextXp, nextTnt);
        future.whenComplete((success, error) -> onMain(() -> {
            boolean ok = error == null && Boolean.TRUE.equals(success);
            audited(sender, "BANK_OVERRIDE", faction.tag(), type + " " + operation + " " + raw, ok);
            send(sender, ok ? "updated" : "failed");
        }));
    }

    private void vault(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { send(sender, "players-only"); return; }
        FactionData faction = resolveFaction(arg(args, 1));
        if (vault == null || faction == null) {
            send(sender, faction == null ? "faction-not-found" : "failed");
            return;
        }
        if (hasForce(args)) {
            vault.forceUnlock(faction.id()).whenComplete((success, error) -> onMain(() -> {
                boolean ok = error == null && Boolean.TRUE.equals(success);
                audited(sender, "VAULT_FORCE_UNLOCK", faction.tag(), "--force", ok);
                if (!ok) { send(sender, "failed"); return; }
                sender.sendMessage(messages.get(sender, "fa.vault-force-wait"));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) vault.openAdmin(player, faction.id());
                }, vault.forceSafetyDelayTicks());
            }));
            return;
        }
        audited(sender, "VAULT_INSPECT", faction.tag(), "open", true);
        vault.openAdmin(player, faction.id());
    }

    private void upgrades(CommandSender sender, String[] args) {
        if (upgrades == null || args.length < 4) { send(sender, "usage-upgrades"); return; }
        FactionData faction = resolveFaction(args[1]);
        FactionUpgrade upgrade = FactionUpgrade.fromConfigKey(args[2]);
        int level = integer(args[3], -1);
        if (faction == null) { send(sender, "faction-not-found"); return; }
        if (upgrade == null || level < 0 || level > upgrades.definition(upgrade).maxLevel()) {
            send(sender, "usage-upgrades"); return;
        }
        upgrades.setLevel(faction.id(), upgrade, level).whenComplete((success, error) -> onMain(() -> {
            boolean ok = error == null && Boolean.TRUE.equals(success);
            audited(sender, "UPGRADE_OVERRIDE", faction.tag(), upgrade.configKey() + "=" + level, ok);
            send(sender, ok ? "updated" : "failed");
        }));
    }

    private void members(CommandSender sender, String[] args) {
        if (args.length < 3) { send(sender, "usage-members"); return; }
        OfflinePlayer target = resolvePlayer(args[2]);
        if (target == null) { send(sender, "player-not-found"); return; }
        if (args[1].equalsIgnoreCase("remove") && hasForce(args)) {
            submitResult(sender, "MEMBER_REMOVE", target.getName(), "--force",
                    () -> factions.forceRemoveMember(target.getUniqueId()));
        } else if (args[1].equalsIgnoreCase("setrole") && args.length >= 4) {
            FactionRole role = FactionRole.parse(args[3], null);
            if (role == null || role == FactionRole.LEADER) { send(sender, "usage-members"); return; }
            submitResult(sender, "MEMBER_ROLE", target.getName(), role.name(),
                    () -> factions.forceSetRole(target.getUniqueId(), role));
        } else send(sender, "usage-members");
    }

    private void combat(CommandSender sender, String[] args) {
        if (args.length < 3 || !args[1].equalsIgnoreCase("clear")) { send(sender, "usage-combat"); return; }
        OfflinePlayer target = resolvePlayer(args[2]);
        if (target == null) { send(sender, "player-not-found"); return; }
        combat.clearOwnTag(target.getUniqueId());
        audited(sender, "COMBAT_CLEAR", target.getName(), null, true);
        send(sender, "updated");
    }

    private void logs(CommandSender sender, String[] args) {
        FactionData faction = resolveFaction(arg(args, 1));
        FactionSocialStorage.Archive archive = faction == null ? social.archive(arg(args, 1)) : null;
        if (faction == null && archive == null) { send(sender, "faction-not-found"); return; }
        int factionId = faction == null ? archive.factionId() : faction.id();
        String factionTag = faction == null ? archive.tag() : faction.tag();
        int page = Math.max(0, integer(arg(args, 2), 1) - 1);
        social.logs(factionId, page).whenComplete((entries, error) -> onMain(() -> {
            if (error != null) { send(sender, "failed"); return; }
            sender.sendMessage(messages.get(sender, "fa.logs-header", "faction", factionTag,
                    "page", String.valueOf(page + 1)));
            if (entries.isEmpty()) send(sender, "logs-empty");
            for (FactionSocialStorage.LogEntry entry : entries) {
                Component line = messages.get(sender, "fa.logs-line", "action", entry.action(),
                        "actor", entry.actorName() == null ? "Server" : entry.actorName(),
                        "time", LOG_TIME.format(Instant.ofEpochMilli(entry.createdAt())));
                if (entry.details() != null) line = line.hoverEvent(HoverEvent.showText(
                        Component.text(entry.details(), NamedTextColor.GRAY)));
                sender.sendMessage(line);
            }
        }));
    }

    private void network(CommandSender sender, String[] args) {
        if (network == null || !network.enabled()) { send(sender, "network-disabled"); return; }
        String action = arg(args, 1).toLowerCase(Locale.ROOT);
        if (action.equals("shards")) {
            sender.sendMessage(messages.get(sender, "fa.network-shards-header"));
            boolean mayInspectRecovery = sender.hasPermission("vertex.network.recovery");
            for (var shard : network.shards()) {
                if (shard.state() == ShardState.CRASH_RECOVERY && !mayInspectRecovery) continue;
                sender.sendMessage(messages.get(sender, "fa.network-shard-line",
                        "shard", shard.shardId(), "role", shard.role(), "state", shard.state().name(),
                        "players", String.valueOf(shard.currentPlayers()), "maximum", String.valueOf(shard.maxPlayers())));
            }
            return;
        }
        if (action.equals("transfers")) {
            sender.sendMessage(messages.get(sender, "fa.network-transfers-header"));
            List<me.vertex.core.network.NetworkStorage.Handoff> rows = network.unresolvedTransfers();
            if (rows.isEmpty()) send(sender, "network-transfers-empty");
            for (var row : rows) sender.sendMessage(messages.get(sender, "fa.network-transfer-line",
                    "id", row.id(), "player", row.playerUuid().toString(), "source", row.sourceShard(),
                    "destination", row.destination().shardId(), "state", row.state()));
            return;
        }
        if (action.equals("state") && args.length >= 3) {
            ShardState next = ShardState.parse(args[2], null);
            boolean touchesRecovery = next == ShardState.CRASH_RECOVERY || network.state() == ShardState.CRASH_RECOVERY;
            if (next == null) {
                send(sender, "usage-network");
                return;
            }
            if (touchesRecovery && !sender.hasPermission("vertex.network.recovery")) {
                send(sender, "network-recovery-denied");
                return;
            }
            // Recovery may only be cleared through the explicit, forced
            // recovery-ready path so an ordinary state change cannot
            // accidentally return an uninspected shard to service.
            if (network.state() == ShardState.CRASH_RECOVERY && next != ShardState.CRASH_RECOVERY) {
                send(sender, "usage-network"); return;
            }
            long eta = 0L;
            if (args.length >= 4) {
                try { eta = System.currentTimeMillis() + DurationParser.parseSeconds(args[3]) * 1_000L; }
                catch (RuntimeException error) { send(sender, "invalid-duration"); return; }
            }
            long finalEta = eta;
            network.setState(next, finalEta, sender.getName()).whenComplete((success, error) -> onMain(() -> {
                boolean ok = error == null && Boolean.TRUE.equals(success);
                audited(sender, "NETWORK_STATE", network.shardId(), next.name(), ok);
                send(sender, ok ? "updated" : "failed");
            }));
            return;
        }
        if (action.equals("recovery") && args.length >= 3 && args[2].equalsIgnoreCase("ready")) {
            if (!sender.hasPermission("vertex.network.recovery") || !hasForce(args)) {
                send(sender, "network-recovery-denied"); return;
            }
            network.setState(ShardState.ONLINE, 0L, sender.getName()).whenComplete((success, error) -> onMain(() -> {
                boolean ok = error == null && Boolean.TRUE.equals(success);
                audited(sender, "NETWORK_RECOVERY_READY", network.shardId(), "--force", ok);
                send(sender, ok ? "updated" : "failed");
            }));
            return;
        }
        if (action.equals("resolve") && args.length >= 4 && hasForce(args)) {
            boolean acknowledge = args[3].equalsIgnoreCase("ack");
            if (!acknowledge && !args[3].equalsIgnoreCase("abort")) { send(sender, "usage-network"); return; }
            network.resolveTransfer(args[2], acknowledge, sender.getName()).whenComplete((success, error) -> onMain(() -> {
                boolean ok = error == null && Boolean.TRUE.equals(success);
                audited(sender, "NETWORK_TRANSFER_RESOLVE", args[2], args[3], ok);
                send(sender, ok ? "updated" : "failed");
            }));
            return;
        }
        send(sender, "usage-network");
    }

    private void season(CommandSender sender, String[] args) {
        if (seasonReset == null || args.length < 2 || !args[1].equalsIgnoreCase("reset") || !hasForce(args)) {
            send(sender, "usage-season");
            return;
        }
        if (!seasonReset.networkIsEmpty()) {
            send(sender, "season-players-online");
            return;
        }
        sender.sendMessage(messages.get(sender, "fa.season-starting"));
        seasonReset.reset().whenComplete((result, error) -> onMain(() -> {
            boolean success = error == null && result != null && result.success();
            audited(sender, "SEASON_RESET", "network", "--force", success);
            if (!success) {
                sender.sendMessage(messages.get(sender, "fa.season-failed", "reason",
                        result == null || result.error() == null ? messages.getRaw(sender,"general.unknown") : result.error()));
                return;
            }
            sender.sendMessage(messages.get(sender, "fa.season-complete", "rows",
                    String.valueOf(result.totalAffectedRows())));
            Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 40L);
        }));
    }

    private void submitResult(CommandSender sender, String action, String target, String details,
            Supplier<FactionService.Result> operation) {
        factions.submitMutation(operation).whenComplete((result, error) -> onMain(() -> {
            boolean success = error == null && result == FactionService.Result.OK;
            audited(sender, action, target, details, success);
            send(sender, success ? "updated" : "failed");
        }));
    }

    private void submit(CommandSender sender, String action, String target, String details,
            Supplier<Boolean> operation) {
        factions.submitMutation(operation).whenComplete((result, error) -> onMain(() -> {
            boolean success = error == null && Boolean.TRUE.equals(result);
            audited(sender, action, target, details, success);
            send(sender, success ? "updated" : "failed");
        }));
    }

    private void audited(CommandSender sender, String action, String target, String details, boolean success) {
        plugin.getLogger().warning("/fa " + action + " by " + sender.getName() + " target=" + target
                + " success=" + success + (details == null ? "" : " " + details));
        CompletableFuture.runAsync(() -> {
            try { audit.insert(uuid(sender) == null ? null : uuid(sender).toString(), sender.getName(),
                    action, target, details, success); }
            catch (Exception error) { plugin.getLogger().severe("Could not persist /fa audit: " + error.getMessage()); }
        });
    }

    private boolean allowed(CommandSender sender, String root) {
        return sender.hasPermission("vertex.fa.*") || sender.hasPermission("vertex.fa." + root);
    }

    private FactionData resolveFaction(String raw) {
        if (raw == null || raw.isBlank()) return null;
        FactionData byTag = factions.faction(raw).orElse(null);
        if (byTag != null) return byTag;
        try { return factions.faction(Integer.parseInt(raw)).orElse(null); }
        catch (NumberFormatException ignored) { return null; }
    }

    private OfflinePlayer resolvePlayer(String raw) {
        Player online = Bukkit.getPlayerExact(raw);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(raw);
        if (cached != null) return cached;
        try { return Bukkit.getOfflinePlayer(UUID.fromString(raw)); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private String usage(CommandSender sender, String root) {
        return messages.getRaw(sender, "fa.help-usage." + root);
    }

    private static boolean hasForce(String[] args) {
        return Stream.of(args).anyMatch("--force"::equalsIgnoreCase);
    }
    private static double calculate(double old, double amount, String operation) {
        return operation.equals("set") ? amount : operation.equals("add") ? old + amount : old - amount;
    }
    private static int integer(String raw, int fallback) {
        try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static UUID uuid(CommandSender sender) { return sender instanceof Player player ? player.getUniqueId() : null; }
    private static String arg(String[] args, int index) { return index < args.length ? args[index] : ""; }
    private static String number(double value) { return String.format(Locale.ROOT, "%.0f", value); }
    private void send(CommandSender sender, String key) { sender.sendMessage(messages.get(sender, "fa." + key)); }
    private void onMain(Runnable task) { Bukkit.getScheduler().runTask(plugin, task); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return complete(args[0], ROOTS.stream().filter(root -> allowed(sender, root)));
        String root = args[0].toLowerCase(Locale.ROOT);
        Stream<String> values = Stream.empty();
        if (args.length == 2 && List.of("info", "claim", "disband", "shield", "bank", "vault", "upgrades", "logs").contains(root)) values = factionTags();
        else if (args.length == 2 && root.equals("grace")) values = Stream.of("on", "off");
        else if (args.length == 2 && root.equals("power")) values = Stream.of("set");
        else if (args.length == 3 && root.equals("power")) values = playerNames();
        else if (args.length == 4 && root.equals("power")) values = Stream.of("current", "max");
        else if (args.length == 3 && root.equals("shield")) values = Stream.of("active", "inactive", "clear");
        else if (args.length == 4 && root.equals("shield")) values = Stream.of("--force");
        else if (args.length == 3 && root.equals("bank")) values = Stream.of("money", "xp", "tnt");
        else if (args.length == 4 && root.equals("bank")) values = Stream.of("set", "add", "take");
        else if (args.length == 3 && root.equals("vault")) values = Stream.of("--force");
        else if (args.length == 3 && root.equals("upgrades")) values = Stream.of(FactionUpgrade.values()).map(FactionUpgrade::configKey);
        else if (args.length == 4 && root.equals("upgrades") && upgrades != null) {
            FactionUpgrade upgrade = FactionUpgrade.fromConfigKey(args[2]);
            if (upgrade != null) values = java.util.stream.IntStream.rangeClosed(0,
                    upgrades.definition(upgrade).maxLevel()).mapToObj(String::valueOf);
        }
        else if (args.length == 2 && root.equals("relation")) values = factionTags();
        else if (args.length == 3 && root.equals("relation")) values = factionTags();
        else if (args.length == 4 && root.equals("relation")) values = Stream.of("ally", "neutral", "enemy");
        else if (args.length == 2 && root.equals("members")) values = Stream.of("remove", "setrole");
        else if (args.length == 3 && root.equals("members")) values = playerNames();
        else if (args.length == 4 && root.equals("members") && args[1].equalsIgnoreCase("setrole")) values = Stream.of("coleader", "admin", "mod", "member", "recruit");
        else if (args.length == 2 && root.equals("combat")) values = Stream.of("clear");
        else if (args.length == 3 && root.equals("combat")) values = playerNames();
        else if (args.length == 2 && root.equals("network")) values = Stream.of("shards", "transfers", "state", "recovery", "resolve");
        else if (args.length == 2 && root.equals("season")) values = Stream.of("reset");
        else if (args.length == 3 && root.equals("season") && args[1].equalsIgnoreCase("reset")) values = Stream.of("--force");
        else if (args.length == 3 && root.equals("network") && args[1].equalsIgnoreCase("state")) values = Stream.of("online", "draining", "restarting", "offline", "crash_recovery");
        else if (args.length == 3 && root.equals("network") && args[1].equalsIgnoreCase("recovery")) values = Stream.of("ready");
        else if (args.length == 4 && root.equals("network") && args[1].equalsIgnoreCase("recovery")) values = Stream.of("--force");
        else if (args.length == 4 && root.equals("network") && args[1].equalsIgnoreCase("resolve")) values = Stream.of("ack", "abort");
        else if (args.length == 5 && root.equals("network") && args[1].equalsIgnoreCase("resolve")) values = Stream.of("--force");
        else if ((root.equals("unclaim") && args.length == 2)
                || (root.equals("disband") && args.length == 3)
                || (root.equals("relation") && args.length == 5)) values = Stream.of("--force");
        return complete(args[args.length - 1], values);
    }

    private Stream<String> factionTags() { return factions.factions().stream().map(FactionData::tag); }
    private static Stream<String> playerNames() { return Bukkit.getOnlinePlayers().stream().map(Player::getName); }
    private static List<String> complete(String partial, Stream<String> values) {
        String prefix = partial.toLowerCase(Locale.ROOT);
        return values.filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
