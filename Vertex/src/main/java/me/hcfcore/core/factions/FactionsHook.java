package me.hcfcore.core.factions;

import dev.kitteh.factions.FPlayer;
import dev.kitteh.factions.FPlayers;
import dev.kitteh.factions.permissible.Role;
import org.bukkit.entity.Player;

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
}
