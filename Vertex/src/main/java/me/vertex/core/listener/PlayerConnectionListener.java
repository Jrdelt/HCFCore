package me.vertex.core.listener;

import me.vertex.core.essentials.EssentialsHook;
import me.vertex.core.lang.MessageFormatter;
import me.vertex.core.luckperms.LuckPermsHook;
import me.vertex.core.pvp.CombatManager;
import me.vertex.core.scoreboard.ScoreboardManager;
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
    private volatile ScoreboardManager scoreboardManager;
    private final CombatManager combatManager;

    public PlayerConnectionListener(UserManager userManager, ScoreboardManager scoreboardManager, CombatManager combatManager) {
        this.userManager = userManager;
        this.scoreboardManager = scoreboardManager;
        this.combatManager = combatManager;
    }

    public void setScoreboardManager(ScoreboardManager scoreboardManager) {
        this.scoreboardManager = scoreboardManager;
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

        if (scoreboardManager == null) {
            return;
        }

        // Scoreboard rendering does not require the database-backed User
        // object.  Setting it up immediately avoids a one-tick race with a
        // slow MySQL login load, which otherwise left players without a
        // scoreboard for the rest of their session.
        scoreboardManager.setup(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (combatManager != null && combatManager.logoutPenaltyEnabled() && combatManager.isTagged(uuid)) {
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
        if (scoreboardManager != null) {
            scoreboardManager.remove(player.getUniqueId());
        }
        if (userManager != null) {
            userManager.unload(player.getUniqueId());
        }
    }
}
