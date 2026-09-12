package me.vertex.core.factions;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Stable access point used by the rest of Vertex for faction state.
 *
 * <p>The class name is retained so existing Vertex modules do not need to
 * know which faction implementation owns the data. It is now backed solely
 * by {@link FactionService}; FactionsUUID is neither read nor required.</p>
 */
public final class FactionsHook {
    public static final int NO_FACTION = FactionService.NO_FACTION;
    private static volatile FactionService service;

    private FactionsHook() { }

    public static void install(FactionService factionService) { service = factionService; }

    /** Whether the native faction service has completed startup. */
    public static boolean isInstalled() { return service != null; }

    public static FactionService service() {
        FactionService current = service;
        if (current == null) throw new IllegalStateException("Vertex faction service has not been initialized.");
        return current;
    }

    public static String getFactionTag(Player player) {
        return optional().flatMap(current -> current.faction(player)).map(FactionData::tag).orElse("None");
    }

    public static String getRoleName(Player player) {
        if (player == null) return "None";
        return optional().map(current -> current.member(player.getUniqueId()))
                .map(member -> member.role().displayName()).orElse("None");
    }

    public static boolean isLeader(Player player) {
        return player != null && optional().map(current -> current.member(player.getUniqueId()))
                .map(member -> member.role() == FactionRole.LEADER).orElse(false);
    }

    public static boolean isSameFaction(Player left, Player right) {
        if (left == null || right == null) return false;
        int first = getFactionId(left);
        return first != NO_FACTION && first == getFactionId(right);
    }

    public static int getFactionId(Player player) { return optional().map(current -> current.factionId(player)).orElse(NO_FACTION); }

    public static List<Player> getOnlineFactionMembers(Player player) {
        if (player == null) return List.of();
        int factionId = getFactionId(player);
        if (factionId == NO_FACTION) return List.of(player);
        return Bukkit.getOnlinePlayers().stream().filter(candidate -> getFactionId(candidate) == factionId)
                .map(candidate -> (Player) candidate).toList();
    }

    public static void messageFaction(int factionId, Component message) {
        if (factionId == NO_FACTION || message == null) return;
        Bukkit.getOnlinePlayers().stream().filter(player -> getFactionId(player) == factionId).forEach(player -> player.sendMessage(message));
    }

    public static String getFactionPower(Player player) {
        return optional().flatMap(current -> current.faction(player))
                .map(faction -> String.format(Locale.ROOT, "%,.0f/%,.0f", faction.power(), faction.powerMax()))
                .orElse("0/0");
    }

    public static String getFactionTop(Player player) {
        int factionId = getFactionId(player);
        return factionId == NO_FACTION ? "-" : getFactionTopRanks().getOrDefault(factionId, "-");
    }

    /** Native power ranking maintained from the persisted Vertex faction records. */
    public static Map<Integer, String> getFactionTopRanks() {
        Map<Integer, String> ranks = new HashMap<>();
        optional().ifPresent(current -> {
            List<FactionData> ranked = current.factions().stream().filter(faction -> !faction.system())
                    .sorted(Comparator.comparingDouble(FactionData::power).reversed()
                            .thenComparing(FactionData::tag, String.CASE_INSENSITIVE_ORDER)).toList();
            for (int index = 0; index < ranked.size(); index++) ranks.put(ranked.get(index).id(), String.valueOf(index + 1));
        });
        return ranks;
    }

    public static String getOnlineFactionCount(Player player) {
        int factionId = getFactionId(player);
        return factionId == NO_FACTION ? "0" : String.valueOf(Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> getFactionId(candidate) == factionId).count());
    }

    public static String getFactionName(int factionId) { return getFactionById(factionId).map(FactionData::tag).orElse("Neutral"); }
    public static int getFactionIdByTag(String tag) { return getFactionByTag(tag).map(FactionData::id).orElse(NO_FACTION); }
    public static boolean isAllyFaction(int first, int second) { return first != NO_FACTION && second != NO_FACTION && first != second && optional().map(current -> current.isAlly(first, second)).orElse(false); }
    public static boolean isEnemyFaction(int first, int second) { return first != NO_FACTION && second != NO_FACTION && first != second && optional().map(current -> current.isEnemy(first, second)).orElse(false); }

    public static int getFactionRank(int factionId) {
        if (factionId == NO_FACTION) return -1;
        return getFactionTopRanks().entrySet().stream().filter(entry -> entry.getKey() == factionId)
                .map(Map.Entry::getValue).mapToInt(Integer::parseInt).findFirst().orElse(-1);
    }

    public static String getClaimFactionTag(Location location) { return optional().map(current -> current.factionTagAt(location)).orElse(null); }
    public static int getClaimFactionId(Location location) { return optional().map(current -> current.factionIdAt(location)).orElse(NO_FACTION); }

    public static boolean isDisabledClaim(Location location, Set<String> disabledNames) {
        if (disabledNames == null || disabledNames.isEmpty()) return false;
        String tag = getClaimFactionTag(location);
        return tag != null && disabledNames.stream().anyMatch(name -> tag.equalsIgnoreCase(name));
    }

    public static Optional<FactionData> getFaction(Player player) { return optional().flatMap(current -> current.faction(player)); }
    public static Optional<FactionData> getFactionById(int factionId) { return factionId == NO_FACTION ? Optional.empty() : optional().flatMap(current -> current.faction(factionId)); }
    public static Optional<FactionData> getFactionByTag(String tag) { return optional().flatMap(current -> current.faction(tag)); }

    private static Optional<FactionService> optional() { return Optional.ofNullable(service); }
}
