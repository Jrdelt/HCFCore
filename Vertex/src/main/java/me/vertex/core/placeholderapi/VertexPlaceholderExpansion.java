package me.vertex.core.placeholderapi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.vertex.core.VertexPlugin;
import me.vertex.core.ability.Ability;
import me.vertex.core.ability.AbilityManager;
import me.vertex.core.auction.AuctionManager;
import me.vertex.core.backpack.BackpackManager;
import me.vertex.core.blueprint.BlueprintManager;
import me.vertex.core.coinflip.CoinflipManager;
import me.vertex.core.economy.EconomyHook;
import me.vertex.core.essentials.EssentialsHook;
import me.vertex.core.faction.FactionBankManager;
import me.vertex.core.faction.FactionUpgrade;
import me.vertex.core.faction.FactionUpgradeManager;
import me.vertex.core.faction.RallyManager;
import me.vertex.core.factions.FactionsHook;
import me.vertex.core.factions.FactionData;
import me.vertex.core.factions.FactionMember;
import me.vertex.core.factions.FactionPowerProfile;
import me.vertex.core.factions.FactionRelation;
import me.vertex.core.factions.FactionRole;
import me.vertex.core.kit.Kit;
import me.vertex.core.kit.KitManager;
import me.vertex.core.luckperms.LuckPermsHook;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.reboot.RebootManager;
import me.vertex.core.sandbot.SandBotManager;
import me.vertex.core.staff.StaffManager;
import me.vertex.core.tag.TagManager;
import me.vertex.core.trade.TradeManager;
import me.vertex.core.user.User;
import me.vertex.core.user.UserManager;
import me.vertex.core.stats.PlayerStatsManager;
import me.vertex.core.util.Numbers;
import me.vertex.core.zone.ZoneManager;
import me.vertex.core.zone.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Exposes Vertex's own data (faction stats, cooldowns, combat status, the
 * faction bank, ...) as {@code %vertex_<key>%} PlaceholderAPI tokens, for
 * any external plugin (a scoreboard/tablist plugin, a hologram plugin,
 * etc.) to consume -- Vertex itself only *consumes* PlaceholderAPI tokens
 * elsewhere (see {@link PlaceholderApiHook}); this is what lets it also
 * *provide* some.
 *
 * <p>Every placeholder here reads live off the same managers Vertex's own
 * commands/listeners use -- nothing is cached or duplicated, so this can
 * never drift from what {@code /balance}, faction chat, etc. would show.
 */
public final class VertexPlaceholderExpansion extends PlaceholderExpansion {

    private final VertexPlugin plugin;
    private final UserManager userManager;
    private final KitManager kitManager;
    private final AbilityManager abilityManager;
    private final CombatManager combatManager;
    private final FactionBankManager factionBankManager;
    private final StaffManager staffManager;
    private final RebootManager rebootManager;
    private final BackpackManager backpackManager;
    private final TagManager tagManager;
    private final BlueprintManager blueprintManager;
    private final CoinflipManager coinflipManager;
    private final AuctionManager auctionManager;
    private final TradeManager tradeManager;
    private final RallyManager rallyManager;
    private final FactionUpgradeManager factionUpgradeManager;
    private final SandBotManager sandBotManager;
    private final PlayerStatsManager playerStatsManager;
    private volatile ZoneManager zoneManager;

    public VertexPlaceholderExpansion(VertexPlugin plugin, UserManager userManager, KitManager kitManager,
                                       AbilityManager abilityManager, CombatManager combatManager,
                                       FactionBankManager factionBankManager, StaffManager staffManager,
                                       RebootManager rebootManager, BackpackManager backpackManager,
                                       TagManager tagManager, BlueprintManager blueprintManager,
                                       CoinflipManager coinflipManager, AuctionManager auctionManager,
                                       TradeManager tradeManager, RallyManager rallyManager,
                                       FactionUpgradeManager factionUpgradeManager, SandBotManager sandBotManager,
                                       PlayerStatsManager playerStatsManager) {
        this.plugin = plugin;
        this.userManager = userManager;
        this.kitManager = kitManager;
        this.abilityManager = abilityManager;
        this.combatManager = combatManager;
        this.factionBankManager = factionBankManager;
        this.staffManager = staffManager;
        this.rebootManager = rebootManager;
        this.backpackManager = backpackManager;
        this.tagManager = tagManager;
        this.blueprintManager = blueprintManager;
        this.coinflipManager = coinflipManager;
        this.auctionManager = auctionManager;
        this.tradeManager = tradeManager;
        this.rallyManager = rallyManager;
        this.factionUpgradeManager = factionUpgradeManager;
        this.sandBotManager = sandBotManager;
        this.playerStatsManager = playerStatsManager;
    }

