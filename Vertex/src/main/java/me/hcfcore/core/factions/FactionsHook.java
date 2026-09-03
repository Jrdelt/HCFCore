package me.hcfcore.core.factions;

import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.FPlayers;
import dev.kitteh.factions.permissible.Role;
import org.bukkit.entity.Player;

import java.util.List;

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
        return fPlayer.faction().tag();
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
        return fa.faction().id() == fb.faction().id();
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
        return fPlayer.faction().membersOnlineAsPlayers();
    }
}
