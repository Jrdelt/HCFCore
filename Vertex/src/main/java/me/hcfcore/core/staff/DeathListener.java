package me.hcfcore.core.staff;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class DeathListener implements Listener {

    private final DeathManager deathManager;

    public DeathListener(DeathManager deathManager) {
        this.deathManager = deathManager;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        String cause = "UNKNOWN";
        if (player.getLastDamageCause() != null && player.getLastDamageCause().getCause() != null) {
            cause = player.getLastDamageCause().getCause().toString();
        }
        String killer = null;

        if (player.getKiller() != null) {
            killer = player.getKiller().getName();
        }

        Death death = Death.from(player, cause, killer);
        deathManager.saveDeath(player.getUniqueId(), death);
    }
}
