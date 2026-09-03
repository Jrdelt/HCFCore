package me.hcfcore.core.factions;

import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.FPlayers;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.Factions;
import dev.kitteh.factions.permissible.Role;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Comparator;
import java.util.Locale;

/**
 * Thin wrapper around the dev.kitteh:factions API. Claims/teams stay owned
 * by the Factions plugin entirely -- this only reads display data from it.
 */
public final class FactionsHook {

    private FactionsHook() {
    }

    public static String getFactionTag(Player player) {
        FPlayer fPlayer = FPlayers.fPlayers().get(player.getUniqueId());
        if (fPlayer == null || !fPlayer.hasFaction()) {
            return "None";
        }
        Faction faction = fPlayer.faction();
        return faction == null ? "None" : faction.tag();
    }

    public static String getRoleName(Player player) {
        FPlayer fPlayer = FPlayers.fPlayers().get(player.getUniqueId());
        if (fPlayer == null || !fPlayer.hasFaction()) {
            return "None";
        }
        Role role = fPlayer.role();
        return role == null ? "None" : role.toString();
    }

    public static boolean isLeader(Player player) {
        FPlayer fPlayer = FPlayers.fPlayers().get(player.getUniqueId());
        return fPlayer != null && fPlayer.hasFaction() && Role.ADMIN.equals(fPlayer.role());
    }

    /**
     * True only when both players are actually in a faction and it's the
     * same one -- two factionless players are never considered "same
     * faction".
     */
    public static boolean isSameFaction(Player a, Player b) {
        FPlayer fa = FPlayers.fPlayers().get(a.getUniqueId());
        FPlayer fb = FPlayers.fPlayers().get(b.getUniqueId());
        if (fa == null || fb == null || !fa.hasFaction() || !fb.hasFaction()) {
            return false;
        }
        Faction factionA = fa.faction();
        Faction factionB = fb.faction();
        return factionA != null && factionB != null && factionA.id() == factionB.id();
    }

    /**
     * Every online member of the player's faction, including the player
     * themselves. Falls back to just the player if they're factionless.
     */
    public static List<Player> getOnlineFactionMembers(Player player) {
        FPlayer fPlayer = FPlayers.fPlayers().get(player.getUniqueId());
        if (fPlayer == null || !fPlayer.hasFaction()) {
            return List.of(player);
        }
        Faction faction = fPlayer.faction();
        return faction == null ? List.of(player) : faction.membersOnlineAsPlayers();
    }

    public static String getFactionPower(Player player) {
        Faction faction = getFaction(player);
        return faction == null ? "0/0" : String.format(Locale.ROOT, "%,.0f/%,.0f",
                faction.powerExact(), faction.powerMaxExact());
    }

    public static String getFactionTop(Player player) {
        Faction faction = getFaction(player);
        if (faction == null) {
            return "-";
        }
        List<Faction> factions = Factions.factions().all().stream()
                .filter(candidate -> candidate != null && candidate.isNormal())
                .sorted(Comparator.comparingDouble(Faction::powerExact).reversed()
                        .thenComparing(Faction::tag, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (int i = 0; i < factions.size(); i++) {
            if (factions.get(i).id() == faction.id()) {
                return String.valueOf(i + 1);
            }
        }
        return "-";
    }

    public static String getOnlineFactionCount(Player player) {
        Faction faction = getFaction(player);
        return faction == null ? "0" : String.valueOf(faction.membersOnlineAsPlayers().size());
    }

    private static Faction getFaction(Player player) {
        FPlayer fPlayer = FPlayers.fPlayers().get(player.getUniqueId());
        return fPlayer == null || !fPlayer.hasFaction() ? null : fPlayer.faction();
    }
}