    /** Installed after Haven/Riftlands has completed its durable startup load. */
    public void setZoneManager(ZoneManager zoneManager) {
        this.zoneManager = zoneManager;
    }

    @Override
    public String getIdentifier() {
        return "vertex";
    }

    @Override
    public String getAuthor() {
        return "cesardeltorojr";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    /** Config/data reloads happen live off the same managers -- nothing here needs a PlaceholderAPI-side reload. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) {
            return "&7";
        }

        String finalized = finalizedPlaceholder(player, identifier);
        if (finalized != null) return finalized;

        if (identifier.startsWith("kit_cooldown_")) {
            return kitCooldown(player, identifier.substring("kit_cooldown_".length()));
        }
        if (identifier.startsWith("ability_cooldown_")) {
            return abilityCooldown(player, identifier.substring("ability_cooldown_".length()));
        }
        if (identifier.startsWith("upgrade_") && identifier.endsWith("_level")) {
            return upgradeLevel(player, identifier.substring("upgrade_".length(), identifier.length() - "_level".length()));
        }

        return switch (identifier) {
            case "name" -> EssentialsHook.resolveName(player);
            case "rank" -> LuckPermsHook.getPrimaryGroupDisplayName(player);
            case "rank_prefix" -> {
                String rank = LuckPermsHook.getPrimaryGroupDisplayName(player);
                yield rank == null || rank.isBlank() ? "" : "[" + rank + "]";
            }
            case "prefix" -> LuckPermsHook.getPrefix(player);
            case "balance" -> EconomyHook.getBalance(player);

            case "faction", "faction_tag" -> FactionsHook.getFactionTag(player);
            case "faction_role" -> FactionsHook.getRoleName(player);
            case "faction_power" -> FactionsHook.getFactionPower(player);
            case "faction_ftop" -> FactionsHook.getFactionTop(player);
            case "faction_online" -> FactionsHook.getOnlineFactionCount(player);
            case "faction_money", "faction_bank_money" -> factionBankMoney(player);
            case "faction_bank_xp" -> factionBankXp(player);

            case "combat_tagged" -> yesNo(combatManager != null && combatManager.isTagged(player.getUniqueId()));
            case "combat_remaining" -> combatRemaining(player);
            case "combat_opponent" -> combatOpponent(player);

            case "staff_vanished" -> yesNo(staffManager != null && staffManager.isVanished(player.getUniqueId()));
            case "staff_build" -> yesNo(staffManager != null && staffManager.isStaffBuild(player.getUniqueId()));

            case "reboot_scheduled" -> yesNo(rebootManager != null && rebootManager.isScheduled());
            case "reboot_remaining" -> rebootManager == null ? "0" : String.valueOf(rebootManager.getRemainingSeconds());

            case "online" -> String.valueOf(Bukkit.getOnlinePlayers().size());
            case "date" -> LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd", Locale.ROOT));
            case "storage_type" -> plugin.storageDialect().name().toLowerCase(Locale.ROOT);

            case "backpack_tier" -> backpackTier(player);
            case "backpack_stored" -> backpackStored(player);
            case "backpack_capacity" -> backpackCapacity(player);

            case "tag" -> tagDisplay(player);

            case "blueprint_cooldown" -> blueprintCooldown(player);

            case "coinflip_active" -> coinflipManager == null ? "0" : String.valueOf(coinflipManager.activeCoinflips().size());
            case "auction_active" -> auctionManager == null ? "0" : String.valueOf(auctionManager.activeListings().size());
            case "trade_accepting" -> yesNo(tradeManager != null && tradeManager.isAccepting(player.getUniqueId()));

            case "zone", "current_zone" -> zoneManager == null ? "None" : zoneManager.currentZone(player);
            case "haven_kills" -> zoneManager == null ? "0" : String.valueOf(zoneManager.kills(player, ZoneType.HAVEN));
            case "riftlands_kills" -> zoneManager == null ? "0" : String.valueOf(zoneManager.kills(player, ZoneType.RIFTLANDS));
            case "haven_progress" -> zoneManager == null ? "0" : trim(zoneManager.progressionBoost(player, ZoneType.HAVEN));
            case "riftlands_progress" -> zoneManager == null ? "0" : trim(zoneManager.progressionBoost(player, ZoneType.RIFTLANDS));
            case "haven_next_milestone" -> zoneManager == null ? "0" : String.valueOf(zoneManager.nextMilestone(player, ZoneType.HAVEN));
            case "riftlands_next_milestone" -> zoneManager == null ? "0" : String.valueOf(zoneManager.nextMilestone(player, ZoneType.RIFTLANDS));
            case "haven_next_remaining" -> zoneManager == null ? "0" : String.valueOf(Math.max(0L, zoneManager.nextMilestone(player, ZoneType.HAVEN) - zoneManager.kills(player, ZoneType.HAVEN)));
            case "riftlands_next_remaining" -> zoneManager == null ? "0" : String.valueOf(Math.max(0L, zoneManager.nextMilestone(player, ZoneType.RIFTLANDS) - zoneManager.kills(player, ZoneType.RIFTLANDS)));
            case "zone_amplification" -> zoneManager == null ? "0" : trim(zoneManager.amplification(player, zoneManager.isIn(player, ZoneType.RIFTLANDS) ? ZoneType.RIFTLANDS : ZoneType.HAVEN));
            case "zone_event_active" -> yesNo(zoneManager != null && zoneManager.eventActive());
            case "zone_event_score" -> zoneManager == null ? "0" : trim(zoneManager.eventScore(player));
            case "zone_event_rank" -> zoneManager == null ? "0" : String.valueOf(zoneManager.eventRank(player));
            case "zone_event_remaining" -> zoneManager == null ? "0" : String.valueOf(zoneManager.eventRemainingSeconds());
            case "zone_winner_boost" -> zoneManager == null ? "0" : trim(zoneManager.winnerBoost(player));
            case "zone_backpack_amplification" -> backpackManager == null ? "0" : trim(Math.max(0D, backpackManager.equippedDropBonusPercent(player)));
            case "zone_event_top_1_name" -> zoneManager == null ? "-" : zoneManager.eventTopName(1);
            case "zone_event_top_2_name" -> zoneManager == null ? "-" : zoneManager.eventTopName(2);
            case "zone_event_top_3_name" -> zoneManager == null ? "-" : zoneManager.eventTopName(3);
            case "zone_event_top_1_score" -> zoneManager == null ? "0" : trim(zoneManager.eventTopScore(1));
            case "zone_event_top_2_score" -> zoneManager == null ? "0" : trim(zoneManager.eventTopScore(2));
            case "zone_event_top_3_score" -> zoneManager == null ? "0" : trim(zoneManager.eventTopScore(3));
            case "haven_death_cooldown" -> zoneManager == null ? "0" : String.valueOf(zoneManager.cooldownRemaining(player, ZoneType.HAVEN));
            case "riftlands_death_cooldown" -> zoneManager == null ? "0" : String.valueOf(zoneManager.cooldownRemaining(player, ZoneType.RIFTLANDS));

            case "rally_active" -> yesNo(rallyManager != null && rallyManager.hasActiveRally(player));

            case "sandbot_active" -> sandBotManager == null ? "0"
                    : String.valueOf(sandBotManager.activeSessionsOwnedBy(player.getUniqueId()));

            default -> null;
        };
    }

    /** Finalized addme.md placeholder set, including suffix-target syntax such as player_power_Notch. */
    private String finalizedPlaceholder(Player viewer, String identifier) {
        PlaceholderRequest request = parseFinalized(identifier);
        if (request == null) return null;
        Subject subject = resolveSubject(viewer, request.targetName());
        if (subject == null) return "&7";
        UUID uuid = subject.uuid();
        Player online = Bukkit.getPlayer(uuid);
        FactionMember member = FactionsHook.service().member(uuid);
        FactionData faction = member == null ? null : FactionsHook.service().faction(member.factionId()).orElse(null);
        FactionPowerProfile power = FactionsHook.service().powerProfile(uuid);
        String missing = "&7";

        return switch (request.key()) {
            case "player" -> "&7" + (online == null ? subject.name() : EssentialsHook.resolveName(online));
            case "player_name" -> subject.name();
            case "player_title" -> member == null ? missing : member.role().displayName();
            case "player_name_and_title" -> member == null ? subject.name()
                    : subject.name() + " &8| " + member.role().displayName();
            case "player_role" -> member == null ? missing : member.role().displayName();
            case "player_power" -> power == null ? missing : compact(power.current());
            case "player_max_power" -> power == null ? missing : compact(power.maximum());
            case "player_regentime" -> power == null ? missing : power.current() >= power.maximum()
                    ? "Full" : compactDuration(FactionsHook.service().millisecondsUntilPowerRegeneration(uuid), false);
            case "player_kills" -> playerStatsManager == null ? missing
                    : String.valueOf(playerStatsManager.stats(uuid).kills());
            case "player_deaths" -> playerStatsManager == null ? missing
                    : String.valueOf(playerStatsManager.stats(uuid).deaths());
            case "player_kills_deaths" -> playerStatsManager == null ? missing
                    : playerStatsManager.stats(uuid).kills() + "/" + playerStatsManager.stats(uuid).deaths();
            case "player_last_seen" -> online != null ? "Online" : relativeTime(subject.lastSeenMillis());
            case "faction" -> factionName(viewer, faction);
            case "faction_description" -> faction == null || faction.description().isBlank() ? missing : faction.description();
            case "faction_creation" -> faction == null ? missing : relativeTime(faction.createdAtMillis());
            case "faction_leader" -> faction == null ? missing : FactionsHook.service().members(faction.id()).stream()
                    .filter(candidate -> candidate.role() == FactionRole.LEADER).map(FactionMember::lastName)
                    .findFirst().orElse(missing);
            case "faction_members" -> faction == null ? missing : String.valueOf(FactionsHook.service().members(faction.id()).size());
            case "faction_members_online" -> faction == null ? missing : String.valueOf(Bukkit.getOnlinePlayers().stream()
                    .filter(candidate -> FactionsHook.getFactionId(candidate) == faction.id()).count());
            case "faction_power" -> faction == null ? missing : compact(faction.power());
            case "faction_max_power" -> faction == null ? missing : compact(faction.powerMax());
            case "faction_power_display" -> faction == null ? missing : compact(faction.power()) + "/" + compact(faction.powerMax());
            case "faction_claims" -> faction == null ? missing : String.valueOf(FactionsHook.service().claimCount(faction.id()));
            case "faction_max_claims" -> faction == null ? missing : String.valueOf(Math.max(0L, (long) Math.floor(faction.power())));
            case "faction_claim_balance" -> faction == null ? missing : String.valueOf((long) Math.floor(faction.power())
                    - FactionsHook.service().claimCount(faction.id()));
            case "faction_warps" -> faction == null ? missing : String.valueOf(FactionsHook.service().warps(faction.id()).size());
            case "faction_max_warps" -> faction == null ? missing : String.valueOf(FactionsHook.service().warpLimit(faction.id()));
            case "faction_bank" -> faction == null || factionBankManager == null ? missing
                    : EconomyHook.format(factionBankManager.money(faction.id()));
            case "faction_bank_formatted" -> faction == null || factionBankManager == null ? missing
                    : Numbers.money(factionBankManager.money(faction.id()));
            case "faction_tnt_bank" -> faction == null || factionBankManager == null ? missing
                    : String.valueOf(factionBankManager.tnt(faction.id()));
            case "faction_tnt_bank_max" -> faction == null || factionUpgradeManager == null ? missing
                    : String.valueOf(factionUpgradeManager.tntCapacity(faction.id()));
            case "faction_shield_status" -> faction == null ? missing
                    : FactionsHook.service().shieldDisplay(faction.id()).active() ? "&aActive" : "&cInactive";
            case "faction_shield_remaining" -> faction == null ? missing
                    : "&7" + compactDuration(FactionsHook.service().shieldDisplay(faction.id()).remainingSeconds() * 1_000L, false);
            case "faction_shield_next" -> faction == null ? missing
                    : "&7" + compactDuration(FactionsHook.service().shieldDisplay(faction.id()).nextSeconds() * 1_000L, false);
            default -> null;
        };
    }

    private PlaceholderRequest parseFinalized(String raw) {
        if (raw == null) return null;
        String identifier = raw.toLowerCase(Locale.ROOT);
        // Longest first prevents player_name_and_title from being parsed as player_name + a target.
        for (String key : FINALIZED_KEYS) {
            if (identifier.equals(key)) return new PlaceholderRequest(key, null);
            String prefix = key + "_";
            if (identifier.startsWith(prefix) && raw.length() > prefix.length()) {
                return new PlaceholderRequest(key, raw.substring(prefix.length()));
            }
        }
        return null;
    }

    private Subject resolveSubject(Player viewer, String targetName) {
        if (targetName == null || targetName.isBlank()) {
            return new Subject(viewer.getUniqueId(), viewer.getName(), viewer.getLastSeen());
        }
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) return new Subject(online.getUniqueId(), online.getName(), online.getLastSeen());
        Optional<FactionMember> member = FactionsHook.service().member(targetName);
        if (member.isPresent()) {
            OfflinePlayer cached = Bukkit.getOfflinePlayer(member.get().playerUuid());
            return new Subject(member.get().playerUuid(), member.get().lastName(), cached.getLastSeen());
        }
        return null;
    }

