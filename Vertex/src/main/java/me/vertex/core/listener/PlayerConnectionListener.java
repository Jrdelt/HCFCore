package me.vertex.core.listener;

import me.vertex.core.essentials.EssentialsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.luckperms.LuckPermsHook;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.pvp.GhostPlayerManager;
import me.vertex.core.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public final class PlayerConnectionListener implements Listener {

    private final UserManager userManager;
    private final CombatManager combatManager;
    private volatile GhostPlayerManager ghostPlayerManager;

    public PlayerConnectionListener(UserManager userManager, CombatManager combatManager) {
        this.userManager = userManager;
        this.combatManager = combatManager;
    }

    public void setGhostPlayerManager(GhostPlayerManager ghostPlayerManager) {
        this.ghostPlayerManager = ghostPlayerManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // MONITOR runs last, after every whitelist/ban/duplicate-login check
        // has had a chance to deny the login -- only load (and therefore
        // only leak an entry to clean up later) for logins that will
        // actually reach PlayerJoinEvent/PlayerQuitEvent.
        if (userManager != null && event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            userManager.load(event.getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        GhostPlayerManager ghosts = ghostPlayerManager;
        if (ghosts != null) {
            ghosts.handleJoin(player);
        }

        // Set player display name with rank (and Essentials nickname, if any).
        // MessageFormatter.deserialize, not Component.text -- resolveName's
        // output may carry MiniMessage color tags (from an Essentials
        // nickname), and Component.text renders those as literal characters
        // instead of parsing them.
        String rank = LuckPermsHook.getPrimaryGroupDisplayName(player);
        String displayName = EssentialsHook.resolveName(player);
        player.displayName(MessageFormatter.deserialize(rank != null && !rank.isBlank()
                ? "[" + rank + "] " + displayName
                : displayName));

    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        GhostPlayerManager ghosts = ghostPlayerManager;
        boolean ghostSpawned = ghosts != null && isForcedDisconnect(event)
                && ghosts.handleForcedDisconnect(player);
        if (ghostSpawned && combatManager != null && combatManager.isTagged(uuid)) {
            UUID opponentId = combatManager.getOpponentId(uuid);
            // A Ghost Player is now the fight's killable representation, so
            // consume the normal instant-death penalty without leaving its
            // opponent tied to a player who is no longer online.
            combatManager.clearOwnTag(uuid);
            combatManager.releaseOpponent(opponentId);
        } else if (combatManager != null && combatManager.logoutPenaltyEnabled() && combatManager.isTagged(uuid)) {
            UUID opponentId = combatManager.getOpponentId(uuid);
            // setHealth(0) triggers a synchronous PlayerDeathEvent, which
            // CombatListener.onDeath handles by applying the killer's
            // post-kill cooldown and then clearing *only* this player's own
            // tag via clearOwnTag() (deliberately non-cascading, so it
            // doesn't stomp that cooldown it just set). That means the
            // combatManager.clear(uuid) below will find this player's entry
            // already gone and never reach its cascade -- so the mutual
            // opponent is released/notified here explicitly instead, using
            // the pairing as it stood right before the death.
            player.setHealth(0.0);
            combatManager.releaseOpponent(opponentId);
        }

        if (combatManager != null) {
            combatManager.clear(uuid);
            combatManager.forgetPlayer(uuid);
        }
        if (userManager != null) {
            userManager.unload(player.getUniqueId());
        }
    }

    /** Paper exposes the disconnect cause directly; a plain client logout is DISCONNECTED. */
    private static boolean isForcedDisconnect(PlayerQuitEvent event) {
        PlayerQuitEvent.QuitReason reason = event.getReason();
        return reason == PlayerQuitEvent.QuitReason.KICKED
                || reason == PlayerQuitEvent.QuitReason.TIMED_OUT
                || reason == PlayerQuitEvent.QuitReason.ERRONEOUS_STATE;
    }
}
