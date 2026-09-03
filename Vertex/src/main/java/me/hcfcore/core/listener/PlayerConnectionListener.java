package me.hcfcore.core.listener;

import me.hcfcore.core.pvp.CombatManager;
import me.hcfcore.core.scoreboard.ScoreboardManager;
import me.hcfcore.core.user.UserManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

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
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            userManager.load(event.getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scoreboardManager.setup(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        if (combatManager.logoutPenaltyEnabled() && combatManager.isTagged(player.getUniqueId())) {
            player.setHealth(0.0);
        }

        combatManager.clear(player.getUniqueId());
        combatManager.forgetPlayer(player.getUniqueId());
        scoreboardManager.remove(player.getUniqueId());
        userManager.unload(player.getUniqueId());
    }
}
