package me.vertex.core.placeholderapi;

import dev.kitteh.factions.Faction;
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
import me.vertex.core.zone.ZoneManager;
import me.vertex.core.zone.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
    private volatile ZoneManager zoneManager;

    public VertexPlaceholderExpansion(VertexPlugin plugin, UserManager userManager, KitManager kitManager,
                                       AbilityManager abilityManager, CombatManager combatManager,
                                       FactionBankManager factionBankManager, StaffManager staffManager,
                                       RebootManager rebootManager, BackpackManager backpackManager,
                                       TagManager tagManager, BlueprintManager blueprintManager,
                                       CoinflipManager coinflipManager, AuctionManager auctionManager,
                                       TradeManager tradeManager, RallyManager rallyManager,
                                       FactionUpgradeManager factionUpgradeManager, SandBotManager sandBotManager) {
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
            return "";
        }

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
            case "faction_money" -> factionMoney(player);
            case "faction_bank_money" -> factionBankMoney(player);
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

    private String factionMoney(Player player) {
        if (!FactionsHook.isFactionMoneyAvailable()) {
            return "";
        }
        Faction faction = FactionsHook.getFaction(player);
        if (faction == null) {
            return "0";
        }
        return EconomyHook.format(dev.kitteh.factions.integration.Econ.getBalance(faction));
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