    private String factionName(Player viewer, FactionData target) {
        if (target == null) return "&8[&7None&8]";
        int viewerFaction = FactionsHook.getFactionId(viewer);
        String color;
        if (viewerFaction == target.id()) color = "&a";
        else {
            FactionRelation relation = FactionsHook.service().relation(viewerFaction, target.id());
            color = relation == FactionRelation.ALLY ? "&d" : relation == FactionRelation.ENEMY ? "&c" : "&7";
        }
        return "&8[" + color + target.tag() + "&8]";
    }

    private static String compact(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String compactDuration(long millis, boolean ago) {
        if (millis < 0L) return "&7";
        long seconds = Math.max(0L, millis / 1_000L);
        long years = seconds / 31_536_000L; seconds %= 31_536_000L;
        long months = seconds / 2_592_000L; seconds %= 2_592_000L;
        long days = seconds / 86_400L; seconds %= 86_400L;
        long hours = seconds / 3_600L; seconds %= 3_600L;
        long minutes = seconds / 60L; seconds %= 60L;
        String value;
        if (years > 0) value = years + "y" + (months > 0 ? " " + months + "mo" : "");
        else if (months > 0) value = months + "mo" + (days > 0 ? " " + days + "d" : "");
        else if (days > 0) value = days + "d" + (hours > 0 ? " " + hours + "h" : "");
        else if (hours > 0) value = hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        else if (minutes > 0) value = minutes + "m" + (seconds > 0 ? " " + seconds + "s" : "");
        else value = seconds + "s";
        return ago ? value + " ago" : value;
    }

    private static String relativeTime(long timestamp) {
        return timestamp <= 0L ? "&7" : compactDuration(System.currentTimeMillis() - timestamp, true);
    }

    private static final List<String> FINALIZED_KEYS = List.of(
            "player_name_and_title", "player_kills_deaths", "faction_members_online",
            "faction_bank_formatted", "faction_shield_remaining", "faction_tnt_bank_max",
            "faction_power_display", "faction_claim_balance", "faction_shield_status",
            "faction_description", "faction_creation", "faction_max_claims", "faction_max_power",
            "faction_members", "faction_max_warps", "faction_tnt_bank", "faction_shield_next",
            "player_max_power", "player_regentime", "player_last_seen", "player_deaths",
            "player_title", "player_name", "player_power", "player_kills", "player_role",
            "faction_leader", "faction_claims", "faction_warps", "faction_power",
            "faction_bank", "player", "faction");

    private record PlaceholderRequest(String key, String targetName) { }
    private record Subject(UUID uuid, String name, long lastSeenMillis) { }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    private String kitCooldown(Player player, String kitName) {
        if (kitManager == null) {
            return "";
        }
        Kit kit = kitManager.get(kitName);
        User user = userManager == null ? null : userManager.get(player.getUniqueId());
        if (kit == null || user == null) {
            return "";
        }
        long remainingSeconds = (user.getCooldownExpiry(kit.getName()) - System.currentTimeMillis() + 999) / 1000;
        return remainingSeconds <= 0 ? "Ready" : String.valueOf(remainingSeconds);
    }

    private String abilityCooldown(Player player, String abilityId) {
        if (abilityManager == null) {
            return "";
        }
        Ability ability = abilityManager.get(abilityId);
        User user = userManager == null ? null : userManager.get(player.getUniqueId());
        if (ability == null || user == null) {
            return "";
        }
        if (!abilityManager.isOnCooldown(user, ability)) {
            return "Ready";
        }
        return String.valueOf((abilityManager.remainingCooldownMillis(user, ability) + 999) / 1000);
    }

    private String combatRemaining(Player player) {
        if (combatManager == null) {
            return "0";
        }
        long remainingMillis = combatManager.remainingMillis(player.getUniqueId());
        return String.valueOf(Math.max(0, (remainingMillis + 999) / 1000));
    }

    private String combatOpponent(Player player) {
        if (combatManager == null || !combatManager.isTagged(player.getUniqueId())) {
            return "None";
        }
        UUID opponentId = combatManager.getOpponentId(player.getUniqueId());
        if (opponentId == null) {
            return "Unknown";
        }
        if (opponentId.equals(CombatManager.SERVER_UUID)) {
            return "the server";
        }
        Player opponent = Bukkit.getPlayer(opponentId);
        return opponent == null ? "Unknown" : opponent.getName();
    }

    private String factionBankMoney(Player player) {
        if (factionBankManager == null) {
            return "0";
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            return "0";
        }
        return EconomyHook.format(factionBankManager.money(factionId));
    }

    private String factionBankXp(Player player) {
        if (factionBankManager == null) {
            return "0";
        }
        int factionId = FactionsHook.getFactionId(player);
        if (factionId == FactionsHook.NO_FACTION) {
            return "0";
        }
        return String.valueOf(factionBankManager.experience(factionId));
    }

    private String upgradeLevel(Player player, String configKey) {
        if (factionUpgradeManager == null) {
            return "";
        }
        FactionUpgrade upgrade = FactionUpgrade.fromConfigKey(configKey);
        if (upgrade == null) {
            return "";
        }
        return String.valueOf(factionUpgradeManager.level(player, upgrade));
    }

    private String backpackTier(Player player) {
        if (backpackManager == null) {
            return "None";
        }
        String tierName = backpackManager.equippedTierDisplayName(player);
        return tierName == null ? "None" : tierName;
    }

    private String backpackStored(Player player) {
        if (backpackManager == null) {
            return "0";
        }
        long stored = backpackManager.equippedStoredCount(player);
        return stored < 0 ? "0" : String.valueOf(stored);
    }

    private String backpackCapacity(Player player) {
        if (backpackManager == null) {
            return "0";
        }
        long capacity = backpackManager.equippedCapacity(player);
        return capacity < 0 ? "0" : String.valueOf(capacity);
    }

    private String tagDisplay(Player player) {
        if (tagManager == null) {
            return "";
        }
        String tagId = tagManager.getPlayerTag(player.getUniqueId());
        if (tagId == null || tagId.isBlank()) {
            return "";
        }
        var tag = tagManager.get(tagId);
        return tag == null ? "" : tag.display();
    }

    private String blueprintCooldown(Player player) {
        if (blueprintManager == null) {
            return "Ready";
        }
        UUID uuid = player.getUniqueId();
        if (!blueprintManager.isOnCooldown(uuid)) {
            return "Ready";
        }
        return String.valueOf((blueprintManager.remainingCooldownMillis(uuid) + 999) / 1000);
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }
}
