package me.vertex.core.factions;

import me.vertex.core.claims.ChunkKey;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.faction.FactionBankManager;
import me.vertex.core.faction.FactionUpgradeManager;
import me.vertex.core.lang.Messages;
import me.vertex.core.network.NetworkLocation;
import me.vertex.core.network.NetworkManager;
import me.vertex.core.teleport.TeleportManager;
import me.vertex.core.util.Numbers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Native /f command tree. Vertex extension listeners may still claim their own subcommands. */
public final class FactionCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT = List.of("help", "create", "disband", "rename", "invite", "join", "leave", "kick", "promote", "demote", "leader", "who", "list", "power", "claim", "unclaim", "unclaimall", "autoclaim", "map", "chat", "home", "sethome", "warp", "setwarp", "delwarp", "ally", "neutral", "enemy", "open", "close", "description", "money", "tnt", "perms", "permissions", "bank", "vault", "logs", "focus", "ban", "unban", "bans", "upgrades", "rally", "shield", "grace", "top", "safezone", "warzone", "admin");
    private static final List<String> HELP_KEYS = List.of("help-command-1", "help-command-2", "help-command-3", "help-command-4", "help-command-5", "help-command-6", "help-command-7", "help-command-8", "help-command-9", "help-command-10", "help-command-11", "help-command-12", "help-command-13", "help-command-14", "help-command-15", "help-command-16", "help-command-17", "help-command-18", "help-command-19", "help-command-20", "help-command-21", "help-command-22", "help-command-23", "help-command-24");
    private final Plugin plugin;
    private final FactionService factions;
    private final Supplier<FactionBankManager> factionBank;
    private final Messages messages;
    private volatile Supplier<FactionUpgradeManager> factionUpgrades = () -> null;
    private volatile TeleportManager teleports;
    private volatile NetworkManager network;

    public FactionCommand(Plugin plugin, FactionService factions, Supplier<FactionBankManager> factionBank,
            Messages messages) {
        this.plugin = plugin;
        this.factions = factions;
        this.factionBank = factionBank;
        this.messages = messages;
    }

    public void setTeleportManager(TeleportManager teleports, NetworkManager network) {
        this.teleports = teleports; this.network = network;
    }

    public void setUpgradeManager(Supplier<FactionUpgradeManager> factionUpgrades) {
        this.factionUpgrades = factionUpgrades == null ? () -> null : factionUpgrades;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.get(sender, "general.players-only")); return true; }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) { help(player, args.length > 1 ? args[1] : "1"); return true; }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> mutate(player, () -> factions.create(player, arg(args, 1)), "created");
            // FactionDisbandMenu owns the mandatory two-confirmation flow.
            // Never expose a direct fallback that could bypass it.
            case "disband" -> send(player, "feature-unavailable");
            case "rename" -> mutate(player, () -> factions.rename(player, arg(args, 1)), "renamed");
            case "invite" -> invite(player, args);
            case "join" -> factions.faction(arg(args, 1)).ifPresentOrElse(target -> mutate(player, () -> factions.join(player, target), "joined", "faction", target.tag()), () -> send(player, "faction-not-found"));
            case "leave" -> mutate(player, () -> factions.leave(player), "left");
            case "kick" -> changeMember(player, args, "kick");
            case "promote" -> changeMember(player, args, "promote");
            case "demote" -> changeMember(player, args, "demote");
            case "leader" -> leader(player, args);
            case "who", "show" -> who(player, args);
            case "list" -> list(player);
            case "power" -> power(player);
            case "claim" -> claim(player, args);
            case "unclaim" -> { ChunkKey key=ChunkKey.of(player.getLocation());mutate(player, () -> factions.unclaim(player,key), "chunk-unclaimed"); }
            case "unclaimall" -> mutate(player, () -> factions.unclaimAll(player), "all-unclaimed");
            case "autoclaim" -> factions.submitMutation(() -> factions.toggleAutoclaim(player.getUniqueId()))
                    .whenComplete((enabled,error)->onMain(()->{
                        if(error!=null){result(player,FactionService.Result.DATABASE_ERROR,"autoclaim-enabled");return;}
                        send(player,Boolean.TRUE.equals(enabled)?"autoclaim-enabled":"autoclaim-disabled");
                    }));
            case "map" -> map(player, args);
            case "chat", "c" -> chat(player, args);
            case "home" -> teleportHome(player);
            case "sethome" -> { Location location=player.getLocation().clone();mutate(player, () -> factions.setHome(player,location), "home-set"); }
            case "warp" -> warp(player, args);
            case "setwarp" -> { Location location=player.getLocation().clone();mutate(player, () -> factions.setWarp(player,arg(args,1),location), "warp-set"); }
            case "delwarp", "deletewarp" -> mutate(player, () -> factions.deleteWarp(player,arg(args,1)), "warp-deleted");
            // FactionSocialCommand owns the request/acceptance protocol. If
            // that router did not intercept the command, fail closed instead
            // of falling back to the retired direct relation write.
            case "ally", "neutral", "enemy" -> send(player, "feature-unavailable");
            case "open" -> mutate(player, () -> factions.setOpen(player, true), "opened");
            case "close" -> mutate(player, () -> factions.setOpen(player, false), "closed");
            case "description", "desc" -> mutate(player, () -> factions.setDescription(player, join(args, 1)), "description-updated");
            case "money" -> money(player, args);
            case "tnt" -> tnt(player);
            case "safezone" -> systemClaim(player, "safezone", args);
            case "warzone" -> systemClaim(player, "warzone", args);
            case "admin" -> admin(player, args);
            // These commands are handled by their established Vertex GUI listeners
            // before Bukkit dispatches /f. Keep a useful fallback if one is disabled.
            case "bank" -> bank(player, args);
            case "perms", "permissions", "vault", "logs", "focus", "ban", "unban", "bans", "upgrades", "rally", "shield", "grace", "top" -> send(player, "feature-unavailable");
            default -> help(player, "1");
        }
        return true;
    }

    private void invite(Player player, String[] args) {
        Player target = Bukkit.getPlayerExact(arg(args, 1));
        if (target == null) { send(player, "invite-online-required"); return; }
        factions.submitMutation(() -> factions.invite(player, target.getUniqueId())).whenComplete((outcome,error)->
                onMain(()->{
                    FactionService.Result safe=error==null?outcome:FactionService.Result.DATABASE_ERROR;
                    result(player,safe,"invited","player",target.getName());
                    if(safe==FactionService.Result.OK&&target.isOnline())target.sendMessage(messages.get(target,
                            "native-factions.invite-received","faction",FactionsHook.getFactionTag(player)));
                }));
    }

    private void changeMember(Player player, String[] args, String action) {
        Player target = Bukkit.getPlayerExact(arg(args, 1));
        if (target == null) { send(player, "player-online-required"); return; }
        FactionMember member = factions.member(target.getUniqueId());
        if (member == null) { send(player, "player-not-member"); return; }
        FactionRole desired=action.equals("promote")?next(member.role()):previous(member.role());
        mutate(player,()->action.equals("kick")?factions.kick(player,target.getUniqueId())
                        :factions.setRole(player,target.getUniqueId(),desired),
                action.equals("kick")?"kicked":"rank-updated","player",target.getName());
    }

    private void leader(Player player, String[] args) {
        Player target = Bukkit.getPlayerExact(arg(args, 1));
        if (target == null) { send(player, "player-online-required"); return; }
        mutate(player,()->factions.transferLeadership(player,target.getUniqueId()),"leadership-transferred");
    }

    private void who(Player player, String[] args) {
        FactionData faction = args.length < 2 ? factions.faction(player).orElse(null)
                : factions.faction(arg(args, 1)).orElseGet(() -> factions.member(arg(args, 1))
                        .flatMap(member -> factions.faction(member.factionId())).orElse(null));
        if (faction == null) { send(player, "faction-not-found"); return; }
        List<FactionMember> members = factions.members(faction.id());
        List<FactionMember> online = members.stream().filter(FactionCommand::isOnline).toList();
        List<FactionMember> offline = members.stream().filter(member -> !isOnline(member)).toList();
        FactionMember leader = members.stream().filter(member -> member.role() == FactionRole.LEADER).findFirst().orElse(null);

        send(player, "who-header", "faction", faction.tag());
        if (!faction.description().isBlank()) send(player, "who-description", "description", faction.description());
        send(player, faction.open() ? "who-status-open" : "who-status-closed",
                "online", String.valueOf(online.size()), "members", String.valueOf(members.size()));
        if (leader == null) send(player, "who-leader-missing");
        else send(player, "who-leader", "leader", leader.lastName());
        send(player, "who-stats", "power", String.format(Locale.ROOT, "%.0f", faction.power()), "maximum", String.format(Locale.ROOT, "%.0f", faction.powerMax()), "land", String.valueOf(factions.claimCount(faction.id())));
        FactionService.ShieldDisplay shield = factions.shieldDisplay(faction.id());
        send(player, shield.active() ? "who-shield-active" : "who-shield-inactive",
                "time", me.vertex.core.grace.DurationParser.format(
                        shield.active() ? shield.remainingSeconds() : shield.nextSeconds()));
        if (online.isEmpty()) send(player, "who-online-empty");
        else send(player, "who-online", "count", String.valueOf(online.size()), "members", memberNames(online));
        if (offline.isEmpty()) send(player, "who-offline-empty");
        else send(player, "who-offline", "count", String.valueOf(offline.size()), "members", memberNames(offline));
        send(player, "who-footer");
    }

    private static boolean isOnline(FactionMember member) {
        Player player = Bukkit.getPlayer(member.playerUuid());
        return player != null && player.isOnline();
    }

    private static String memberNames(List<FactionMember> members) {
        return members.stream().map(FactionMember::lastName).collect(Collectors.joining(", "));
    }

    private void list(Player player) {
        List<FactionData> all = factions.factions().stream().filter(faction -> !faction.system()).toList();
        send(player, "list-header", "count", String.valueOf(all.size()));
        all.stream().limit(12).forEach(faction -> send(player, "list-entry", "faction", faction.tag(),
                "members", String.valueOf(factions.members(faction.id()).size()), "land", String.valueOf(factions.claimCount(faction.id()))));
    }

    private void power(Player player) {
        FactionPowerProfile profile = factions.ensurePowerProfile(player.getUniqueId());
        if (profile == null) { result(player, FactionService.Result.DATABASE_ERROR, "power"); return; }
        long remaining = factions.millisecondsUntilPowerRegeneration(player.getUniqueId());
        String next = remaining < 0L ? messages.getRaw(player, "native-factions.power-full")
                : me.vertex.core.grace.DurationParser.format((remaining + 999L) / 1_000L);
        send(player, "power-status", "power", number(profile.current()), "maximum", number(profile.maximum()),
                "next", next, "amount", plugin.getConfig().getString("factions.power.regeneration-per-interval", "1"),
                "interval", me.vertex.core.grace.DurationParser.format(factions.powerRegenerationIntervalMillis() / 1_000L));
    }

    private static String number(double value) {
        return Math.rint(value) == value ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    private void claim(Player player, String[] args) {
        int radius = 0;
        if (args.length > 1) try { radius = Math.max(0, Math.min(factions.serviceClaimRadiusLimit(), Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) { send(player, "claim-usage"); return; }
        ChunkKey centre = ChunkKey.of(player.getLocation()); int selectedRadius=radius;
        factions.submitMutation(()->claimArea(player,centre,selectedRadius)).whenComplete((outcome,error)->onMain(()->{
            if(error!=null||outcome==null){result(player,FactionService.Result.DATABASE_ERROR,"claimed");return;}
            if(outcome.successful()==0&&outcome.failure()!=null){result(player,outcome.failure(),"claimed");return;}
            send(player,outcome.successful()>0?"claimed-count":"claim-already-selected","count",String.valueOf(outcome.successful()));
        }));
    }

    private ClaimOutcome claimArea(Player player,ChunkKey centre,int radius){
        int successful=0;FactionService.Result failure=null;
        outer:for(int x=centre.x()-radius;x<=centre.x()+radius;x++)for(int z=centre.z()-radius;z<=centre.z()+radius;z++){
            FactionService.Result current=factions.claim(player,new ChunkKey(centre.world(),x,z),factions.claimsMayOverclaim());
            if(current==FactionService.Result.OK)successful++;
            else if(successful==0&&current!=FactionService.Result.ALREADY_CLAIMED){failure=current;break outer;}
        }
        return new ClaimOutcome(successful,failure);
    }
    private record ClaimOutcome(int successful,FactionService.Result failure){}

    private void map(Player player, String[] args) {
        if(args.length<2){player.sendMessage(factions.map(player));return;}
        if(args[1].equalsIgnoreCase("off")){factions.submitMutation(()->{factions.setMap(player.getUniqueId(),false);return true;})
                .whenComplete((ignored,error)->onMain(()->{if(error==null)send(player,"map-disabled");else result(player,FactionService.Result.DATABASE_ERROR,"map-disabled");}));return;}
        if(args[1].equalsIgnoreCase("on")){factions.submitMutation(()->{factions.setMap(player.getUniqueId(),true);return true;})
                .whenComplete((ignored,error)->onMain(()->{if(error==null)player.sendMessage(factions.map(player));else result(player,FactionService.Result.DATABASE_ERROR,"map-enabled");}));return;}
        player.sendMessage(factions.map(player));
    }

    private void chat(Player player, String[] args) {
        String selectedMode;
        if(args.length<2)selectedMode=null;
        else if(List.of("f","faction","fc").contains(args[1].toLowerCase(Locale.ROOT)))selectedMode="FACTION";
        else if(List.of("a","ally","allies").contains(args[1].toLowerCase(Locale.ROOT)))selectedMode="ALLY";
        else if(List.of("p","public","pc").contains(args[1].toLowerCase(Locale.ROOT)))selectedMode="PUBLIC";
        else{send(player,"chat-usage");return;}
        factions.submitMutation(()->selectedMode==null?factions.toggleChat(player.getUniqueId())
                        :factions.setChatMode(player.getUniqueId(),selectedMode))
                .whenComplete((resultMode,error)->onMain(()->{
                    if(error!=null){result(player,FactionService.Result.DATABASE_ERROR,"chat-public");return;}
                    send(player,resultMode.equals("ALLY")?"chat-ally":resultMode.equals("FACTION")?"chat-faction":"chat-public");
                }));
    }

    private void teleportHome(Player player) {
        int factionId = FactionsHook.getFactionId(player); FactionData.Home location = factions.homeRecord(factionId);
        if (location == null) { send(player, "home-not-set"); return; }
        if (!factions.hasAction(factions.member(player.getUniqueId()), "home")) { send(player, "home-denied"); return; }
        if (teleports == null || network == null) { send(player, "feature-unavailable"); return; }
        int seconds=Math.max(0,plugin.getConfig().getInt("teleports.countdown-seconds",5));
        teleports.request(player,"f-home",()->factionTarget(factions.homeRecord(factionId),
                messages.getRaw(player,"native-factions.home-display")),seconds,0L,true);
    }

    private void warp(Player player, String[] args) {
        int factionId = FactionsHook.getFactionId(player);
        if (args.length < 2) { send(player, "warp-list", "warps", factions.warps(factionId).stream().map(FactionWarp::name).reduce((a, b) -> a + ", " + b).orElse(messages.getRaw(player,"general.none"))); return; }
        FactionData.Home location = factions.warpRecord(factionId, args[1]);
        if (location == null) { send(player, "warp-not-found"); return; }
        if (!factions.hasAction(factions.member(player.getUniqueId()), "home")) { send(player, "warp-denied"); return; }
        if (teleports == null || network == null) { send(player, "feature-unavailable"); return; }
        String name=args[1];int seconds=Math.max(0,plugin.getConfig().getInt("teleports.countdown-seconds",5));
        teleports.request(player,"f-warp",()->factionTarget(factions.warpRecord(factionId,name),name),seconds,0L,true);
    }

    private TeleportManager.Target factionTarget(FactionData.Home home,String display){
        if(home==null||network==null)return null;String shard=home.shardId()==null||home.shardId().isBlank()?network.shardId():home.shardId();
        NetworkLocation target=new NetworkLocation(shard,home.world(),home.x(),home.y(),home.z(),home.yaw(),home.pitch());
        return new TeleportManager.Target(target,java.util.Objects.hash(shard,home.world(),home.x(),home.y(),home.z(),home.yaw(),home.pitch()),display);
    }

    private void money(Player player, String[] args) {
        FactionData faction = factions.faction(player).orElse(null);
        if (faction == null) { send(player, "no-faction"); return; }
        FactionBankManager bank = factionBank.get();
        if (bank == null) { send(player, "bank-starting"); return; }
        if (args.length < 2) { send(player, "money-balance", "amount", String.format(Locale.ROOT, "%,.2f", bank.money(faction.id()))); return; }
        double amount; try { amount = Double.parseDouble(arg(args, 2)); } catch (NumberFormatException error) { send(player, "money-usage"); return; }
        if (!Double.isFinite(amount) || amount <= 0D || !EconomyHook.isAvailable()) { send(player, "money-invalid"); return; }
        if (args[1].equalsIgnoreCase("deposit")) {
            if (!factions.hasAction(factions.member(player.getUniqueId()), "bank-deposit")) { send(player, "money-deposit-denied"); return; }
            EconomyResponse charged = EconomyHook.getEconomy().withdrawPlayer(player, amount);
            if (charged == null || !charged.transactionSuccess()) { send(player, "money-deposit-failed"); return; }
            bank.depositMoney(player, faction.id(), amount, "bank-deposit")
                    .whenComplete((saved, error) -> onMain(() -> {
                if (error != null || !Boolean.TRUE.equals(saved)) {
                    EconomyHook.getEconomy().depositPlayer(player, amount);
                    send(player, "money-deposit-rolled-back");
                    return;
                }
                bank.audit(faction.id(), "MONEY_DEPOSIT", player, "amount=" + amount);
                send(player, "money-deposited", "amount", String.format(Locale.ROOT, "%,.2f", amount));
            }));
        } else if (args[1].equalsIgnoreCase("withdraw")) {
            if (!factions.hasAction(factions.member(player.getUniqueId()), "bank-withdraw")) { send(player, "money-withdraw-denied"); return; }
            bank.withdrawMoney(player, faction.id(), amount, "bank-withdraw")
                    .whenComplete((saved, error) -> onMain(() -> {
                if (error != null || !Boolean.TRUE.equals(saved)) { send(player, "money-insufficient"); return; }
                EconomyResponse paid = EconomyHook.getEconomy().depositPlayer(player, amount);
                if (paid == null || !paid.transactionSuccess()) {
                    bank.depositMoney(faction.id(), amount);
                    send(player, "money-withdraw-rolled-back");
                    return;
                }
                bank.audit(faction.id(), "MONEY_WITHDRAW", player, "amount=" + amount);
                send(player, "money-withdrew", "amount", String.format(Locale.ROOT, "%,.2f", amount));
            }));
        } else send(player, "money-usage");
    }

    private void bank(Player player, String[] args) {
        if (args.length < 4) { send(player, "bank-usage"); return; }
        FactionData faction = factions.faction(player).orElse(null);
        FactionBankManager bank = factionBank.get();
        if (faction == null) { send(player, "no-faction"); return; }
        if (bank == null) { send(player, "bank-starting"); return; }
        String operation = args[1].toLowerCase(Locale.ROOT);
        if (!operation.equals("deposit") && !operation.equals("withdraw")) { send(player, "bank-usage"); return; }
        String currency = args[3].toLowerCase(Locale.ROOT);
        if (currency.equals("xp")) currency = "experience";
        if (!List.of("money", "experience", "tnt").contains(currency)) { send(player, "bank-invalid-currency"); return; }
        if (!factions.hasAction(factions.member(player.getUniqueId()),
                (currency.equals("experience") ? "xp-" : "bank-") + operation)) {
            send(player, "bank-permission-denied");
            return;
        }
        if (currency.equals("money")) {
            Double amount = Numbers.parseDoublePositive(args[2]);
            if (amount == null || !EconomyHook.isAvailable()) { send(player, "bank-invalid-amount"); return; }
            bankMoney(player, faction, bank, operation, amount);
            return;
        }
        Long amount = Numbers.parseLongPositive(args[2]);
        if (amount == null || amount > Integer.MAX_VALUE && currency.equals("experience")) { send(player, "bank-invalid-amount"); return; }
        if (currency.equals("experience")) bankExperience(player, faction, bank, operation, amount);
        else bankTnt(player, faction, bank, operation, amount);
    }

    private void bankMoney(Player player, FactionData faction, FactionBankManager bank, String operation, double amount) {
        if (operation.equals("deposit")) {
            if (!EconomyHook.getEconomy().has(player, amount)) { send(player, "bank-not-enough"); return; }
            EconomyResponse charged = EconomyHook.getEconomy().withdrawPlayer(player, amount);
            if (charged == null || !charged.transactionSuccess()) { send(player, "bank-failed"); return; }
            bank.depositMoney(player, faction.id(), amount, "bank-deposit").whenComplete((saved, error) -> onMain(() -> {
                if (error != null || !Boolean.TRUE.equals(saved)) { EconomyHook.getEconomy().depositPlayer(player, amount); send(player, "bank-failed"); return; }
                bank.audit(faction.id(), "MONEY_DEPOSIT", player, "amount=" + amount);
                send(player, "bank-success", "operation", "Deposited", "amount", Numbers.formatShort(amount), "currency", "money");
            }));
        } else {
            bank.withdrawMoney(player, faction.id(), amount, "bank-withdraw").whenComplete((saved, error) -> onMain(() -> {
                if (error != null || !Boolean.TRUE.equals(saved)) { send(player, "bank-not-enough"); return; }
                EconomyResponse paid = EconomyHook.getEconomy().depositPlayer(player, amount);
                if (paid == null || !paid.transactionSuccess()) { bank.depositMoney(faction.id(), amount); send(player, "bank-failed"); return; }
                bank.audit(faction.id(), "MONEY_WITHDRAW", player, "amount=" + amount);
                send(player, "bank-success", "operation", "Withdrew", "amount", Numbers.formatShort(amount), "currency", "money");
            }));
        }
    }

    private void bankExperience(Player player, FactionData faction, FactionBankManager bank, String operation, long amount) {
        int points = (int) amount;
        if (operation.equals("deposit")) {
            if (player.getTotalExperience() < points) { send(player, "bank-not-enough"); return; }
            player.giveExp(-points);
            bank.depositExperience(player, faction.id(), amount, "xp-deposit").whenComplete((saved, error) -> onMain(() -> {
                if (error != null || !Boolean.TRUE.equals(saved)) { player.giveExp(points); send(player, "bank-failed"); return; }
                bank.audit(faction.id(), "EXPERIENCE_DEPOSIT", player, "amount=" + amount);
                send(player, "bank-success", "operation", "Deposited", "amount", Numbers.formatShort(amount), "currency", "experience");
            }));
        } else {
            bank.withdrawExperience(player, faction.id(), amount, "xp-withdraw").whenComplete((saved, error) -> onMain(() -> {
                if (error != null || !Boolean.TRUE.equals(saved)) { send(player, "bank-not-enough"); return; }
                player.giveExp(points);
                bank.audit(faction.id(), "EXPERIENCE_WITHDRAW", player, "amount=" + amount);
                send(player, "bank-success", "operation", "Withdrew", "amount", Numbers.formatShort(amount), "currency", "experience");
            }));
        }
    }

    private void bankTnt(Player player, FactionData faction, FactionBankManager bank, String operation, long amount) {
        FactionUpgradeManager upgrades = factionUpgrades.get();
        if (upgrades == null) { send(player, "bank-starting"); return; }
        long capacity = upgrades.tntCapacity(faction.id());
        if (operation.equals("deposit")) {
            if (bank.tnt(faction.id()) > capacity - amount || amount > Integer.MAX_VALUE
                    || !player.getInventory().containsAtLeast(new ItemStack(Material.TNT), (int) amount)) { send(player, "bank-not-enough"); return; }
            player.getInventory().removeItem(new ItemStack(Material.TNT, (int) amount));
            bank.depositTnt(player, faction.id(), amount, capacity, "tnt-deposit").whenComplete((saved, error) -> onMain(() -> {
                if (error != null || !Boolean.TRUE.equals(saved)) { player.getInventory().addItem(new ItemStack(Material.TNT, (int) amount)); send(player, "bank-failed"); return; }
                bank.audit(faction.id(), "TNT_DEPOSIT", player, "amount=" + amount);
                send(player, "bank-success", "operation", "Deposited", "amount", Numbers.formatShort(amount), "currency", "TNT");
            }));
        } else {
            if (amount > Integer.MAX_VALUE || !canFitTnt(player.getInventory(), amount)) { send(player, "bank-inventory-full"); return; }
            bank.withdrawTnt(player, faction.id(), amount, "tnt-withdraw").whenComplete((saved, error) -> onMain(() -> {
                if (error != null || !Boolean.TRUE.equals(saved)) { send(player, "bank-not-enough"); return; }
                if (!canFitTnt(player.getInventory(), amount)) { bank.depositTnt(faction.id(), amount, capacity); send(player, "bank-inventory-full"); return; }
                player.getInventory().addItem(new ItemStack(Material.TNT, (int) amount));
                bank.audit(faction.id(), "TNT_WITHDRAW", player, "amount=" + amount);
                send(player, "bank-success", "operation", "Withdrew", "amount", Numbers.formatShort(amount), "currency", "TNT");
            }));
        }
    }

    private static boolean canFitTnt(Inventory inventory, long amount) {
        long room = 0L;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.isEmpty()) room += Material.TNT.getMaxStackSize();
            else if (item.getType() == Material.TNT) room += Material.TNT.getMaxStackSize() - item.getAmount();
            if (room >= amount) return true;
        }
        return room >= amount;
    }

    private void tnt(Player player) {
        FactionData faction = factions.faction(player).orElse(null);
        if (faction == null) { send(player, "no-faction"); return; }
        FactionBankManager bank = factionBank.get();
        FactionUpgradeManager upgrades = factionUpgrades.get();
        if (bank == null || upgrades == null) { send(player, "bank-starting"); return; }
        send(player, "tnt-balance", "amount", String.format(Locale.ROOT, "%,d", bank.tnt(faction.id())),
                "maximum", String.format(Locale.ROOT, "%,d", upgrades.tntCapacity(faction.id())));
    }

    private void onMain(Runnable task) { Bukkit.getScheduler().runTask(plugin, task); }

    private void systemClaim(Player player, String type, String[] args) {
        int radius = 0;
        if (args.length > 1) {
            try {
                radius = Math.max(0, Math.min(factions.serviceClaimRadiusLimit(), Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                send(player, "admin-usage");
                return;
            }
        }
        String tag = factionsTag(type);
        ChunkKey key=ChunkKey.of(player.getLocation());
        int selectedRadius = radius;
        factions.submitMutation(() -> factions.claimSystemArea(player, tag, key, selectedRadius))
                .whenComplete((outcome, error) -> onMain(() -> {
                    if (error != null || outcome == null) {
                        result(player, FactionService.Result.DATABASE_ERROR, "system-claim-created", "faction", tag);
                        return;
                    }
                    if (outcome.successful() == 0 && outcome.failure() != null) {
                        result(player, outcome.failure(), "system-claim-created", "faction", tag);
                        return;
                    }
                    send(player, "system-claim-created", "faction", tag,
                            "count", String.valueOf(outcome.successful()));
                }));
    }

    private void admin(Player player, String[] args) {
        if (!player.hasPermission("vertex.factions.admin")) { player.sendMessage(messages.get(player, "general.no-permission")); return; }
        if (args.length < 2) { send(player, "admin-usage"); return; }
        if (args[1].equalsIgnoreCase("safezone") || args[1].equalsIgnoreCase("warzone")) { systemClaim(player, args[1].toLowerCase(Locale.ROOT), args); return; }
        if (args[1].equalsIgnoreCase("unclaim")) { ChunkKey key=ChunkKey.of(player.getLocation());factions.submitMutation(()->factions.forceUnclaim(key)).whenComplete((removed,error)->onMain(()->send(player,error==null&&Boolean.TRUE.equals(removed)?"admin-claim-removed":"admin-no-claim")));return; }
        send(player, "admin-usage");
    }

    private String factionsTag(String type) { return factions.systemTag(type); }

    private void result(Player player, FactionService.Result outcome, String success, String... placeholders) {
        if (outcome == FactionService.Result.OK) send(player, success, placeholders);
        else player.sendMessage(messages.get(player, "native-factions.result." + outcome.name().toLowerCase(Locale.ROOT).replace('_', '-')));
    }
    private void mutate(Player player,Supplier<FactionService.Result> operation,String success,String...placeholders){
        factions.submitMutation(operation).whenComplete((outcome,error)->onMain(()->result(player,
                error==null&&outcome!=null?outcome:FactionService.Result.DATABASE_ERROR,success,placeholders)));
    }
    private void send(Player player, String key, String... placeholders) {
        player.sendMessage(messages.get(player, "native-factions." + key, placeholders));
    }
    private void help(Player player, String rawPage) {
        int page;
        try { page = Integer.parseInt(rawPage); } catch (NumberFormatException ignored) { page = 1; }
        int pages = Math.max(1, (HELP_KEYS.size() + 7) / 8);
        page = Math.max(1, Math.min(page, pages));
        player.sendMessage(messages.get(player, "native-factions.help-header", "page", String.valueOf(page), "pages", String.valueOf(pages)));
        HELP_KEYS.stream().skip((long) (page - 1) * 8L).limit(8)
                .forEach(key -> player.sendMessage(messages.get(player, "native-factions.help-line",
                        "command", messages.getRaw(player, "native-factions." + key))));
        Component navigation = Component.empty();
        if (page > 1) navigation = navigation.append(messages.get(player, "native-factions.help-prev")
                .clickEvent(ClickEvent.runCommand("/f help " + (page - 1))));
        if (page > 1 && page < pages) navigation = navigation.append(Component.text(" "));
        if (page < pages) navigation = navigation.append(messages.get(player, "native-factions.help-next")
                .clickEvent(ClickEvent.runCommand("/f help " + (page + 1))));
        if (page > 1 || page < pages) player.sendMessage(navigation);
    }
    private static String arg(String[] args, int index) { return index < args.length ? args[index] : ""; }
    private static String join(String[] args, int start) { return start >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, start, args.length)); }
    private static FactionRole next(FactionRole role) { return switch (role) { case RECRUIT -> FactionRole.MEMBER; case MEMBER -> FactionRole.MODERATOR; case MODERATOR -> FactionRole.ADMIN; case ADMIN -> FactionRole.COLEADER; default -> role; }; }
    private static FactionRole previous(FactionRole role) { return switch (role) { case COLEADER -> FactionRole.ADMIN; case ADMIN -> FactionRole.MODERATOR; case MODERATOR -> FactionRole.MEMBER; case MEMBER -> FactionRole.RECRUIT; default -> role; }; }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return complete(args[0], ROOT.stream());
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("help")) return complete(args[1], Stream.of("1", "2", "3"));
        if (args.length == 2 && List.of("join", "ally", "neutral", "enemy", "who").contains(sub)) return complete(args[1], factions.factions().stream().filter(faction -> !faction.system()).map(FactionData::tag));
        if (args.length == 2 && List.of("invite", "kick", "promote", "demote", "leader").contains(sub)) return complete(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName));
        if (args.length == 2 && sub.equals("map")) return complete(args[1], Stream.of("on", "off"));
        if (args.length == 2 && (sub.equals("chat") || sub.equals("c"))) return complete(args[1], Stream.of("faction", "ally", "public"));
        if (args.length == 2 && sub.equals("money")) return complete(args[1], Stream.of("deposit", "withdraw"));
        if (args.length == 2 && sub.equals("bank")) return complete(args[1], Stream.of("deposit", "withdraw"));
        if (args.length == 4 && sub.equals("bank")) return complete(args[3], Stream.of("money", "experience", "xp", "tnt"));
        if (args.length == 2 && sub.equals("admin")) return complete(args[1], Stream.of("safezone", "warzone", "unclaim"));
        if (args.length == 2 && sub.equals("warp") && sender instanceof Player player) return complete(args[1], factions.warps(FactionsHook.getFactionId(player)).stream().map(FactionWarp::name));
        return List.of();
    }
    private static List<String> complete(String partial, Stream<String> values) { String safe = partial.toLowerCase(Locale.ROOT); return values.filter(value -> value.toLowerCase(Locale.ROOT).startsWith(safe)).sorted(String.CASE_INSENSITIVE_ORDER).toList(); }
}
