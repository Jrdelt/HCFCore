package me.hcfcore.core.factions;

import dev.kitteh.factions.Board;
import dev.kitteh.factions.FLocation;
import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.FPlayers;
import dev.kitteh.factions.Faction;
import dev.kitteh.factions.Factions;
import dev.kitteh.factions.permissible.Relation;
import dev.kitteh.factions.permissible.Role;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

/**
 * Thin wrapper around the dev.kitteh:factions API. Claims/teams stay owned
 * by the Factions plugin entirely -- this only reads display data from it.
 */
public final class FactionsHook {

    /** Sentinel faction id for a factionless player. */
    public static final int NO_FACTION = Integer.MIN_VALUE;

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
     * The player's faction id, or {@link #NO_FACTION} when they aren't in
     * one. Ids are only ever compared to each other, so callers must treat
     * NO_FACTION as "matches nothing" rather than as a faction of its own
     * -- otherwise every factionless player would read as teammates.
     */
    public static int getFactionId(Player player) {
        Faction faction = getFaction(player);
        return faction == null ? NO_FACTION : faction.id();
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

    /**
     * Get faction name from faction id, or "Neutral" if not in a faction.
     */
    public static String getFactionName(int factionId) {
        if (factionId == NO_FACTION) {
            return "Neutral";
        }
        for (Faction faction : Factions.factions().all()) {
            if (faction != null && faction.id() == factionId) {
                return faction.tag();
            }
        }
        return "Neutral";
    }

    /**
     * Whether two different factions have a genuine, mutual /f ally
     * relation -- FactionsUUID does expose this via {@code relationWish},
     * contrary to {@link #isAlly}'s claim otherwise. A relation is only as
     * friendly as its *more hostile* side wishes it to be (a one-sided ally
     * wish doesn't make a real alliance, but a one-sided enemy wish does
     * make real hostility), so this takes the higher-ordinal (more hostile)
     * of the two one-directional wishes. Same faction, or either side
     * factionless, is never "ally" -- callers should check same-faction
     * (see {@link #isSameFaction}) separately first.
     */
    public static boolean isAllyFaction(int factionIdA, int factionIdB) {
        if (factionIdA == NO_FACTION || factionIdB == NO_FACTION || factionIdA == factionIdB) {
            return false;
        }
        Faction factionA = factionById(factionIdA);
        Faction factionB = factionById(factionIdB);
        if (factionA == null || factionB == null) {
            return false;
        }
        return effectiveRelation(factionA, factionB).isAlly();
    }

    /**
     * Whether two different factions have a genuine, mutual /f enemy
     * relation -- see {@link #isAllyFaction} for how "mutual" is resolved.
     */
    public static boolean isEnemyFaction(int factionIdA, int factionIdB) {
        if (factionIdA == NO_FACTION || factionIdB == NO_FACTION || factionIdA == factionIdB) {
            return false;
        }
        Faction factionA = factionById(factionIdA);
        Faction factionB = factionById(factionIdB);
        if (factionA == null || factionB == null) {
            return false;
        }
        return effectiveRelation(factionA, factionB).isEnemy();
    }

    /** The more hostile (higher-ordinal) of the two factions' one-directional relationWish() calls. */
    private static Relation effectiveRelation(Faction factionA, Faction factionB) {
        Relation wishFromA = factionA.relationWish(factionB);
        Relation wishFromB = factionB.relationWish(factionA);
        return wishFromA.ordinal() >= wishFromB.ordinal() ? wishFromA : wishFromB;
    }

    private static Faction factionById(int factionId) {
        if (factionId == NO_FACTION) {
            return null;
        }
        for (Faction faction : Factions.factions().all()) {
            if (faction != null && faction.id() == factionId) {
                return faction;
            }
        }
        return null;
    }

    public static int getFactionRank(int factionId) {
        if (factionId == NO_FACTION) {
            return -1;
        }
        List<Faction> factions = Factions.factions().all().stream()
                .filter(candidate -> candidate != null && candidate.isNormal())
                .sorted(Comparator.comparingDouble(Faction::powerExact).reversed()
                        .thenComparing(Faction::tag, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (int i = 0; i < factions.size(); i++) {
            if (factions.get(i).id() == factionId) {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * The tag of the faction claiming this location -- the system
     * WarZone/SafeZone factions included, since they're claimed the same
     * way as any player faction -- or null for unclaimed wilderness.
     */
    public static String getClaimFactionTag(Location location) {
        Faction faction = Board.board().factionAt(new FLocation(location));
        return faction == null || faction.isWilderness() ? null : faction.tag();
    }

    /**
     * True when the claim at this location belongs to a faction whose name
     * is in `disabledNames` (case-insensitive) -- e.g. a SafeZone claim
     * listed in abilities.disabled-claim-names. Wilderness and any claim
     * not in the list return false.
     */
    public static boolean isDisabledClaim(Location location, Set<String> disabledNames) {
        if (disabledNames.isEmpty()) {
            return false;
        }
        String tag = getClaimFactionTag(location);
        if (tag == null) {
            return false;
        }
        for (String disabled : disabledNames) {
            if (tag.equalsIgnoreCase(disabled)) {
                return true;
            }
        }
        return false;
    }

    private static Faction getFaction(Player player) {
        FPlayer fPlayer = FPlayers.fPlayers().get(player.getUniqueId());
        return fPlayer == null || !fPlayer.hasFaction() ? null : fPlayer.faction();
    }
}
